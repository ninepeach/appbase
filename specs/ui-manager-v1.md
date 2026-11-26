# UIManager Design Specification (UIManager and UIHost)

Status: core state machine and Android UIHost implemented; integration, hardening and verification pending. Requirements below are target behavior unless explicitly identified as current implementation.
Document standard: [Appbase design document standard](design-document-standard.md). Related: [SavedState](saved-state.md).
Parent specification: [Appbase Architecture](architecture.md).

## 1. Purpose

UIManager is the single public management entry point for pages, modals, overlays and transient notices. Product code chooses routes, arguments and business actions. UIManager determines ordering, input ownership, back handling, state retention and resource cleanup.

Android and iOS implement UIManager independently using native facilities. Public names and behavior should align where practical; no shared runtime or adaptation layer is required.

Keep the implementation small and deterministic. Do not introduce separate public navigation, presentation, input, transition or state managers. Registry, stacks and queues may be ordinary private data structures. UIHost renders native UI; DesignSystem is independent. Ordinary widgets, styling, business logic and local widget state are not managed by UIManager.

## Core placement and dependency rules

UIManager and UIHost are two separate classes in the same Core module and share this specification. Core is the native application infrastructure, not a platform-independent JVM library. Android and iOS implement independently. The optional UI library contains DesignSystem, themes and reusable visual components; applications may use Core without it.

Target Android layout: move UIHost and AndroidSavedState into Core alongside UIManager and SavedState. Convert Core to an Android library with necessary Compose and native dependencies. Preserve UIManager's pure state-machine logic and JVM unit-testability where practical. UIHost depends on UIManager, native UI, SavedState and lifecycle integration; UIManager must not depend on UIHost. Core must not depend on the optional UI library. Communication primitives must not depend on UI components. UIHost must not impose product themes or colors; application supplies theme/content, with native presentation defaults where necessary.

Trade-off: Core gains Android/Compose build dependencies even for headless consumers. This is accepted for the native Appbase application runtime; independent Foundation libraries remain usable without UI. Do not merge classes merely because they share a module.

Migration acceptance: Core builds as Android library, UIHost and AndroidSavedState resolve from Core, no reverse dependency on UI library, app can mount UI without DesignSystem, pure logic tests remain runnable, and native lifecycle/restoration tests pass. This is a design decision; source relocation and build changes are pending.

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

## 9. Design rationale and current implementation

UIManager owns instance identity, ordering, ownership and state lifetime. UIHost owns native rendering, input enforcement and native widget state. SavedState owns bounded navigation persistence; AppLifecycle owns application lifecycle signals. This separation avoids duplicating native facilities or coupling the pure Kotlin state machine to Android. Android Core is an Android library with Compose; UIManager remains JVM-testable using UUID and kotlinx.coroutines (StateFlow, SupervisorJob, CompletableDeferred). UIHost resides in Core and uses Android Activity and Compose. No dedicated UIManager thread or polling loop.

The current manager uses one insertion-ordered instance registry rather than separate public stack managers. Each tab has a protected root; the last PAGE in a tab is its active page. Parent links support recursive removal. Route identity is reusable; UUID instance identity is unique per opening. The snapshot separates live instances, visible IDs, interactive ID and foreground. StateFlow represents authoritative current state, not an event history. Mutations synchronize on the manager monitor; each instance has a child job and result deferred. Close detaches descendants and publishes the new snapshot under the monitor, then cancels jobs and completes results outside it. Cleanup callback failures are isolated but not logged. The native host renders the active page and top blocking dialog, with SaveableStateHolder keyed by instance ID. It removes state for closed IDs and uses native Snackbar duration. Composition disposal is not logical close.

Prompt queue is distinct from modal stack: current prompts belong to the active page, not the current modal. Promotion requires foreground, matching tab and page, no blocker and available capacity. Highest priority wins; equal priority preserves insertion order. Expiry is lazy, checked on promotion/prune or explicit refresh, not by a timer. This is deliberate to avoid a dedicated worker.

## 10. Actual Android public API

The following signatures reflect current source, rather than proposed convenience APIs in section 3.

```kotlin
UIManager(
    routes: Collection<UIRoute>,
    roots: Map<String, String>,
    capacity: Int = 128,
    queueCapacity: Int = 32,
    clock: () -> Long = { System.nanoTime() / 1_000_000 },
    dispatcher: CoroutineDispatcher = Dispatchers.Default
)
```

| API | Current behavior |
|---|---|
| state: StateFlow<UISnapshot> | Current tab, instances, visible IDs, interactive ID, foreground, pending prompts |
| notifications: StateFlow<List<String>> | Pending notice messages |
| selectRoot(tab: String) | Switch tab unless blocked; Unit |
| openPage(route, arguments = emptyMap()) | Push page, return UUID; throws if blocked |
| openModal(route, arguments = emptyMap(), owner: String? = null) | Open child of current modal or page; explicit owner must match |
| showOverlay(route, arguments = emptyMap()) | Open route owned by current modal/page; return UUID |
| showSystem(route, arguments = emptyMap()) | Open system presentation owned by current page |
| toast(route, arguments = emptyMap()) | Creates TOAST instance; currently not rendered or auto-expired by UIHost; prefer notify |
| notify(message: String): Boolean | Admit nonblank notice up to 2048 characters; false on closed/full |
| consumeNotice(message: String) | Host removes matching head message |
| enqueueModal(route, key, priority = 0, ttlMs = 60000, arguments = emptyMap()): String? | Queue or immediately promote; null on duplicate/full |
| cancelQueuedModal(id: String): Boolean | Cancel pending prompt only |
| refresh() | Prune and attempt promotion |
| back(): Boolean | Close top blocker or page; nondismissible consumes; root returns false |
| popModal(): Boolean | Close top dismissible modal; false if absent/nondismissible |
| close(id, result = UIResult.Cancelled): Boolean | Exact instance close and descendant cancellation; stale/root false |
| canGoBack(): Boolean | Whether a blocker or non-root page exists |
| isAlive(id: String): Boolean | Registry membership |
| scope(id: String): CoroutineScope? | Instance-owned coroutine scope |
| result(id: String): Deferred<UIResult>? | Capture before closing; completed result or cancellation value |
| setForeground(value: Boolean) | Update foreground and attempt promotion |
| bindSavedState(savedState, key = "appbase.navigation"): AutoCloseable | Restore then register navigation provider; caller owns registration |
| saveNavigation(): Map<String, String> | Versioned navigation descriptors |
| restoreNavigation(saved: Map<String, String>): Boolean | Validate and restore only on fresh root-only manager |
| close() | Cancel all instances, clear queues and close root job |

Arguments are Map<String, String>. Route definition: name, layer, dismissible (default true), blocking (default true for MODAL/SYSTEM), restorable (default true for PAGE). UIResult is Completed(values: Map<String,String>) or Cancelled. Unknown route, wrong layer, invalid arguments, invalid owner and instance capacity failures throw. Root selection while blocked is a no-op. Proposed replacePage, popToRoot and awaitResult convenience APIs are not implemented.

```kotlin
val ui = UIManager(
    routes = listOf(
        UIRoute("home", UILayer.PAGE),
        UIRoute("settings", UILayer.PAGE),
        UIRoute("editor", UILayer.MODAL),
        UIRoute("confirm", UILayer.MODAL)
    ),
    roots = mapOf("home" to "home", "settings" to "settings")
)
val editor = ui.openModal("editor")
val result = ui.result(editor)
val confirm = ui.openModal("confirm", owner = editor)
ui.back() // closes confirm, editor remains
check(!ui.close(confirm))
ui.close(editor, UIResult.Completed(mapOf("saved" to "true")))
// result?.await() in a coroutine
```

UIHost(manager, renderers: Map<String, @Composable (UIInstance) -> Unit>, lifecycle: AppLifecycle? = null) exposes mount(activity) and composable Content(). Dialog renderers provide content, not another Dialog. Caller owns manager lifetime and SavedState binding.

## 11. Limits, recovery and error handling

Defaults: 128 live instances including roots; 32 pending prompts and separately 32 notices; arguments at most 64 entries, key 128 characters and value 8192 characters; notice 2048 characters; prompt TTL 1–86,400,000 ms, default 60,000 ms. These are character/count bounds, not aggregate memory byte limits. The navigation provider additionally obeys SavedState limits.

Navigation schema v1 records selected tab, count, instance UUID, route, tab and parent. Only restorable PAGE/MODAL entries with retained parents are included. All arguments are currently excluded, not merely sensitive arguments. Restore validates version, UUID uniqueness, known routes/tabs, parent chains and all roots before mutation; invalid input leaves current roots intact. No live jobs, results, prompts, overlays or notices are restored. UIHost uses native SaveableStateHolder for local saveable state; automatic SavedState binding and end-to-end process recreation are not verified.

## 12. Potential problems and solutions

| Problem | Current mitigation and remaining work |
|---|---|
| Stale close or duplicate route | UUID and exact close; capture result before close. Test repeated opens and late callbacks. |
| Parent teardown | Recursive child removal and job cancellation. Test cancellation callbacks and result completion ordering. |
| Reentrant callbacks / lock inversion | Cancellation and deferred completion moved outside the monitor after state commit; StateFlow publication remains under the monitor. Cleanup exceptions are isolated without diagnostics. Test reentry and concurrent close. |
| Covered page overlay leaks | Presentations are filtered by ancestry to the active page; hidden page overlays remain alive and can reappear on return. Modal-ancestor overlay rendering/input isolation remains to be validated. |
| Modal input isolation | Native Dialog provides top blocking surface; reported interactive ID alone does not enforce pointer/focus/accessibility for all custom overlays or background content. Validate host and enforce isolation. |
| Blocking SYSTEM/overlay ordering | openModal rejects non-modal blockers; popModal still targets top modal rather than top blocker. Mixed-layer policy remains incomplete. |
| Prompt expiry and starvation | Lazy expiry and priority queue; no preemption. Refresh on lifecycle/operations; document page-bound ownership and consider fairness only if required. |
| Notice restart and duplicate text | Snackbar effect keys use head text and foreground, and loop over consecutive identical messages. Stable notice IDs and stale-consumption protection remain pending; test background/resume. |
| TOAST route accumulation | Current toast creates an instance but UIHost excludes TOAST; use notify for now; remove redundant API or implement bounded native expiration. |
| SavedState overflow | Flat navigation map uses four fields per instance plus metadata; default 256 collection elements can reject 64 or more instances. Use compact structured descriptors or a smaller explicit restoration limit; report rejection. |
| Missing restore parent field | Current restore treats missing parent as null; require field presence and validate schema strictly. |
| Draft and scroll restoration | SaveableStateHolder keyed by UUID; verify actual close, rapid changes and recreation. No custom form store needed. |
| Native lifecycle | Optional AppLifecycle collection updates foreground; host does not independently bind native lifecycle or lock signals. Integrate native owner and foreground task policy. |
| Rendering failures | Missing renderer throws getValue; validate registry completeness before mount and define error handling. |
| Memory and immutable snapshots | Published instance/prompt/visible/notice collections and argument/result maps are defensive unmodifiable copies. Aggregate payload byte bounds remain pending. |
| Transition and accessibility | Native Dialog/Snackbar used; full page transitions, reduced motion and accessibility acceptance remain unverified. |

## 13. Implementation status and verification

Implemented: core route/instance registry, tab history, modal stack, page-bound prompt queue, precise close, scopes/results, foreground flag, SavedState navigation provider, native UIHost page/dialog/notice rendering. Target requirements in sections 1–8 remain applicable; the gaps above are not completed features. The separate Core test application is outside this document's acceptance claims. No compile, automated concurrency tests or device verification is asserted by this document. Before Stable, execute section 8 acceptance plus mixed-layer, stale-owner, snapshot limit, provider binding, lifecycle and native input tests.

Source locations:
- android/core/src/main/kotlin/appbase/UIManager.kt
- android/core/src/main/kotlin/appbase/UIHost.kt
- android/core/src/main/kotlin/appbase/SavedState.kt
- android/core/src/main/kotlin/appbase/Lifecycle.kt

## Android Core migration status

UIHost and AndroidSavedState source files now reside in android/core/src/main/kotlin/appbase with package appbase. Core build configuration is Android Library with Compose; all Kotlin sources share src/main/kotlin/appbase; standalone smoke tests explicitly exclude UIHost.kt and AndroidSavedState.kt. UIHost.mount(activity, theme) accepts application-supplied composable theme wrapper (default identity). Earlier target-placement statements are superseded by this source migration. No compatibility aliases. Demo migration remains deferred. Build and native verification are pending; source migration does not establish successful compilation.
