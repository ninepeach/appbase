package appbase.ui

import android.os.Bundle
import androidx.savedstate.SavedStateRegistry
import appbase.SavedState

/** Main-thread adapter; consume after the owner's onCreate restoration. */
class AndroidSavedState(private val registry: SavedStateRegistry) : SavedState {
    override fun consume(key: String): Map<String, String>? =
        registry.consumeRestoredStateForKey(key)?.let { bundle ->
            bundle.keySet().associateWith { bundle.getString(it) ?: error("Invalid saved state") }
        }

    override fun register(key: String, provider: () -> Map<String, String>): AutoCloseable {
        registry.registerSavedStateProvider(key) {
            Bundle().apply { provider().forEach { (k, v) -> putString(k, v) } }
        }
        return AutoCloseable { registry.unregisterSavedStateProvider(key) }
    }
}
