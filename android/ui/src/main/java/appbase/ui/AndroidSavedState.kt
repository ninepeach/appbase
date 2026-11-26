package appbase.ui

import android.os.Bundle
import android.os.Parcel
import androidx.savedstate.SavedStateRegistry
import appbase.SavedStatePlatform

/** Main-thread native adapter. No custom persistence. */
class AndroidSavedState(private val registry: SavedStateRegistry,
    private val maxBytes: Int = 131072) : SavedStatePlatform {
    init { require(maxBytes > 0) }
    private fun fits(bundle: Bundle): Boolean {
        val parcel = Parcel.obtain()
        return try { parcel.writeBundle(bundle); parcel.dataSize() <= maxBytes }
        finally { parcel.recycle() }
    }
    override fun consume(key: String): Map<String, Map<String, String>>? =
        registry.consumeRestoredStateForKey(key)?.takeIf { fits(it) }?.let { bundle ->
            bundle.keySet().associateWith { k ->
                val entry = requireNotNull(bundle.getBundle(k))
                entry.keySet().associateWith { requireNotNull(entry.getString(it)) }
            }
        }
    override fun register(key: String, provider: () -> Map<String, Map<String, String>>): AutoCloseable {
        registry.registerSavedStateProvider(key) {
            val result = Bundle()
            provider().forEach { (k, v) ->
                result.putBundle(k, Bundle().apply { v.forEach { (name, value) -> putString(name, value) } })
                if (!fits(result)) result.remove(k)
            }
            result
        }
        return AutoCloseable { registry.unregisterSavedStateProvider(key) }
    }
}
