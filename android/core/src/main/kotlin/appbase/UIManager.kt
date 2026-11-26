package appbase

import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

enum class UILayer { PAGE, OVERLAY, MODAL, TOAST, SYSTEM }

data class UIRoute(val name: String, val layer: UILayer, val dismissible: Boolean = true,
    val blocking: Boolean = layer == UILayer.MODAL || layer == UILayer.SYSTEM,
    val restorable: Boolean = layer == UILayer.PAGE)

data class UIInstance(val id: String, val route: UIRoute, val tab: String,
    val parent: String?, val arguments: Map<String, String>)

data class UIPrompt(val id: String, val route: String, val tab: String, val owner: String,
    val key: String, val priority: Int, val expiresAt: Long, val arguments: Map<String, String>)

data class UISnapshot(val tab: String, val instances: List<UIInstance>,
    val visible: Set<String>, val interactive: String?, val foreground: Boolean,
    val prompts: List<UIPrompt>)

sealed interface UIResult {
    data class Completed(val values: Map<String, String>) : UIResult
    object Cancelled : UIResult
}

class UIManager(routes: Collection<UIRoute>, roots: Map<String, String>,
    private val capacity: Int = 128, private val queueCapacity: Int = 32,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    dispatcher: CoroutineDispatcher = Dispatchers.Default) : AutoCloseable {
    private val definitions = routes.associateBy { it.name }
    private val roots = roots.toMap()
    private val entries = linkedMapOf<String, UIInstance>()
    private val rootIds = mutableSetOf<String>()
    private val jobs = mutableMapOf<String, CompletableJob>()
    private val results = mutableMapOf<String, CompletableDeferred<UIResult>>()
    private val prompts = mutableListOf<UIPrompt>()
    private val activeKeys = mutableMapOf<String, String>()
    private val notices = MutableStateFlow<List<String>>(emptyList())
    val notifications = notices.asStateFlow()
    private val rootJob = SupervisorJob()
    private val context = dispatcher + rootJob
    private var selected = roots.keys.firstOrNull() ?: error("At least one root is required")
    private var foreground = true
    private var closed = false
    init {
        require(roots.isNotEmpty() && capacity >= roots.size && queueCapacity > 0)
        require(definitions.size == routes.size)
        roots.forEach { (tab, route) ->
            val item = add(route, tab, null, emptyMap(), UILayer.PAGE)
            rootIds.add(item.id)
        }
    }
    private val mutableState = MutableStateFlow(snapshot())
    val state = mutableState.asStateFlow()

    private fun add(route: String, tab: String, parent: String?, args: Map<String, String>,
        layer: UILayer, id: String = UUID.randomUUID().toString()): UIInstance {
        check(!closed && entries.size < capacity)
        val definition = definitions.getValue(route)
        require(definition.layer == layer)
        require(args.size <= 64 && args.all { it.key.length <= 128 && it.value.length <= 8192 })
        val item = UIInstance(id, definition, tab, parent, args.toMap())
        entries[id] = item
        jobs[id] = SupervisorJob(rootJob)
        results[id] = CompletableDeferred()
        return item
    }
    private fun page(tab: String = selected) = entries.values.last { it.tab == tab && it.route.layer == UILayer.PAGE }
    private fun presentations(tab: String = selected) = entries.values.filter { it.tab == tab && it.route.layer != UILayer.PAGE }
    private fun modal() = presentations().lastOrNull { it.route.layer == UILayer.MODAL }
    private fun snapshot(): UISnapshot {
        if (closed) return UISnapshot(selected, emptyList(), emptySet(), null, false, emptyList())
        val active = page()
        val layers = presentations().sortedBy { it.route.layer.ordinal }
        val visible = (listOf(active) + layers).map { it.id }.toSet()
        val input = layers.lastOrNull { it.route.blocking } ?: active
        return UISnapshot(selected, entries.values.toList(), visible,
            input.id.takeIf { foreground }, foreground, prompts.toList())
    }
    private fun publish() { mutableState.value = snapshot() }
    @Synchronized fun openPage(route: String, arguments: Map<String, String> = emptyMap()): String {
        check(presentations().none { it.route.blocking }) { "Close blocking presentation first" }
        val item = add(route, selected, page().id, arguments, UILayer.PAGE)
        publish(); return item.id
    }
    @Synchronized fun openModal(route: String, arguments: Map<String, String> = emptyMap(), owner: String? = null): String {
        val parent = modal()?.id ?: page().id
        require(owner == null || owner == parent) { "Owner must be the current page or modal" }
        val item = add(route, selected, parent, arguments, UILayer.MODAL)
        publish(); return item.id
    }
    @Synchronized fun showOverlay(route: String, arguments: Map<String, String> = emptyMap()): String {
        val item = add(route, selected, modal()?.id ?: page().id, arguments, UILayer.OVERLAY)
        publish(); return item.id
    }
    @Synchronized fun notify(message: String): Boolean {
        if (closed || message.isBlank() || message.length > 2048 || notices.value.size >= queueCapacity) return false
        notices.value = notices.value + message
        return true
    }
    @Synchronized fun consumeNotice(message: String) {
        if (notices.value.firstOrNull() == message) notices.value = notices.value.drop(1)
    }
    @Synchronized fun toast(route: String, arguments: Map<String, String> = emptyMap()): String {
        val item = add(route, selected, page().id, arguments, UILayer.TOAST)
        publish(); return item.id
    }
    @Synchronized fun showSystem(route: String, arguments: Map<String, String> = emptyMap()): String {
        val item = add(route, selected, page().id, arguments, UILayer.SYSTEM)
        publish(); return item.id
    }
    @Synchronized fun enqueueModal(route: String, key: String, priority: Int = 0,
        ttlMs: Long = 60_000, arguments: Map<String, String> = emptyMap()): String? {
        check(!closed)
        require(definitions.getValue(route).layer == UILayer.MODAL && key.isNotBlank() && ttlMs in 1..86_400_000)
        require(arguments.size <= 64 && arguments.all { it.key.length <= 128 && it.value.length <= 8192 })
        prune()
        if (prompts.size >= queueCapacity || prompts.any { it.key == key } || key in activeKeys.values) { publish(); return null }
        val id = UUID.randomUUID().toString()
        prompts.add(UIPrompt(id, route, selected, page().id, key, priority, clock() + ttlMs, arguments.toMap()))
        promote(); publish(); return id
    }
    private fun prune() { prompts.removeAll { it.expiresAt <= clock() || it.owner !in entries } }
    private fun promote() {
        prune()
        if (!foreground || presentations().any { it.route.blocking } || entries.size >= capacity) return
        val next = prompts.filter { it.tab == selected && it.owner == page().id }.maxByOrNull { it.priority } ?: return
        prompts.remove(next)
        add(next.route, next.tab, next.owner, next.arguments, UILayer.MODAL, next.id)
        activeKeys[next.id] = next.key
    }
    @Synchronized fun refresh() { if (!closed) { promote(); publish() } }
    @Synchronized fun cancelQueuedModal(id: String): Boolean {
        val removed = prompts.removeAll { it.id == id }
        if (removed) publish()
        return removed
    }
    private fun remove(id: String, result: UIResult) {
        entries.values.filter { it.parent == id }.map { it.id }.asReversed().forEach { remove(it, UIResult.Cancelled) }
        entries.remove(id)
        activeKeys.remove(id)
        prompts.removeAll { it.owner == id }
        jobs.remove(id)?.cancel()
        results.remove(id)?.complete(result)
    }
    @Synchronized fun close(id: String, result: UIResult = UIResult.Cancelled): Boolean {
        if (id in rootIds || id !in entries) return false
        remove(id, result); promote(); publish(); return true
    }
    @Synchronized fun back(): Boolean {
        if (closed) return false
        val target = presentations().sortedBy { it.route.layer.ordinal }.lastOrNull { it.route.blocking }
            ?: page()
        if (target.id in rootIds) return false
        if (!target.route.dismissible) return true
        return close(target.id)
    }
    @Synchronized fun selectRoot(tab: String) {
        check(!closed); require(tab in roots)
        if (presentations().any { it.route.blocking }) return
        selected = tab; promote(); publish()
    }
    @Synchronized fun popModal(): Boolean {
        if (closed) return false
        val top = modal() ?: return false
        if (!top.route.dismissible) return false
        return close(top.id)
    }
    @Synchronized fun canGoBack(): Boolean {
        if (closed) return false
        return presentations().any { it.route.blocking } || page().id !in rootIds
    }
    @Synchronized fun setForeground(value: Boolean) {
        if (closed) return
        foreground = value; promote(); publish()
    }
    @Synchronized fun scope(id: String): CoroutineScope? =
        jobs[id]?.let { CoroutineScope(context + it) }
    @Synchronized fun result(id: String): Deferred<UIResult>? = results[id]
    @Synchronized fun isAlive(id: String): Boolean = id in entries
    @Synchronized override fun close() {
        if (closed) return
        closed = true
        entries.keys.toList().asReversed().forEach { if (it in entries) remove(it, UIResult.Cancelled) }
        prompts.clear(); notices.value = emptyList(); rootJob.cancel(); publish()
    }
}
