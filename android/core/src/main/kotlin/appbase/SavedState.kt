package appbase

data class SavedStateLimits(
    val maxEntryBytes: Int = 32768,
    val maxScopeBytes: Int = 65536,
    val maxTotalBytes: Int = 131072,
    val maxKeyBytes: Int = 128,
    val maxEntries: Int = 256,
    val maxDepth: Int = 8
) {
    init { require(maxEntryBytes > 0 && maxEntryBytes <= maxScopeBytes && maxScopeBytes <= maxTotalBytes && maxKeyBytes > 0 && maxEntries > 0 && maxDepth in 1..32) }
}

/** Native owner supplies one aggregate snapshot. All operations are owner-thread confined. */
interface SavedStatePlatform {
    fun consume(key: String): Map<String, Any>?
    fun register(key: String, provider: () -> Map<String, Any>): AutoCloseable
}

/** Transient, bounded state. No disk I/O or background workers. */
class SavedState private constructor(
    private val store: Store, private val prefix: String
) : AutoCloseable {
    constructor(platform: SavedStatePlatform, limits: SavedStateLimits = SavedStateLimits(),
        key: String = "appbase.savedState", warning: (String) -> Unit = {}) :
        this(Store(platform, limits, key, warning), "")

    private class Store(platform: SavedStatePlatform, val limits: SavedStateLimits, key: String, val warning: (String) -> Unit) {
        val values = linkedMapOf<String, Any>()
        val providers = linkedMapOf<String, () -> Any>()
        var closed = false
        fun warn(code: String) { runCatching { warning(code) } }
        fun copy(value: Any, depth: Int = 0): Any {
            require(depth <= limits.maxDepth)
            return when (value) {
                is String -> value.also { require(it.length <= limits.maxEntryBytes) }
                is Boolean, is Int, is Long, is Double -> value
                is ByteArray -> value.also { require(it.size <= limits.maxEntryBytes) }.copyOf()
                is List<*> -> { require(value.size <= limits.maxEntries); value.map { copy(requireNotNull(it), depth + 1) } }
                is Map<*, *> -> {
                    require(value.size <= limits.maxEntries)
                    value.entries.associate { (k, v) ->
                        require(k is String && k.toByteArray().size <= limits.maxKeyBytes)
                        k to copy(requireNotNull(v), depth + 1)
                    }
                }
                else -> error("Unsupported saved state type")
            }
        }
        fun measure(value: Any, depth: Int = 0): Long {
            require(depth <= limits.maxDepth)
            return when (value) {
                is String -> 16L + value.toByteArray().size
                is Boolean, is Int, is Long, is Double -> 16L
                is ByteArray -> 16L + value.size
                is List<*> -> { require(value.size <= limits.maxEntries); 16L + value.sumOf { measure(requireNotNull(it), depth + 1) } }
                is Map<*, *> -> { require(value.size <= limits.maxEntries); 16L + value.entries.sumOf { (k,v) ->
                    require(k is String && k.toByteArray().size <= limits.maxKeyBytes)
                    16L + k.toByteArray().size + measure(requireNotNull(v), depth + 1)
                } }
                else -> error("Unsupported saved state type")
            }
        }
        fun bytes(key: String, value: Any): Long = 32L + key.toByteArray().size + measure(value)
        fun valid(key: String, value: Any, target: Map<String, Any>): Boolean {
            val n = runCatching { bytes(key, value) }.getOrNull() ?: return false
            if (n > limits.maxEntryBytes || (key !in target && target.size >= limits.maxEntries)) return false
            val scopes = key.indices.filter { key[it] == '/' }.map { key.substring(0, it + 1) }.ifEmpty { listOf("") }
            val other = target.filterKeys { it != key }
            return n + other.entries.sumOf { bytes(it.key, it.value) } <= limits.maxTotalBytes &&
                scopes.all { scope -> n + other.filterKeys { if (scope.isEmpty()) '/' !in it else it.startsWith(scope) }.entries.sumOf { bytes(it.key, it.value) } <= limits.maxScopeBytes }
        }
        fun snapshot(): Map<String, Any> {
            val result = LinkedHashMap(values)
            for ((key, provider) in providers.toList()) {
                val value = runCatching { provider() }.getOrNull()
                if (value != null && valid(key, value, result)) result[key] = copy(value)
                else { result.remove(key); warn("savedstate.provider.rejected") }
            }
            return result
        }
        init {
            runCatching { platform.consume(key) }.onFailure { warn("savedstate.restore.failed") }.getOrNull()?.forEach { (k, v) ->
                if (k.toByteArray().size <= limits.maxKeyBytes && valid(k, v, values)) values[k] = copy(v)
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
    fun get(key: String): Any? = store.values[full(key)]?.let { store.copy(it) }
    fun contains(key: String): Boolean = full(key).let { it in store.values || it in store.providers }
    fun set(key: String, value: Any): Boolean {
        val k = full(key)
        if (k in store.providers || !store.valid(k, value, store.values) ||
            (k !in store.values && (store.values.keys + store.providers.keys).size >= limits.maxEntries)) {
            store.warn("savedstate.set.rejected"); return false
        }
        store.values[k] = store.copy(value)
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
    fun register(key: String, provider: () -> Any): AutoCloseable {
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
