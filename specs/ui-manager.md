# UIManager and UIHost — Design specification

Status: approved target architecture; implementation and native proof of concept pending. Existing source has not yet migrated to this design; proposed signatures are not implemented.

## 1. Purpose and boundaries

UIManager is the single public entry point for navigation policy, instance ownership, presentation ordering and resource lifetime. UIHost renders native UI. Android PAGE rendering uses Navigation 3; iOS independently uses native navigation facilities. No cross-platform adapter, separate public NavigationManager, duplicate page stack, compatibility aliases or product styling in Core. DesignSystem remains optional.

## 2. Design principles

Android UIManager owns one authoritative back stack per tab and selected tab. NavDisplay observes entries derived directly from that stack, not a second synchronized stack. Official navigation decorators own page-local saveable state and ViewModel stores. UIManager owns logical instance IDs, coroutine scopes, presentation ownership and prompt scheduling. UIHost owns native rendering and input isolation. All UI mutations occur on the main thread; callers must marshal background callbacks. Native rendering disposal or exit animation is not logical close.

Business routes are serializable NavKey implementations without framework instance fields. Internally PageEntry wraps a generated unique ID and a route key. Construct NavEntry with contentKey = entry.id; duplicate business keys remain independent. Retain IDs across restoration. The concrete serialization and NavEntry API must be proven against the chosen stable dependency version before implementation.

## 3. Proposed API and data model

```kotlin
@Serializable
data class ServerDetail(val serverId: String) : NavKey

internal data class PageEntry(val id: String, val key: NavKey)

// Proposed business call; UIManager generates ID.
val id = ui.openPage(ServerDetail("tokyo-01"))
```

openPage(key: NavKey): String; selectRoot(tab: String); back(): Boolean; close(id: String): Boolean; scope(id: String): CoroutineScope?. Existing non-page presentation operations retain their ownership and queue semantics; their concrete signatures are subject to implementation review. Unknown routes, invalid owners, duplicate IDs and capacity violations must be explicit failures. Stale close returns false. Roots cannot be ordinarily popped. No framework ID in product route classes. No public mutable back stack.

## 4. Presentation and ownership

Retain OVERLAY, MODAL, TOAST and SYSTEM layers. Modal parent/child stack is separate from independent prompt queue. Queue is bounded, priority ordered with FIFO ties, deduplicated and expiry checked; unrelated prompts do not interrupt a modal chain. Page-owned presentations and scopes close when their PageEntry is removed; nested modal closure cascades. Covered or inactive tabs retain live page state and owned resources according to foreground policy. Nonblocking notices do not steal input. Nondismissible blockers consume back. Use native dialog, snackbar and accessibility/focus facilities. No polling thread.

## 5. Back and predictive back

NavDisplay onBack delegates to UIManager.back (adapt return type if necessary). UIManager handles eligible SYSTEM, MODAL, blocking OVERLAY, then PAGE in that order; no double consumption. UIHost must register higher-priority native back handling for blocking presentations and suppress underlying page predictive preview while blocked. A cancelled gesture changes no stack, results or scopes; a committed gesture closes exactly one eligible instance. Page pop updates the authoritative stack and cancels owned work immediately, independently of exit animation. Test gesture cancellation and blocker changes during a gesture.

## 6. State and restoration

Keep explicit navigation save/restore actions: UIManager owns selected tab and all PageEntry lists, so register an aggregate SavedStateRegistry provider and restore before mounting. Use Kotlin serialization with explicit polymorphic route serializers; do not retain existing flat field schema. A small versioned envelope, byte/count limits, route/ID/root validation and safe fallback remain necessary. Never persist callbacks, jobs, Deferreds or transient prompts/modals by default. Recreate scopes for restored IDs. Page-local rememberSaveable and ViewModelStore ownership use official decorators keyed by instance ID; only explicitly saved ViewModel data survives process death. Each tab must retain independent decorator ownership even when not displayed; prove this using the official multiple-back-stack recipe. Generic SavedState remains available for non-navigation state. No second page SaveableStateHolder.

## 7. Dependencies and implementation plan

Target stable Navigation 3 runtime/UI, lifecycle ViewModel navigation decorator and Kotlin serialization. Verify exact versions, compileSdk, AGP, Kotlin, Compose and minSdk compatibility through Gradle; do not assume dependency resolution. First build a minimal native proof of concept, then replace existing PAGE rendering/stack and flat navigation schema, retaining presentation logic. Do not introduce a second navigation engine or change iOS implementation to depend on Android.

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

Before migration, prove duplicate-key isolation, all-tab draft/scroll/ViewModel retention, pop disposal, Activity recreation, actual process-death restore of IDs and saved data, predictive back cancellation/commit, modal interception and owner cascade. Run JVM tests, Android assembleDebug and emulator/device tests. Record actual results; no verification is claimed here.

Implementation locations: android/core/src/main/kotlin/appbase/UIManager.kt, UIHost.kt, SavedState.kt, AndroidSavedState.kt. Source migration remains pending.
