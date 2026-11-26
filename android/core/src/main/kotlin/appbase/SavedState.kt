package appbase

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

public interface SavedStatePlatform {
    public fun checkThread()
    public fun consume(key: String): Map<String, ByteArray>?
    public fun register(key: String, provider: () -> Map<String, ByteArray>): AutoCloseable
    public fun fits(values: Map<String, ByteArray>): Boolean
}

/**
 * Small typed UI state that survives native owner recreation. Main thread only.
 *
 * Each key is owned by one live [Slot]. Closing a slot releases the key and drops its value, so
 * per-instance slots (for example `"draft.$instanceId"`) can be opened and closed indefinitely.
 * Restored values whose key is never claimed again are kept for one save only, so renamed or
 * removed slots cannot hold budget forever.
 */
public class SavedState(
    private val platform: SavedStatePlatform,
    key: String = "appbase.savedState",
    private val maxEntryBytes: Int = 32768,
    private val maxTotalBytes: Int = 131072,
    private val maxEntries: Int = 64,
    private val warning: (String) -> Unit = {},
    private val json: Json = Json
) : AutoCloseable {
    private val values = linkedMapOf<String, ByteArray>()
    private val claimed = mutableSetOf<String>()
    private val unclaimedRestored = mutableSetOf<String>()
    private var total = 0L
    private var closed = false
    private val registration: AutoCloseable

    init {
        platform.checkThread()
        require(maxEntryBytes in 1..maxTotalBytes && maxEntries > 0)
        val restored = runCatching { platform.consume(key) }
            .onFailure { warn("savedstate.restore.failed") }
            .getOrNull()
            .orEmpty()
        for ((name, bytes) in restored) {
            if (validKey(name) && replace(name, bytes)) unclaimedRestored.add(name)
            else warn("savedstate.restore.rejected")
        }
        registration = platform.register(key) { snapshot() }
    }

    private fun warn(code: String) {
        runCatching { warning(code) }
    }

    private fun validKey(key: String): Boolean =
        key.isNotBlank() && key.length <= 128 && key.encodeToByteArray().size <= 128

    private fun checkOpen() {
        platform.checkThread()
        check(!closed)
    }

    private fun cost(key: String, bytes: ByteArray): Long =
        key.encodeToByteArray().size.toLong() + bytes.size

    /** Called by the native owner when saving. Unclaimed restored values get exactly one save. */
    private fun snapshot(): Map<String, ByteArray> {
        if (closed) return emptyMap()
        val output = values.mapValues { it.value.copyOf() }
        unclaimedRestored.toList().forEach { remove(it) }
        return output
    }

    private fun remove(key: String) {
        values.remove(key)?.let { total -= cost(key, it) }
        unclaimedRestored.remove(key)
    }

    private fun replace(key: String, bytes: ByteArray): Boolean {
        val previous = values[key]
        if (previous != null && previous.contentEquals(bytes)) return true
        val next = total - (previous?.let { cost(key, it) } ?: 0L) + cost(key, bytes)
        if (bytes.size > maxEntryBytes || next > maxTotalBytes) return false
        if (previous == null && values.size >= maxEntries) return false
        val candidate = LinkedHashMap(values)
        candidate[key] = bytes
        if (!platform.fits(candidate)) return false
        values[key] = bytes.copyOf()
        total = next
        return true
    }

    /**
     * Claims [key]. Returns null if the key is already owned by a live slot or the slot limit is
     * reached; an invalid key is a programming error and throws.
     */
    public fun <T : Any> slot(key: String, serializer: KSerializer<T>): Slot<T>? {
        checkOpen()
        require(validKey(key)) { "Invalid saved state key" }
        if (key in claimed || claimed.size >= maxEntries) {
            warn("savedstate.slot.rejected")
            return null
        }
        claimed.add(key)
        unclaimedRestored.remove(key)
        return Slot(key, serializer)
    }

    public inner class Slot<T : Any> internal constructor(
        private val key: String,
        private val serializer: KSerializer<T>
    ) : AutoCloseable {
        private var released = false

        private fun checkLive() {
            checkOpen()
            check(!released) { "Slot is closed" }
        }

        public fun get(): T? {
            checkLive()
            val bytes = values[key] ?: return null
            return try {
                json.decodeFromString(serializer, bytes.decodeToString(throwOnInvalidSequence = true))
            } catch (e: Exception) {
                // Incompatible or corrupt value (for example after a schema change): drop it.
                remove(key)
                warn("savedstate.decode.rejected")
                null
            }
        }

        public fun set(value: T): Boolean {
            checkLive()
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

        public fun clear() {
            checkLive()
            remove(key)
        }

        /** Releases the key and drops the value. Call when the owner is logically closed. */
        override fun close() {
            platform.checkThread()
            if (released || closed) {
                released = true
                return
            }
            released = true
            remove(key)
            claimed.remove(key)
        }
    }

    override fun close() {
        platform.checkThread()
        if (closed) return
        registration.close()
        closed = true
        values.clear()
        claimed.clear()
        unclaimedRestored.clear()
        total = 0
    }
}
