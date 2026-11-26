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

/** Native owner supplies one aggregate snapshot. Platform registration and disposal follow the native owner's thread rules. */
interface SavedStatePlatform {
    fun consume(key: String): Map<String, Any>?
    fun register(key: String, provider: () -> Map<String, Any>): AutoCloseable
}

/** Transient, bounded state. Thread-safe data access; native owner creates and closes the root. No background workers. */
class SavedState private constructor(
    private val store: Store, private val prefix: String, private val parent: SavedState? = null
) : AutoCloseable {
    constructor(platform: SavedStatePlatform, limits: SavedStateLimits = SavedStateLimits(),
        key: String = "appbase.savedState", warning: (String) -> Unit = {}) :
        this(Store(platform, limits, key, warning), "")

    private class Store(platform: SavedStatePlatform, val limits: SavedStateLimits, key: String, val warning: (String) -> Unit) {
        val lock = Any()
        val values = linkedMapOf<String, Any>()
        val providers = linkedMapOf<String, Provider>()
        class Provider(val read: () -> Any)
        val providerSizes = linkedMapOf<String, Long>()
        var saving = false
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
        /** Charge before descending or allocating UTF-8 buffers. Stop at the first exceeded budget. */
        private class Budget(private var remaining: Long) {
            fun charge(n: Long) {
                require(n >= 0 && n <= remaining) { "Saved state size exceeded" }
                remaining -= n
            }
            fun text(value: String) {
                // UTF-8 uses at least one byte per UTF-16 code unit, except surrogate
                // pairs which use four bytes for two units.
                require(value.length.toLong() <= remaining)
                charge(value.toByteArray(Charsets.UTF_8).size.toLong())
            }
            fun left(): Long = remaining
        }
        private fun measure(value: Any, budget: Budget, depth: Int = 0) {
            require(depth <= limits.maxDepth)
            budget.charge(16)
            when (value) {
                is String -> budget.text(value)
                is Boolean, is Int, is Long, is Double -> Unit
                is ByteArray -> budget.charge(value.size.toLong())
                is List<*> -> {
                    require(value.size <= limits.maxEntries)
                    for (item in value) measure(requireNotNull(item), budget, depth + 1)
                }
                is Map<*, *> -> {
                    require(value.size <= limits.maxEntries)
                    for ((k, v) in value) {
                        require(k is String && k.length <= limits.maxKeyBytes &&
                            k.toByteArray(Charsets.UTF_8).size <= limits.maxKeyBytes)
                        budget.charge(16)
                        budget.text(k)
                        measure(requireNotNull(v), budget, depth + 1)
                    }
                }
                else -> error("Unsupported saved state type")
            }
        }
        fun bytes(key: String, value: Any): Long {
            val budget = Budget(limits.maxEntryBytes.toLong())
            budget.charge(32)
            budget.text(key)
            measure(value, budget)
            return limits.maxEntryBytes.toLong() - budget.left()
        }
        fun valid(key: String, value: Any, target: Map<String, Any>): Boolean {
            val n = runCatching { bytes(key, value) }.getOrNull() ?: return false
            if (n > limits.maxEntryBytes || (key !in target && target.size >= limits.maxEntries)) return false
            val scopes = key.indices.filter { key[it] == '/' }.map { key.substring(0, it + 1) }.ifEmpty { listOf("") }
            val other = target.filterKeys { it != key }
            return n + other.entries.sumOf { bytes(it.key, it.value) } <= limits.maxTotalBytes &&
                scopes.all { scope -> n + other.filterKeys { if (scope.isEmpty()) '/' !in it else it.startsWith(scope) }.entries.sumOf { bytes(it.key, it.value) } <= limits.maxScopeBytes }
        }
        fun snapshot(): Map<String, Any> {
            val captured = synchronized(lock) {
                if (closed || saving) return emptyMap()
                saving = true
                providers.toList()
            }
            try {
                val warnings = mutableListOf<String>()
                val output = synchronized(lock) { LinkedHashMap(values) }
                for ((key, provider) in captured) {
                    val value = runCatching {
                        val raw = provider.read()
                        bytes(key, raw)
                        copy(raw).also { bytes(key, it) }
                    }.getOrNull()
                    synchronized(lock) {
                        if (closed) return emptyMap()
                        if (providers[key] !== provider) return@synchronized
                        if (value != null && valid(key, value, output)) {
                            output[key] = value
                            providerSizes[key] = bytes(key, value)
                        } else {
                            output.remove(key)
                            providerSizes.remove(key)
                            warnings.add("savedstate.provider.rejected")
                        }
                    }
                }
                val result = synchronized(lock) {
                    if (closed) return emptyMap()
                    // Static writes and provider removals may have occurred during callbacks.
                    val merged = LinkedHashMap(values)
                    for ((key, provider) in captured) {
                        if (providers[key] !== provider) continue
                        val value = output[key]
                        if (value != null && valid(key, value, merged)) {
                            merged[key] = value
                            providerSizes[key] = bytes(key, value)
                        } else {
                            merged.remove(key)
                            providerSizes.remove(key)
                        }
                    }
                    merged.mapValues { copy(it.value) }
                }
                warnings.forEach { warn(it) }
                return result
            } finally {
                synchronized(lock) { saving = false }
            }
        }
        init {
            runCatching { platform.consume(key) }.onFailure { warn("savedstate.restore.failed") }.getOrNull()?.forEach { (k, v) ->
                val restored = runCatching {
                    require(k.length <= limits.maxKeyBytes && k.toByteArray().size <= limits.maxKeyBytes &&
                        k.split('/').all { it.isNotBlank() })
                    require(valid(k, v, values))
                    copy(v).also { require(valid(k, it, values)) }
                }.getOrNull()
                if (restored != null) values[k] = restored else warn("savedstate.restore.rejected")
            }
        }
        val registration = platform.register(key) { snapshot() }
    }

    val limits get() = store.limits
    private var closed = false
    private fun isOpen(): Boolean = !closed && !store.closed && (parent?.isOpen() ?: true)
    private fun checkOpen() { check(isOpen()) }
    private fun full(key: String): String {
        checkOpen()
        require(key.isNotBlank() && '/' !in key && key.length <= limits.maxKeyBytes)
        return (prefix + key).also { require(it.toByteArray().size <= limits.maxKeyBytes) }
    }
    fun scope(name: String): SavedState = synchronized(store.lock) {
        SavedState(store, full(name) + "/", this)
    }
    fun get(key: String): Any? = synchronized(store.lock) {
        store.values[full(key)]?.let { store.copy(it) }
    }
    fun contains(key: String): Boolean = synchronized(store.lock) {
        full(key).let { it in store.values || it in store.providers }
    }
    fun set(key: String, value: Any): Boolean {
        val k = synchronized(store.lock) { full(key) }
        // Caller-owned collections are inspected outside the shared lock.
        val owned = runCatching {
            store.bytes(k, value)
            store.copy(value).also { store.bytes(k, it) }
        }.getOrNull()
        val accepted = synchronized(store.lock) {
            checkOpen()
            if (owned == null || k in store.providers || !store.valid(k, owned, store.values) ||
                (k !in store.values && (store.values.keys + store.providers.keys).size >= limits.maxEntries)) false
            else { store.values[k] = owned; true }
        }
        if (!accepted) store.warn("savedstate.set.rejected")
        return accepted
    }
    fun remove(key: String): Boolean = synchronized(store.lock) {
        val k = full(key)
        val provider = store.providers.remove(k)
        val value = store.values.remove(k)
        store.providerSizes.remove(k)
        provider != null || value != null
    }
    private fun clearLocked() {
        store.values.keys.removeAll { it.startsWith(prefix) }
        store.providers.keys.removeAll { it.startsWith(prefix) }
        store.providerSizes.keys.removeAll { it.startsWith(prefix) }
    }
    fun clear() = synchronized(store.lock) { checkOpen(); clearLocked() }
    fun register(key: String, provider: () -> Any): AutoCloseable {
        val entry = Store.Provider(provider)
        val k = synchronized(store.lock) {
            val name = full(key)
            require(name !in store.providers)
            require(name in store.values || (store.values.keys + store.providers.keys).size < limits.maxEntries)
            store.providers[name] = entry
            name
        }
        return AutoCloseable {
            synchronized(store.lock) {
                if (store.providers[k] === entry) {
                    store.providers.remove(k)
                    store.providerSizes.remove(k)
                }
            }
        }
    }
    /** Estimated bytes of static values and last accepted provider snapshots. */
    fun size(): Long = synchronized(store.lock) {
        checkOpen()
        store.values.filterKeys { it.startsWith(prefix) && it !in store.providerSizes }
            .entries.sumOf { store.bytes(it.key, it.value) } +
            store.providerSizes.filterKeys { it.startsWith(prefix) }.values.sum()
    }
    override fun close() {
        val dispose = synchronized(store.lock) {
            if (!isOpen()) return
            clearLocked()
            closed = true
            if (prefix.isEmpty()) { store.closed = true; true } else false
        }
        if (dispose) store.registration.close()
    }
}
