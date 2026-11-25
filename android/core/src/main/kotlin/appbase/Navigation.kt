package appbase

import kotlinx.coroutines.flow.*
import java.util.concurrent.atomic.AtomicLong

enum class Layer { SCREEN, OVERLAY, MODAL, TOAST, SYSTEM }
data class UIEntry(val instance: Long, val id: String, val layer: Layer)
/** Pure state machine. Renderers own composition and page-scoped resources. */
class Navigation(definitions: Map<String, Layer>, root: String, private val capacity: Int = 128) {
    init { require(capacity > 0) }
    private val definitions = definitions.toMap()
    private val sequence = AtomicLong()
    private val entries = MutableStateFlow(listOf(entry(root, Layer.SCREEN)))
    val stack = entries.asStateFlow()
    private fun entry(id: String, layer: Layer): UIEntry {
        require(definitions[id] == layer) { "Unknown UI or incorrect layer: $id" }
        return UIEntry(sequence.incrementAndGet(), id, layer)
    }
    @Synchronized fun open(id: String): Long {
        val layer = definitions[id] ?: error("Unknown UI: $id")
        check(entries.value.size < capacity) { "UI capacity exceeded" }
        val item = entry(id, layer)
        entries.value = entries.value + item
        return item.instance
    }
    @Synchronized fun close(instance: Long): Boolean {
        val current = entries.value
        if (current.first().instance == instance || current.none { it.instance == instance }) return false
        entries.value = current.filterNot { it.instance == instance }; return true
    }
    @Synchronized fun push(id: String): Long {
        require(definitions[id] == Layer.SCREEN)
        return open(id)
    }
    @Synchronized fun pop(): Boolean {
        val top = entries.value.last { it.layer == Layer.SCREEN }
        return close(top.instance)
    }
    @Synchronized fun back(): Boolean {
        val top = entries.value.drop(1).filter { it.layer != Layer.TOAST }.maxWithOrNull(compareBy<UIEntry> { it.layer.ordinal }.thenBy { it.instance }) ?: return false
        return close(top.instance)
    }
}
