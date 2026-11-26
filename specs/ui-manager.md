# UIManager Design Specification

Status: target specification. Implementation and test readiness are tracked separately.
Parent specification: [Appbase Architecture](architecture.md).

## 1. Purpose

UIManager is the single public management entry point for pages, modals, overlays and transient notices. Product code chooses routes, arguments and business actions. UIManager determines ordering, input ownership, back handling, state retention and resource cleanup.

Android and iOS implement UIManager independently using native facilities. Public names and behavior should align where practical; no shared runtime or adaptation layer is required.

Keep the implementation small and deterministic. Do not introduce separate public navigation, presentation, input, transition or state managers. Registry, stacks and queues may be ordinary private data structures. UIHost renders native UI; DesignSystem is independent. Ordinary widgets, styling, business logic and local widget state are not managed by UIManager.

## 2. Managed UI

| Type | Behavior |
|---|---|
| Page | Independent stack per root tab; switching retains history |
| Modal | LIFO stack; only top blocking presentation receives input |
| Queued modal | Independent prompt queue; waits for current modal flow to finish |
| Overlay | Instance-owned presentation; optionally blocks input |
| Toast / Snackbar | Bounded transient notice queue, timeout, no navigation history or input ownership |

A route defines its type, argument contract, renderer and default presentation policy. Register routes explicitly at setup. Every opening creates a unique instance ID, including repeated openings of the same route. Each instance owns its arguments, draft state and work. Owner and navigation parent are distinct concepts when needed. Roots are protected from ordinary close operations.

## 3. Public API

Conceptual signatures; native language typing and asynchronous conventions apply.

| Operation | Contract |
|---|---|
| selectRoot(root) | Select root and restore its current page; blocked while a modal or blocking overlay owns input |
| openPage(route, args) | Push page on selected root; return new instance ID |
| openModal(route, args, owner) | Push modal owned by current page or top modal; return new instance ID |
| enqueueModal(route, args, options) | Admit independent prompt; return request ID or explicit rejection |
| back() | Consume top blocking presentation first, then page stack; return whether consumed |
| popModal() | Close only top dismissible modal; false if absent or not dismissible |
| close(id) | Precisely close instance and descendants; false for stale ID or protected root |
| showOverlay(message, options) | Return overlay instance ID; close the same ID on completion |
| notify(message) | Admit transient notice; return admission success |

Useful auxiliary operations: replacePage, popToRoot, cancelQueuedModal, isAlive, awaitResult. Route registration and host attachment are setup operations. Snapshot/restore and input ownership are internal or diagnostic interfaces rather than routine business APIs.

Do not expose mutable stacks or queues. Explicitly reject invalid routes, arguments, owners and capacity overflow. A nondismissible blocking presentation consumes back without closing; programmatic close remains possible. Late callbacks must use exact IDs, never pop the current top as a substitute. Modal results distinguish completion from cancellation; waiters must be resolved on close and owner teardown.

## 4. Ordering and ownership

Maintain one authoritative navigation state. Serialize mutations and publish consistent snapshots. Page stacks are independent; presentations are associated with their owner and root. Child modals must not outlive their owner. Closing a parent removes descendants in top-down order and cancels owned work. Closing a non-top page also removes dependent pages and presentations.

Queued prompts are not child modals. Default FIFO; optional priority, deduplication key and expiry are supported without interrupting active modal chains. Preserve FIFO among equal priorities. Promote only when the current blocking presentation flow has ended and the prompt is eligible. Bound pending and active entries, reject overflow, discard expired or cancelled requests and clean up owner-bound requests when their owner closes. Independent application prompts must not accidentally inherit a transient modal owner. Deduplication covers active and pending prompts when a key is supplied.

Blocking overlays participate in input and back policy. Nonblocking overlays and notices do not steal focus. Use native notification durations and cancel notices when their host is permanently destroyed. No arbitrary custom layer registration in the initial implementation.

## 5. Instance lifecycle and input

Open creates an instance and its owned scope. Covered preserves the instance and draft but removes input ownership. Resume restores the same instance and state. Close removes it, cancels its work and subscriptions, resolves pending results and clears its draft.

Alive, visible, interactive and app foreground are distinct. Full-screen pages can hide lower pages; partial modals can leave lower content visible but not interactive. Native host must enforce pointer, keyboard, focus, accessibility and back isolation, not merely report an input owner. Composition disposal is not logical close.

App background, lock and host recreation preserve logical stacks while pausing foreground-only work. Observe native lifecycle signals and reconcile on resume; document unavailable signals. Logical close is immediate even if native exit animation continues; outgoing content cannot accept input. Use native animation and reduced-motion facilities. No dedicated polling or thread pool.

## 6. State and recovery

Key state by instance ID, not route or stack depth. Preserve drafts and scroll position while covered or switching roots. Actual close discards state. Reopening a route creates a fresh ID and state.

Restore selected root, eligible page stacks, instance identities, arguments and bounded serializable form/scroll state after native host recreation. Validate restored routes, ownership, schema and limits; fall back safely on incompatible data. Never serialize callbacks, coroutine jobs, live resources or sensitive drafts by default. Transient confirmation callbacks, notices and progress overlays are not restored. A restorable modal must be explicitly described by route and data and rebind its actions on reconstruction.

Use native saved-state mechanisms. Do not continuously render all historical pages or retain unlimited drafts. Define and test limits for instance count, queue length, argument size and aggregate saved-state size. Do not claim process-death recovery for live work.

## 7. Implementation structure

- UIManager: single public facade and authoritative state machine.
- UIInstance / route definitions: identity, ownership, arguments and policies.
- Private registry, page/modal stacks, prompt/notice queues and state storage: simple bounded collections unless complexity warrants extraction.
- UIHost: native rendering, input isolation, lifecycle integration, saved state and transitions.

DesignSystem is separate. Reuse native coroutine/task scopes, observable state, UI components and animation facilities. Diagnostics integrate with Appbase Inspector rather than a separate UI diagnostics subsystem. Record safe route/instance metadata and capacity failures without exposing sensitive arguments. Avoid global recomposition, unbounded caches, dynamic registration, custom lifecycle policy engines and speculative abstractions.

## 8. Acceptance

The primary scenario: A opens B; B enters a draft and opens C. Only C accepts input. Independent prompt D waits. Back closes C and restores B with its original draft. A late close(C.id) returns false and leaves B untouched. Closing B cancels its work and clears its draft; D can now display. Reopening B creates a fresh ID and draft.

Additional tests:
- Independent root histories and state survive switching and supported Android recreation.
- Duplicate route instances have independent drafts and IDs.
- Nondismissible modal consumes back; precise close and cascade are idempotent.
- Queue FIFO, optional priority, active/pending deduplication, expiry, cancellation and overflow.
- Overlapping overlays close by exact ID; nonblocking notices do not own input.
- Rapid back/open/close, invalid IDs, stale callbacks, owner teardown and result cancellation.
- Background/resume, native input/focus isolation, accessibility and reduced motion.
- Bounded saved state, invalid restoration and sensitive-state exclusion.
- Demo uses the same authoritative UIManager; no parallel navigation implementation.

Validate the pure state machine with automated tests and the native host with device or emulator tests. Measure startup, memory and recomposition before making performance claims. This document specifies required behavior, not current implementation completeness.
