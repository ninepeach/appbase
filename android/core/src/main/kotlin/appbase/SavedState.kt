package appbase

data class SavedStateLimits(
    val maxEntryBytes: Int = 32768,
    val maxScopeBytes: Int = 65536,
    val maxTotalBytes: Int = 131072,
    val maxKeyBytes: Int = 128,
    val maxEntries: Int = 256
) {
    init { require(maxEntryBytes > 0 && maxEntryBytes <= maxScopeBytes && maxScopeBytes <= maxTotalBytes && maxKeyBytes > 0 && maxEntries > 0) }
}

/** Native owner supplies one aggregate snapshot. All operations are owner-thread confined. */
interface SavedStatePlatform {
    fun consume(key: String): Map<String, Map<String, String>>?
    fun register(key: String, provider: () -> Map<String, Map<String, String>>): AutoCloseable
}

/** Transient, bounded state. No disk I/O or background workers. */
class SavedState private constructor(
    private val store: Store, private val prefix: String
) : AutoCloseable {
    constructor(platform: SavedStatePlatform, limits: SavedStateLimits = SavedStateLimits(),
        key: String = "appbase.savedState", warning: (String) -> Unit = {}) :
        this(Store(platform, limits, key, warning), "")

    private class Store(platform: SavedStatePlatform, val limits: SavedStateLimits, key: String, val warning: (String) -> Unit) {
        val values = linkedMapOf<String, Map<String, String>>()
        val providers = linkedMapOf<String, () -> Map<String, String>>()
        var closed = false
        fun warn(code: String) { runCatching { warning(code) } }
        fun bytes(key: String, value: Map<String, String>): Long =
            32L + key.toByteArray().size + value.entries.sumOf { 24L + it.key.toByteArray().size + it.value.toByteArray().size }
        fun valid(key: String, value: Map<String, String>, target: Map<String, Map<String, String>>): Boolean {
            if (value.size > limits.maxEntries || value.keys.any { it.toByteArray().size > limits.maxKeyBytes }) return false
            val n = bytes(key, value)
            if (n > limits.maxEntryBytes || (key !in target && target.size >= limits.maxEntries)) return false
            val scope = key.substringBeforeLast('/', "")
            val other = target.filterKeys { it != key }
            return n + other.entries.sumOf { bytes(it.key, it.value) } <= limits.maxTotalBytes &&
                n + other.filterKeys { it.substringBeforeLast('/', "") == scope }.entries.sumOf { bytes(it.key, it.value) } <= limits.maxScopeBytes
        }
        fun snapshot(): Map<String, Map<String, String>> {
            val result = LinkedHashMap(values)
            for ((key, provider) in providers.toList()) {
                val value = runCatching { provider().toMap() }.getOrNull()
                if (value != null && valid(key, value, result)) result[key] = value
                else { result.remove(key); warn("savedstate.provider.rejected") }
            }
            return result
        }
        init {
            runCatching { platform.consume(key) }.onFailure { warn("savedstate.restore.failed") }.getOrNull()?.forEach { (k, v) ->
                if (k.toByteArray().size <= limits.maxKeyBytes && valid(k, v, values)) values[k] = v.toMap()
                else warn("savedstate.restore.rejected")
            }
        }
        val registration = platform.register(key) { snapshot() }
    }

    val limits get() = store.limits
    private var closed = false
    private fun checkOpen() { check(!closed && !store.closed) }
    private fun full(key: String): String {
        checkOpen()
        require(key.isNotBlank() && '/' !in key)
        return (prefix + key).also { require(it.toByteArray().size <= limits.maxKeyBytes) }
    }
    fun scope(name: String): SavedState = SavedState(store, full(name) + "/")
    fun get(key: String): Map<String, String>? = store.values[full(key)]?.toMap()
    fun contains(key: String): Boolean = full(key).let { it in store.values || it in store.providers }
    fun set(key: String, value: Map<String, String>): Boolean {
        val k = full(key)
        if (k in store.providers || !store.valid(k, value, store.values) ||
            (k !in store.values && (store.values.keys + store.providers.keys).size >= limits.maxEntries)) {
            store.warn("savedstate.set.rejected"); return false
        }
        store.values[k] = value.toMap()
        return true
    }
    fun remove(key: String): Boolean {
        val k = full(key)
        val provider = store.providers.remove(k)
        val value = store.values.remove(k)
        return provider != null || value != null
    }
    fun clear() {
        checkOpen()
        store.values.keys.removeAll { it.startsWith(prefix) }
        store.providers.keys.removeAll { it.startsWith(prefix) }
    }
    fun register(key: String, provider: () -> Map<String, String>): AutoCloseable {
        val k = full(key)
        require(k !in store.providers)
        require(k in store.values || (store.values.keys + store.providers.keys).size < limits.maxEntries)
        store.providers[k] = provider
        return AutoCloseable { if (store.providers[k] === provider) store.providers.remove(k) }
    }
    fun size(): Long {
        checkOpen()
        return store.values.filterKeys { it.startsWith(prefix) }.entries.sumOf { store.bytes(it.key, it.value) }
    }
    override fun close() {
        if (closed || store.closed) return
        clear()
        closed = true
        if (prefix.isEmpty()) { store.closed = true; store.registration.close() }
    }
}
