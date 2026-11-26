# SavedState — Design specification

Status: implemented core and Android adapter; compilation, concurrency tests and integration acceptance pending. This document distinguishes current behavior from required hardening.

## 1. Purpose and non-goals

SavedState preserves small transient application/UI state across native owner reconstruction when the OS supplies a saved snapshot. It is not persistent KV, a database, a reactive state store, a secret store or a guarantee of recovery after force-stop or arbitrary process termination. No disk fallback, worker, coroutine, event bus or third-party core dependency. Android and iOS follow the same interface semantics and design principles but implement independently; this document describes the current Android implementation, not an implemented iOS port.

## 2. Design principles and architecture

- Native ownership: the platform determines save/restore timing. One root registers one aggregate provider. Android uses SavedStateRegistry and Bundle, with a Parcel size check. Native registration and root disposal follow the owner thread.
- Small, bounded state: use identifiers, selections, navigation descriptors and small drafts, not images, files, credentials or business records. Reject oversized values rather than truncate them.
- One shared Store per root: all scopes share values, providers, size accounting and one lock. No separate manager.
- Thread-safe core: static reads, mutations, capacity checks and lifecycle changes synchronize on the Store lock. External provider and warning callbacks and native disposal execute outside it.
- Defensive ownership: copy caller-owned lists/maps/byte arrays on ingress and copy on egress. Callers must not concurrently mutate input collections during a call.
- Explicit scope lifecycle: parent handles invalidate descendants on close; clear removes the namespace subtree without closing the handle. Root close unregisters the native provider. Current same-name scope handles share a namespace; see risks.
- Snapshot consistency: capture provider identities under lock, evaluate outside lock, then merge still-registered results with current static values under lock. This is not a globally simultaneous snapshot. Providers must be quick, nonblocking and internally consistent.

Flow: application -> SavedState scope -> shared Store -> aggregate snapshot -> SavedStatePlatform -> native owner. Restore reverses the flow and validates entries before exposing them.

## 3. Public API

Current Kotlin API (signatures):

```kotlin
class SavedState(
    platform: SavedStatePlatform,
    limits: SavedStateLimits = SavedStateLimits(),
    key: String = "appbase.savedState",
    warning: (String) -> Unit = {}
) : AutoCloseable {
    val limits: SavedStateLimits
    fun get(key: String): Any?
    fun set(key: String, value: Any): Boolean
    fun contains(key: String): Boolean
    fun remove(key: String): Boolean
    fun clear()
    fun register(key: String, provider: () -> Any): AutoCloseable
    fun scope(name: String): SavedState
    fun size(): Long
    override fun close()
}
interface SavedStatePlatform {
    fun consume(key: String): Map<String, Any>?
    fun register(key: String, provider: () -> Map<String, Any>): AutoCloseable
}
```

| API | Contract |
|---|---|
| get | Returns a defensive copy of a static/restored value or null. Does not execute providers. |
| set | Validates and copies value, then atomically checks aggregate limits and writes. False on rejection; previous value retained. Cannot overwrite registered provider. |
| contains | True for a static/restored entry or registered provider. |
| remove | Removes both value and provider at key; returns whether either existed. |
| clear | Removes all values/providers/accounting under the current namespace. |
| register | Registers a deferred snapshot callback; duplicate provider or exhausted entry count rejected. Returned handle unregisters only that registration. A restored value may coexist with a provider. |
| scope | Creates a child handle sharing the store; keys are slash-separated internally. Public segments must be nonblank and contain no slash. |
| size | Estimated bytes of static values and last accepted provider snapshots, not live provider values or actual Parcel bytes. |
| close | Idempotent; clears subtree and invalidates handle/descendants; root also disposes native registration. |

Invalid keys and closed handles throw; unsupported or oversized values supplied to set return false. Registration failures throw. Warning callback receives diagnostic codes, not payloads. Current supported types: String, Boolean, Int, Long, Double, ByteArray, List and Map with String keys; nested values must be non-null. Other types rejected. Any is intentional to avoid a separate value hierarchy.

```kotlin
val saved = SavedState(AndroidSavedState(activity.savedStateRegistry))
val editor = saved.scope("editor")
editor.set("draft", mapOf("name" to "Tokyo", "port" to 443))
val draft = editor.get("draft") as? Map<*, *>
val registration = saved.register("navigation") { uiManager.saveNavigation() }
// Close registration on detach; close root on native owner disposal.
```

## 4. Limits and validation

| Limit | Default |
|---|---:|
| Entry | 32,768 bytes |
| Scope | 65,536 bytes |
| Total | 131,072 bytes |
| Full key / map key | 128 UTF-8 bytes |
| Entries / collection elements | 256 |
| Nesting depth | 8 (configurable 1–32) |

Core estimates: 32 bytes per entry plus UTF-8 key; 16 bytes per value and map member plus payload. A remaining-byte budget charges during traversal and stops at first exceeded limit; depth and element count also checked. Aggregate validation includes ancestor namespace limits and total size. Static writes are atomic. Provider output is checked at save time, with insertion-order acceptance under the remaining aggregate budget. Native Parcel size is separately checked; Android currently drops entries that make the Bundle exceed its own cap. Core estimates are not exact heap or serialized sizes.

## 5. Platform and lifecycle

Target module placement: SavedState and AndroidSavedState both belong to Core, alongside UIManager and UIHost. The native adapter currently resides in the UI library; relocation and Android Core build migration are pending. Core must not depend on the optional visual component library. See [UIManager](ui-manager.md).

AndroidSavedState(registry, maxBytes = 131072) encodes supported types into tagged Bundles, decodes on consume, and checks actual Parcel size. Register after the native registry is restored and before its save phase; dispose on the native owner thread. No guarantee of OS save delivery. UIManager.bindSavedState restores its navigation map then registers a provider; arbitrary route arguments are excluded. Unified app.savedState ownership and UIHost automatic binding remain pending. Native adapter and core limits must be configured coherently.

## 6. Potential problems and solutions

| Problem | Current mitigation / remaining solution |
|---|---|
| Oversized/deep/cyclic input | Incremental budget, depth and collection bounds; test early termination. Copy is a second pass: caller must not mutate input concurrently; consider one-pass bounded copy if needed. |
| Concurrent writes exceed total | Shared lock makes aggregate validation and commit atomic. Test concurrent boundary writes. |
| Deadlock from provider reentry | Execute providers and warnings outside lock; identity check rejects stale results. Provider must not block native save thread. |
| Snapshot races | Static values taken at merge; providers sampled independently. Document this, do not claim global transaction. Newly registered providers may wait until next save. |
| Parent close and duplicate scope handles | Parent chain invalidates descendants of that handle. Same-prefix handles can still write after another handle closes; define ownership policy and test before stable release. |
| Provider failure | Core catches callback/validation/copy failure and omits entry. Android decode currently fails whole consume on malformed entry; isolate per-entry decode and warn in future hardening. |
| Stale restored value | Unregister removes provider, not restored value; explicitly remove key if no fallback desired. Document and test. |
| Reentrant/concurrent save | Current guard returns empty map; native owner should serialize saves. Test reentry and consider retaining last valid snapshot if required. |
| Memory during snapshot | Current code accumulates all individually bounded provider results before aggregate merge; total temporary memory can exceed total budget. Future hardening: process one provider at a time with bounded aggregate staging. |
| Size accuracy | size reports estimates and last accepted provider sizes, which may be stale or differ from native accepted entries. Do not treat as exact current usage. |
| Native overflow | Parcel cap protects this provider only, not the whole Activity transaction. Android currently drops overflow entries silently; add diagnostic reporting and test native overhead. |
| Navigation entry count | Current flat navigation map uses four keys per instance plus metadata; default 256 collection limit may reject deep stacks. Use compact structured descriptors or explicitly bounded navigation capacity. |
| Secrets and durability | Never store tokens, passwords or essential records; use secure/persistent storage instead. |
| Native owner disposal | Root creation/close must follow platform thread rules; no automatic main-thread dispatch. Add lifecycle integration and tests. |

## 7. Acceptance and verification

Before Stable: compile core and Android UI; test every supported type and defensive copy; invalid/null/deep/cyclic values; UTF-8 and exact budget boundaries; ancestor/total/entry limits; concurrent writes and close; duplicate registration and stale handles; provider reentry/failure/unregister races; restoration and malformed native bundles; actual Parcel limits; Activity recreation and process recreation with UIManager; confirm no demo changes required for module tests. Verify native registration disposal and no external callback under Store lock. Current status: source implementation committed; these tests and app/UIHost integration are not verified. Do not mark Stable until acceptance passes.

## 8. Source locations

- android/core/src/main/kotlin/appbase/SavedState.kt
- android/ui/src/main/java/appbase/ui/AndroidSavedState.kt (current; target: Core)
- android/core/src/main/kotlin/appbase/UIManager.kt
