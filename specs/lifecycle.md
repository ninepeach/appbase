# Lifecycle

## Design principles
AppLifecycle owns latest AppPhase and foreground child scope. Native owner serializes callbacks. INACTIVE, FOREGROUND, BACKGROUND, CLOSED. StateFlow authoritative; EventBus notification best effort.

## API
AppLifecycle(parent: CoroutineScope, events: EventBus? = null); phase: StateFlow<AppPhase>; transition(next); foregroundScope(): CoroutineScope?; close().

Example: lifecycle.transition(AppPhase.FOREGROUND); lifecycle.foregroundScope()?.launch { refresh() }.

## Problems and solutions
Duplicate phase is no-op. CLOSED terminal. New state is published before old scope cancellation, which runs outside monitor. Cancellation is cooperative; old work may briefly overlap new scope. Native Activity/process binding and parent cancellation reconciliation remain pending. Event notification can be dropped. Do not treat lock screen as process death.

## Acceptance
Test transitions, duplicate callbacks, scope cancellation, close idempotence, reentry and concurrent close; device background/foreground and recreation. Source modified; tests/build not verified.
