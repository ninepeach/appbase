package appbase

import kotlinx.coroutines.flow.*

/** Keys are identity tokens. Values must be immutable or caller-owned snapshots. */
public class StateStore(private val capacity: Int = 256) {
    init { require(capacity in 1..4096) }
    private val values = mutableMapOf<StateKey<*>, MutableStateFlow<*>>()
    @Synchronized public fun <T> declare(key: StateKey<T>, initial: T) {
        require(key.name.isNotBlank() && key.name.length <= 128)
        check(key !in values && values.size < capacity)
        values[key] = MutableStateFlow(initial)
    }
    @Suppress("UNCHECKED_CAST")
    @Synchronized private fun <T> get(key: StateKey<T>): MutableStateFlow<T> =
        (values[key] ?: error("Unknown state ${key.name}")) as MutableStateFlow<T>
    public fun <T> observe(key: StateKey<T>): StateFlow<T> = get(key).asStateFlow()
    public fun <T> set(key: StateKey<T>, value: T) { get(key).value = value }
    /** Atomic CAS update; transform may run repeatedly and must be side-effect free. */
    public fun <T> update(key: StateKey<T>, transform: (T) -> T) { get(key).update(transform) }
    @Synchronized public fun size(): Int = values.size
}
