package appbase

import java.util.UUID
import java.util.Collections
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
        val item = UIInstance(id, definition, tab, parent, Collections.unmodifiableMap(LinkedHashMap(args)))
        entries[id] = item
        jobs[id] = SupervisorJob(rootJob)
        results[id] = CompletableDeferred()
        return item
    }
    private fun page(tab: String = selected) = entries.values.last { it.tab == tab && it.route.layer == UILayer.PAGE }
    private fun presentations(tab: String = selected): List<UIInstance> {
        val active = page(tab).id
        return entries.values.filter { item ->
            if (item.tab != tab || item.route.layer == UILayer.PAGE) false
            else {
                var owner = item.parent
                while (owner != null && entries[owner]?.route?.layer != UILayer.PAGE) {
                    owner = entries[owner]?.parent
                }
                owner == active
            }
        }
    }
    private fun modal() = presentations().lastOrNull { it.route.layer == UILayer.MODAL }
    private fun snapshot(): UISnapshot {
        if (closed) return UISnapshot(selected, emptyList(), emptySet(), null, false, emptyList())
        val active = page()
        val layers = presentations().sortedBy { it.route.layer.ordinal }
        val visible = (listOf(active) + layers).map { it.id }.toSet()
        val input = layers.lastOrNull { it.route.blocking } ?: active
        return UISnapshot(selected, Collections.unmodifiableList(entries.values.toList()), Collections.unmodifiableSet(visible),
            input.id.takeIf { foreground }, foreground, Collections.unmodifiableList(prompts.toList()))
    }
    private fun publish() { mutableState.value = snapshot() }

    /** Attach once per native owner, before rendering. Caller closes registration on detach. */
    fun bindSavedState(savedState: SavedState, key: String = "appbase.navigation"): AutoCloseable {
        val saved = runCatching { savedState.get(key) }.getOrNull()
        if (saved is Map<*, *> && saved.all { it.key is String && it.value is String }) {
            restoreNavigation(saved.entries.associate { (k, v) -> k as String to v as String })
        }
        return savedState.register(key) { saveNavigation() }
    }

    @Synchronized fun saveNavigation(): Map<String, String> {
        if (closed) return emptyMap()
        val output = linkedMapOf("version" to "1", "selected" to selected)
        val retained = mutableSetOf<String>()
        var index = 0
        for (item in entries.values) {
            if (!item.route.restorable || item.route.layer !in setOf(UILayer.PAGE, UILayer.MODAL)) continue
            if (item.parent != null && item.parent !in retained) continue
            val prefix = "p.${index}."
            output[prefix + "id"] = item.id
            output[prefix + "route"] = item.route.name
            output[prefix + "tab"] = item.tab
            output[prefix + "parent"] = item.parent.orEmpty()
            // Arguments are opt-in: do not persist arbitrary credentials or payloads.
            retained.add(item.id)
            index++
        }
        output["count"] = index.toString()
        return output
    }

    fun restoreNavigation(saved: Map<String, String>): Boolean { return mutate { retired ->
        if (closed || entries.size != rootIds.size || saved["version"] != "1") return false
        val tab = saved["selected"] ?: return false
        if (tab !in roots) return false
        val count = saved["count"]?.toIntOrNull() ?: return false
        if (count !in roots.size..capacity) return false
        val restored = linkedMapOf<String, UIInstance>()
        val restoredRoots = mutableSetOf<String>()
        val lastPage = mutableMapOf<String, String>()
        val lastModal = mutableMapOf<String, String>()
        for (i in 0 until count) {
            val p = "p.$i."
            val id = saved[p + "id"] ?: return false
            if (runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false).not() || id in restored) return false
            val route = definitions[saved[p + "route"]] ?: return false
            val root = saved[p + "tab"] ?: return false
            if (root !in roots || !route.restorable) return false
            val parent = saved[p + "parent"]?.ifEmpty { null }
            when (route.layer) {
                UILayer.PAGE -> {
                    if (parent == null) {
                        if (root in lastPage || roots[root] != route.name) return false
                        restoredRoots.add(id)
                    } else if (parent != lastPage[root] || root in lastModal) return false
                    lastPage[root] = id
                }
                UILayer.MODAL -> {
                    if (parent == null || parent != (lastModal[root] ?: lastPage[root])) return false
                    lastModal[root] = id
                }
                else -> return false
            }
            restored[id] = UIInstance(id, route, root, parent, emptyMap())
        }
        if (lastPage.keys != roots.keys) return false
        entries.keys.toList().forEach { remove(it, UIResult.Cancelled, retired) }
        rootIds.clear()
        for (item in restored.values) {
            add(item.route.name, item.tab, item.parent, item.arguments, item.route.layer, item.id)
        }
        rootIds.addAll(restoredRoots)
        selected = tab
        publish()
        return true
    } }
    @Synchronized fun openPage(route: String, arguments: Map<String, String> = emptyMap()): String {
        check(presentations().none { it.route.blocking }) { "Close blocking presentation first" }
        val item = add(route, selected, page().id, arguments, UILayer.PAGE)
        publish(); return item.id
    }
    @Synchronized fun openModal(route: String, arguments: Map<String, String> = emptyMap(), owner: String? = null): String {
        check(!closed)
        check(presentations().none { it.route.blocking && it.route.layer != UILayer.MODAL }) { "Close blocking presentation first" }
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
        notices.value = Collections.unmodifiableList(notices.value + message)
        return true
    }
    @Synchronized fun consumeNotice(message: String) {
        if (notices.value.firstOrNull() == message) notices.value = Collections.unmodifiableList(notices.value.drop(1))
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
        prompts.add(UIPrompt(id, route, selected, page().id, key, priority, clock() + ttlMs, Collections.unmodifiableMap(LinkedHashMap(arguments))))
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
    private data class Retired(val job: Job?, val deferred: CompletableDeferred<UIResult>?, val result: UIResult)
    private inline fun <T> mutate(block: (MutableList<Retired>) -> T): T {
        val retired = mutableListOf<Retired>()
        try { return synchronized(this) { block(retired) } }
        finally {
            retired.forEach { item ->
                runCatching { item.job?.cancel() }
                runCatching { item.deferred?.complete(item.result) }
            }
        }
    }
    private fun remove(id: String, result: UIResult, retired: MutableList<Retired>) {
        entries.values.filter { it.parent == id }.map { it.id }.asReversed().forEach { remove(it, UIResult.Cancelled, retired) }
        entries.remove(id)
        activeKeys.remove(id)
        prompts.removeAll { it.owner == id }
        retired.add(Retired(jobs.remove(id), results.remove(id), result))
    }
    fun close(id: String, result: UIResult = UIResult.Cancelled): Boolean { return mutate { retired ->
        if (id in rootIds || id !in entries) return false
        val safeResult = if (result is UIResult.Completed) UIResult.Completed(Collections.unmodifiableMap(LinkedHashMap(result.values))) else result
        remove(id, safeResult, retired); promote(); publish(); return true
    } }
    fun back(): Boolean { return mutate { retired ->
        if (closed) return false
        val target = presentations().sortedBy { it.route.layer.ordinal }.lastOrNull { it.route.blocking }
            ?: page()
        if (target.id in rootIds) return false
        if (!target.route.dismissible) return true
        remove(target.id, UIResult.Cancelled, retired); promote(); publish(); return true
    } }
    @Synchronized fun selectRoot(tab: String) {
        check(!closed); require(tab in roots)
        if (presentations().any { it.route.blocking }) return
        selected = tab; promote(); publish()
    }
    fun popModal(): Boolean { return mutate { retired ->
        if (closed) return false
        val top = modal() ?: return false
        if (!top.route.dismissible) return false
        remove(top.id, UIResult.Cancelled, retired); promote(); publish(); return true
    } }
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
    override fun close() {
        mutate { retired ->
            if (closed) return
            closed = true
            entries.keys.toList().asReversed().forEach { if (it in entries) remove(it, UIResult.Cancelled, retired) }
            prompts.clear(); notices.value = emptyList(); publish()
            retired.add(Retired(rootJob, null, UIResult.Cancelled))
        }
    }
}
