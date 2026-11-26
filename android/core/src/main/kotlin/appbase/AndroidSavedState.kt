package appbase

import android.os.Bundle
import android.os.Looper
import android.os.Parcel
import androidx.savedstate.SavedStateRegistry

class AndroidSavedState(
    private val registry: SavedStateRegistry,
    private val maxBytes: Int = 131072
) : SavedStatePlatform {
    init {
        require(maxBytes > 0)
    }

    override fun checkThread() {
        check(Looper.myLooper() == Looper.getMainLooper())
    }

    private fun bundle(values: Map<String, ByteArray>): Bundle =
        Bundle().apply {
            values.forEach { (key, value) -> putByteArray(key, value) }
        }

    override fun fits(values: Map<String, ByteArray>): Boolean {
        checkThread()
        val parcel = Parcel.obtain()
        return try {
            parcel.writeBundle(bundle(values))
            parcel.dataSize() <= maxBytes
        } finally {
            parcel.recycle()
        }
    }

    override fun consume(key: String): Map<String, ByteArray>? {
        checkThread()
        val restored = registry.consumeRestoredStateForKey(key) ?: return null
        // Skip entries that are not byte arrays (for example an older saved format) instead of
        // failing the whole restore.
        val result = linkedMapOf<String, ByteArray>()
        for (name in restored.keySet()) {
            val bytes = runCatching { restored.getByteArray(name) }.getOrNull() ?: continue
            result[name] = bytes
        }
        return result
    }

    override fun register(key: String, provider: () -> Map<String, ByteArray>): AutoCloseable {
        checkThread()
        registry.registerSavedStateProvider(key) { bundle(provider()) }
        var closed = false
        return AutoCloseable {
            checkThread()
            if (!closed) {
                registry.unregisterSavedStateProvider(key)
                closed = true
            }
        }
    }
}
