package appbase

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

interface SavedStatePlatform {
    fun checkThread()
    fun consume(key: String): Map<String, ByteArray>?
    fun register(key: String, provider: () -> Map<String, ByteArray>): AutoCloseable
    fun fits(values: Map<String, ByteArray>): Boolean
}

class SavedState(
    private val platform: SavedStatePlatform,
    key: String = "appbase.savedState",
    private val maxEntryBytes: Int = 32768,
    private val maxTotalBytes: Int = 131072,
    private val warning: (String) -> Unit = {},
    private val json: Json = Json
) : AutoCloseable {
    private val values = linkedMapOf<String, ByteArray>()
    private val claimed = mutableSetOf<String>()
    private var total = 0L
    private var closed = false
    private val registration: AutoCloseable

    init {
        platform.checkThread()
        require(maxEntryBytes in 1..maxTotalBytes)
        val restored = platform.consume(key).orEmpty()
        for ((name, bytes) in restored) {
            if (validKey(name) && !replace(name, bytes)) warn("savedstate.restore.rejected")
            else if (!validKey(name)) warn("savedstate.restore.rejected")
        }
        registration = platform.register(key) { values.mapValues { it.value.copyOf() } }
    }

    private fun warn(code: String) {
        warning(code)
    }

    private fun validKey(key: String): Boolean =
        key.isNotBlank() && key.length <= 128 && key.encodeToByteArray().size <= 128

    private fun checkOpen() {
        platform.checkThread()
        check(!closed)
    }

    private fun cost(key: String, bytes: ByteArray): Long =
        key.encodeToByteArray().size.toLong() + bytes.size

    private fun replace(key: String, bytes: ByteArray): Boolean {
        val previous = values[key]
        val next = total - (previous?.let { cost(key, it) } ?: 0L) + cost(key, bytes)
        if (bytes.size > maxEntryBytes || next > maxTotalBytes) return false
        if (previous == null && values.size >= 64) return false
        val candidate = LinkedHashMap(values)
        candidate[key] = bytes
        if (!platform.fits(candidate)) return false
        values[key] = bytes.copyOf()
        total = next
        return true
    }

    fun <T : Any> slot(key: String, serializer: KSerializer<T>): Slot<T> {
        checkOpen()
        require(validKey(key))
        require(claimed.size < 64 && claimed.add(key))
        return Slot(key, serializer)
    }

    inner class Slot<T : Any> internal constructor(
        private val key: String,
        private val serializer: KSerializer<T>
    ) {
        fun get(): T? {
            checkOpen()
            val bytes = values[key] ?: return null
            return try {
                json.decodeFromString(serializer, bytes.decodeToString(throwOnInvalidSequence = true))
            } catch (e: Exception) {
                warn("savedstate.decode.rejected")
                null
            }
        }

        fun set(value: T): Boolean {
            checkOpen()
            val bytes = try {
                json.encodeToString(serializer, value).encodeToByteArray()
            } catch (e: Exception) {
                warn("savedstate.encode.rejected")
                return false
            }
            if (replace(key, bytes)) return true
            warn("savedstate.size.rejected")
            return false
        }

        fun clear() {
            checkOpen()
            values.remove(key)?.let { total -= cost(key, it) }
        }
    }

    override fun close() {
        platform.checkThread()
        if (closed) return
        registration.close()
        closed = true
        values.clear()
        claimed.clear()
        total = 0
    }
}
