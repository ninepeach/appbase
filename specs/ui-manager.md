# UIManager and UIHost — Design specification

Status: approved target architecture with partial Android source migration. See implementation status below for current APIs and remaining work.

## Implementation status — Android

Partial source migration; compiles in CI, not device-verified. The following distinguishes current source from target contracts.

| Feature | Current status |
|---|---|
| PAGE rendering | UIHost uses NavDisplay with instance-ID string keys derived from UIManager snapshots; no separate mutable stack. |
| Native decorators | SaveableState and ViewModelStore decorators are wired per tab; retention and cleanup are unverified. PAGE no longer uses the presentation SaveableStateHolder. |
| Runtime rejection | Nullable String / Boolean APIs are present for runtime rejection; programming errors remain exceptions. |
| Blocking presentation | UISnapshot.blocker is implemented and UIHost consumes it directly. Back delegates to UIManager; predictive gesture isolation is unverified. |
| Toast | UIManager has duration-based toast API; UIHost renders visible toasts. Runtime behavior unverified. |
| Current page API | openPage(route: String, arguments: Map<String, String> = emptyMap()): String?; UIInstance remains the page model. |
| Typed routes | openPage(NavKey) and PageEntry are not implemented. |
| Navigation restoration | Independent bindSavedState exists, but saveNavigation/restoreNavigation still use Map<String, String>; typed versioned NavSnapshot and polymorphic route serialization are pending. |
| Thread confinement | UIManager still uses synchronized mutations; main-thread-only contract is a target, not established. |
| Build | Core depends on navigation3-runtime/ui 1.2.2 and lifecycle-viewmodel-navigation3 2.10.0 (compileSdk 36, Kotlin 2.2.21, AGP 8.9.2). CI (`android-core.yml`) builds Core, UI and appbaseTest with `assembleDebug` and runs the JVM smoke tests successfully; no emulator or device run yet. |

Source-level implementation does not establish functional acceptance.

## 1. Purpose and boundaries

UIManager is the single public entry point for navigation policy, instance ownership, presentation ordering and resource lifetime. UIHost renders native UI. Android PAGE rendering uses Navigation 3; iOS independently uses native navigation facilities. No cross-platform adapter, separate public NavigationManager, duplicate page stack, compatibility aliases or product styling in Core. DesignSystem remains optional.

## 2. Design principles

Android UIManager owns one authoritative back stack per tab and selected tab. NavDisplay observes entries derived directly from that stack, not a second synchronized stack. Official navigation decorators own page-local saveable state and ViewModel stores. UIManager owns logical instance IDs, coroutine scopes, presentation ownership and prompt scheduling. UIHost owns native rendering and input isolation. All UI mutations occur on the main thread; callers must marshal background callbacks. Native rendering disposal or exit animation is not logical close.

Business routes are serializable NavKey implementations without framework instance fields. Internally PageEntry wraps a generated unique ID and a route key. Construct NavEntry with contentKey = entry.id; duplicate business keys remain independent. Retain IDs across restoration. The concrete serialization and NavEntry API must be proven against the chosen stable dependency version before implementation.

## 3. Target API and data model

```kotlin
@Serializable
data class ServerDetail(val serverId: String) : NavKey

internal data class PageEntry(val id: String, val key: NavKey)

// Proposed business call; UIManager generates ID.
val id = ui.openPage(ServerDetail("tokyo-01"))
```

openPage(key: NavKey): String?; selectRoot(tab: String): Boolean; back(): Boolean; close(id: String): Boolean; scope(id: String): CoroutineScope?. Existing non-page presentation operations retain their ownership and queue semantics; their concrete signatures are subject to implementation review. Programming errors (unknown routes, wrong layer, invalid owners, duplicate IDs, oversized arguments) throw. Runtime rejections (manager closed, a blocking presentation owns input, instance capacity full) return null or false and never throw, so late callbacks cannot crash the app. Stale close returns false. Roots cannot be ordinarily popped. No framework ID in product route classes. No public mutable back stack.

## 4. Presentation and ownership

Retain OVERLAY, MODAL, TOAST and SYSTEM layers. Modal parent/child stack is separate from independent prompt queue. Queue is bounded, priority ordered with FIFO ties, deduplicated and expiry checked; unrelated prompts do not interrupt a modal chain. Page-owned presentations and scopes close when their PageEntry is removed; nested modal closure cascades. Covered or inactive tabs retain live page state and owned resources according to foreground policy. Nonblocking notices do not steal input. TOAST instances close automatically after their duration (default 4 s, max 60 s) through a delay in their own instance scope; at most `toastCapacity` (default 3) are live and the oldest is closed to admit a new one, so toasts can never exhaust instance capacity. `UISnapshot.blocker` is the single source of the blocking presentation that owns input and back; UIHost renders from it and must not recompute layer priority. Nondismissible blockers consume back. Use native dialog, snackbar and accessibility/focus facilities. No polling thread.

### 4.1 Ownership and visibility

Every modal, overlay and system presentation has an immutable parent instance ID, resolving through a live parent chain to its owning page. Ordinary modal and overlay openings attach to the current modal or current page; system presentations may attach to the current page or modal according to their policy. No orphan or cross-tab parent is allowed. Only presentations whose owning page is the selected tab's top page are eligible for display. Covering a page or switching tabs hides its presentations without destroying their logical instances, drafts, results or scopes. Returning to that page restores eligible presentations. Visibility, input ownership, foreground and logical lifetime are distinct. Only the highest eligible blocker owns input; hidden descendants of a covered modal must not receive input. Page removal cascades to all owned presentations.

### 4.2 Independent prompt queue

`enqueueModal(route, key, priority = 0, ttlMs = 60_000, arguments = emptyMap()): String?` is the existing admission contract; typed presentation arguments may evolve independently of PAGE NavKey. A prompt belongs to the current page, not the current modal. Deduplicate by key across queued and promoted live prompts, reject admission when full, and use a monotonic clock for TTL. Select highest priority among eligible prompts, FIFO for ties. Promote only while foreground, owner is selected tab's top page, no blocking presentation is active, and instance capacity is available. Recheck on enqueue, close, navigation, foreground transition and explicit refresh; expired prompts cannot promote. Closing an owner discards its queued prompts. Promotion preserves request ID; closing the promoted instance releases its deduplication key. No dedicated polling thread. Queue acceptance is not proof of display or completion.

### 4.3 Close, result and resource lifetime

`close(id, result = UIResult.Cancelled): Boolean` closes the exact live non-root instance and all descendants, never an unrelated instance. Stale IDs and protected roots return false. Descendants complete with Cancelled; the explicitly closed target receives the supplied result (Cancelled by default). Remove ownership records, pending prompts and deduplication records atomically before cancelling jobs or completing Deferreds, so callbacks cannot observe a half-closed tree. Each live instance exposes `scope(id): CoroutineScope?` and `result(id): Deferred<UIResult>?`. Capture the Deferred before closing; after removal lookup returns null. Scope cancellation is cooperative and does not imply synchronous termination. Logical close is independent of animation or composition disposal.

```kotlin
val id = ui.openModal("confirm") ?: return // rejected: blocked or full
val pending = requireNotNull(ui.result(id))
val result = pending.await()
```

A caller awaiting a modal result must use a scope that outlives the modal itself. Cancellation of the awaiting caller does not by itself close the modal. Back consumes nondismissible blockers without removing them; at an unblocked root it returns false. A successful committed back closes exactly one target and its descendants. Repeated or delayed close requests cannot affect a newly opened instance.

## 5. Back and predictive back

NavDisplay onBack delegates to UIManager.back (adapt return type if necessary). UIManager handles eligible SYSTEM, MODAL, blocking OVERLAY, then PAGE in that order; no double consumption. UIHost must register higher-priority native back handling for blocking presentations and suppress underlying page predictive preview while blocked. A cancelled gesture changes no stack, results or scopes; a committed gesture closes exactly one eligible instance. Page pop updates the authoritative stack and cancels owned work immediately, independently of exit animation. Test gesture cancellation and blocker changes during a gesture.

## 6. State and restoration

Keep explicit navigation save/restore actions: UIManager owns selected tab and all PageEntry lists, so register an aggregate SavedStateRegistry provider and restore before mounting. Use Kotlin serialization with explicit polymorphic route serializers; do not retain existing flat field schema. A small versioned envelope, byte/count limits, route/ID/root validation and safe fallback remain necessary. Never persist callbacks, jobs, Deferreds or transient prompts/modals by default. Recreate scopes for restored IDs. Page-local rememberSaveable and ViewModelStore ownership use official decorators keyed by instance ID; only explicitly saved ViewModel data survives process death. Each tab must retain independent decorator ownership even when not displayed; prove this using the official multiple-back-stack recipe. Generic SavedState remains available for non-navigation state. No second page SaveableStateHolder.

## 7. Dependencies and implementation plan

Target stable Navigation 3 runtime/UI, lifecycle ViewModel navigation decorator and Kotlin serialization. Verify exact versions, compileSdk, AGP, Kotlin, Compose and minSdk compatibility through Gradle; do not assume dependency resolution. PAGE rendering has already been changed at source level. Dependencies are resolved and the integration compiles; next, complete typed PAGE entries and replace the flat navigation schema, retaining presentation logic. Do not introduce a second navigation engine or change iOS implementation to depend on Android.

## 8. Potential problems and mitigations

| Risk | Required mitigation / status |
|---|---|
| Duplicate routes share state | PageEntry ID and NavEntry contentKey; proof pending |
| Inactive tab decorator clears state | Independent tab decorators following official recipe; proof pending |
| Polymorphic route restore fails | Explicit serializers, version/limits/validation and root fallback; proof pending |
| Modal triggers page predictive preview | Higher-priority native handler and preview suppression; device test pending |
| Gesture cancellation releases resources | Mutate only on committed back; device test pending |
| Page pop leaks modal or scope | Exact owner ID and immediate cascade; regression test pending |
| UI state race | Main-thread mutations and immutable external snapshots; tests pending |
| Oversized saved state | Bounded serialized payload, safe rejection and diagnostic code; tests pending |
| Native animation retains removed page | Logical close independent of animation; tests pending |

## 9. Acceptance and source locations

Before declaring migration complete, prove duplicate-key isolation, all-tab draft/scroll/ViewModel retention, pop disposal, Activity recreation, actual process-death restore of IDs and saved data, predictive back cancellation/commit, modal interception and owner cascade. Run JVM tests, Android assembleDebug and emulator/device tests. Additionally verify hidden presentation retention across page cover/tab switch, nested ownership cascade, stale/root close rejection, prompt priority/FIFO/dedup/TTL/foreground/owner eligibility, owner removal dropping queued prompts, exact-once Deferred completion and cooperative scope cancellation. Record actual results; no verification is claimed here.

Implementation locations: android/core/src/main/kotlin/appbase/UIManager.kt, UIHost.kt, SavedState.kt, AndroidSavedState.kt. Source migration is partial; see implementation status above.
