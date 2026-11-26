package appbase

import kotlinx.coroutines.flow.*
import kotlin.reflect.KClass

/** Ephemeral broadcast; no replay. Slow subscribers exert backpressure.
 * With no subscribers, emissions are discarded. Collection lifetime belongs to the caller's scope.
 * A successful tryEmit means accepted by the flow, not acknowledged by a subscriber.
 */
public class EventBus(capacity: Int = 64) {
    init { require(capacity in 1..4096) }
    private val bus = MutableSharedFlow<AppEvent>(extraBufferCapacity = capacity)
    public suspend fun emit(event: AppEvent): Unit = bus.emit(event)
    public fun tryEmit(event: AppEvent): Boolean = bus.tryEmit(event)
    public val subscriberCount: StateFlow<Int> = bus.subscriptionCount
    public fun <T : AppEvent> observe(type: KClass<T>): Flow<T> =
        bus.filter { type.isInstance(it) }.map { @Suppress("UNCHECKED_CAST") (it as T) }
}
