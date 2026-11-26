package appbase

import kotlinx.coroutines.flow.*
import kotlin.reflect.KClass

/** Ephemeral broadcast; no replay. Slow subscribers exert backpressure. */
class EventBus(capacity: Int = 64) {
    init { require(capacity in 1..4096) }
    private val bus = MutableSharedFlow<AppEvent>(extraBufferCapacity = capacity)
    suspend fun emit(event: AppEvent) = bus.emit(event)
    fun tryEmit(event: AppEvent): Boolean = bus.tryEmit(event)
    val subscriberCount: StateFlow<Int> = bus.subscriptionCount
    fun <T : AppEvent> observe(type: KClass<T>): Flow<T> =
        bus.filter { type.isInstance(it) }.map { @Suppress("UNCHECKED_CAST") (it as T) }
}
