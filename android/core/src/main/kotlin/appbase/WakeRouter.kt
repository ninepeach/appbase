package appbase

import kotlinx.coroutines.sync.Mutex

public enum class WakeReason { COLD_START, FOREGROUND, PUSH, NOTIFICATION_TAP, DEEP_LINK, BACKGROUND_TASK }
public data class WakeRequest(val reason: WakeReason, val id: String, val route: String, val expiresAtMs: Long, val payload: String = "")
public data class WakeHandled(val reason: WakeReason, val route: String) : AppEvent
public enum class WakeResult { HANDLED, DUPLICATE, EXPIRED, UNKNOWN_ROUTE, INVALID, BUSY, CLOSED }
/** In-memory deduplication only; handlers must be idempotent across process death. */
public class WakeRouter(private val events: EventBus, private val capacity: Int = 256) : AutoCloseable {
    init { require(capacity in 1..4096) }
    private val mutex = Mutex()
    private val handlers = mutableMapOf<String, suspend (WakeRequest) -> Unit>()
    private val seen = LinkedHashMap<String, Long>()
    @Volatile private var closed = false
    @Synchronized public fun register(route: String, handler: suspend (WakeRequest) -> Unit) {
        check(!closed); require(route.length in 1..128 && route !in handlers && handlers.size < capacity); handlers[route] = handler
    }
    public suspend fun dispatch(request: WakeRequest): WakeResult {
        if (request.id.length !in 1..128 || request.route.length !in 1..128 || request.payload.length > 4096) return WakeResult.INVALID
        if (closed) return WakeResult.CLOSED
        if (!mutex.tryLock()) return WakeResult.BUSY
        try {
            val handler = synchronized(this) {
            if (closed) return WakeResult.CLOSED
            val now = System.currentTimeMillis()
            if (request.expiresAtMs <= now) return WakeResult.EXPIRED
            seen.entries.removeAll { it.value <= now }
            if (request.id in seen) return WakeResult.DUPLICATE
            if (seen.size >= capacity) return WakeResult.BUSY
            handlers[request.route] ?: return WakeResult.UNKNOWN_ROUTE
            }
            handler(request)
            return synchronized(this) {
                if (closed) WakeResult.CLOSED else {
                    seen[request.id] = request.expiresAtMs
                    events.tryEmit(WakeHandled(request.reason, request.route))
                    WakeResult.HANDLED
                }
            }
        } finally { mutex.unlock() }
    }
    @Synchronized override fun close() { closed = true; handlers.clear(); seen.clear() }
}
