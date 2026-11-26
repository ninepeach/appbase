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
    private fun encode(value: Any): Bundle = Bundle().apply {
        when (value) {
            is String -> { putString("t", "s"); putString("v", value) }
            is Boolean -> { putString("t", "b"); putBoolean("v", value) }
            is Int -> { putString("t", "i"); putInt("v", value) }
            is Long -> { putString("t", "l"); putLong("v", value) }
            is Double -> { putString("t", "d"); putDouble("v", value) }
            is ByteArray -> { putString("t", "a"); putByteArray("v", value) }
            is List<*> -> { putString("t", "list"); putInt("n", value.size); value.forEachIndexed { i, v -> putBundle(i.toString(), encode(requireNotNull(v))) } }
            is Map<*, *> -> { putString("t", "map"); putBundle("v", Bundle().apply { value.forEach { (k,v) -> putBundle(k as String, encode(requireNotNull(v))) } }) }
            else -> error("Unsupported value")
        }
    }
    private fun decode(b: Bundle, depth: Int): Any {
        require(depth <= 32)
        return when (b.getString("t")) {
            "s" -> requireNotNull(b.getString("v"))
            "b" -> b.getBoolean("v")
            "i" -> b.getInt("v")
            "l" -> b.getLong("v")
            "d" -> b.getDouble("v")
            "a" -> requireNotNull(b.getByteArray("v"))
            "list" -> List(b.getInt("n").also { require(it in 0..4096) }) { decode(requireNotNull(b.getBundle(it.toString())), depth + 1) }
            "map" -> requireNotNull(b.getBundle("v")).let { m -> m.keySet().associateWith { decode(requireNotNull(m.getBundle(it)), depth + 1) } }
            else -> error("Invalid saved state type")
        }
    }
    override fun consume(key: String): Map<String, Any>? =
        registry.consumeRestoredStateForKey(key)?.takeIf { fits(it) }?.let { bundle ->
            bundle.keySet().associateWith { k ->
                decode(requireNotNull(bundle.getBundle(k)), 0)
            }
        }
    override fun register(key: String, provider: () -> Map<String, Any>): AutoCloseable {
        registry.registerSavedStateProvider(key) {
            val result = Bundle()
            provider().forEach { (k, v) ->
                result.putBundle(k, encode(v))
                if (!fits(result)) result.remove(k)
            }
            result
        }
        return AutoCloseable { registry.unregisterSavedStateProvider(key) }
    }
}
