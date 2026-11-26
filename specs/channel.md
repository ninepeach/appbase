# Channel

## Design principles
Typed ChannelCommand<T> represents cold streaming request, not broadcast or persistent queue. Reuse Flow and structured cancellation. CommandService owns registration and stream admission; no extra manager.

## API
registerChannel(type: KClass<C>, id: String, handler: (C) -> Flow<T>); CommandClient.stream(command: ChannelCommand<T>): Flow<T>. Registration before start; capability checked at collection.

Example: client.stream(query).collect { item -> render(item) }.

## Problems and solutions
Each collector starts independent stream. Semaphore bounds concurrent streams to workers; excess rejected with QueueFull. Producer runs on Default with rendezvous buffer(0); upstream handlers can still allocate buffers. Cancellation and errors propagate; service scope guard cancels collectors on shutdown. Handler invocation deferred into producer context. No stream timeout or idle deadline currently; caller may use withTimeout. Blocking I/O must use suitable dispatcher. Shutdown race and guard behavior require tests.

## Acceptance
Test admission, permission, cold collection, slow consumer, cancellation, producer failure, shutdown race and slot release. Source modified; tests/build not verified.
