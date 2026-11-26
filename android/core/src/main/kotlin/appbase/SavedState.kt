package appbase

/** Small transient state only. Platform owns persistence and restoration timing.
 * Providers are synchronous, bounded and must not perform I/O.
 */
interface SavedState {
    fun consume(key: String): Map<String, String>?
    fun register(key: String, provider: () -> Map<String, String>): AutoCloseable
}
