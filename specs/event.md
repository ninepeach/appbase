# Event

## Design principles
Ephemeral typed in-process broadcast using SharedFlow, not durable messaging. No replay, worker or custom dispatcher. Capacity defaults to 64, range 1–4096. All event types share the buffer.

## API
EventBus(capacity = 64); suspend emit(event: AppEvent); tryEmit(event): Boolean; observe(type: KClass<T>): Flow<T>; subscriberCount: StateFlow<Int>.

Example: app.event.observe(LifecycleChanged::class).collect { handle(it) } in an owner scope.

## Problems and solutions
Slow subscribers suspend emit; tryEmit returns false when full. With no subscribers, both succeed but events are discarded. Subscription cancellation belongs to the caller scope. Collector failures belong to that collector. Successful tryEmit indicates flow acceptance, not subscriber acknowledgement. Payload size and subscriber count are not bounded by the bus; use small immutable events and scoped subscriptions. Do not use for authoritative state.

## Acceptance
Test no replay, type filtering, full buffer, cancellation, concurrent producers and no-subscriber semantics. Source implemented; tests/build not verified.
