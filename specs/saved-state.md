# SavedState — Design specification

Status: typed-slot source migration completed; native build and runtime verification pending. Navigation integration remains transitional. The untyped store and tagged Bundle codec have been removed.

## 1. Purpose and boundaries

SavedState is an optional, small, typed native-owner transient state facility. It is not a global KV store, reactive state store, database, secret store or durability guarantee. Page-local state belongs to rememberSaveable or SavedStateHandle and Navigation 3 decorators. UIManager owns navigation snapshot registration. Services and business records use appropriate persistent storage such as DataStore or Room; runtime shared state uses StateStore. Android and iOS implement independently using native facilities.

## 2. Design principles

Use native SavedStateRegistry on Android, with one aggregate provider per SavedState owner. Main-thread-only creation, access, registration, snapshot and disposal; no locks, workers, cross-thread merge, namespace tree, Any values or custom tagged Bundle codec. Encode typed values immediately with kotlinx.serialization into immutable serialized payloads; decode on read. Store byte arrays directly in Bundle. No mutable caller objects retained. Keep the facility optional; do not add DataStore or Room dependencies to Core merely to support this separation.

## 3. Implemented API

```kotlin
// Claim once per logical owner, not on every recomposition.
val draft = saved.slot("editor.draft", Draft.serializer())
if (draft == null) {
    // Duplicate live key or slot capacity reached: handle rejection.
} else {
    val restored: Draft? = draft.get()
    val accepted: Boolean = draft.set(newDraft)
    // Handle false without assuming the previous value was replaced.
    // Retain the handle across recreation as appropriate for its owner.
    // On logical owner close (not merely composition disposal):
    draft.close() // releases key and deletes payload
}
// Close saved itself when its native owner is permanently disposed.
```

A slot is bound to a unique key and explicit serializer. `slot()` returns null when the key is already owned by a live slot or the slot limit is reached; it never throws for these runtime conditions. Slot is AutoCloseable: closing it releases the key and drops its value, so per-instance keys (for example `draft.<instanceId>`) can be opened and closed indefinitely. Restored values whose key is not claimed are written for one more save only, then dropped, so renamed or removed slots cannot hold budget forever. get returns null for absent data; a value that fails to decode is removed, with a diagnostic. Unchanged bytes skip the Parcel size check. The Android adapter skips restored entries that are not byte arrays instead of failing the whole restore. The navigation provider catches snapshot encoding and size-check failures: an oversized navigation snapshot is dropped with `navigation.save.rejected` and the app restarts at its roots. set serializes and validates before replacing; false on encoding or size rejection, preserving previous accepted value. clear removes payload. Owner close unregisters provider and invalidates slots. Invalid keys, closed handles and wrong-thread calls are programmer errors. No generic Any API, scope hierarchy or arbitrary provider callbacks. Slot payload schema/version is the caller's responsibility; incompatible data is rejected with a diagnostic, never silently coerced. Diagnostics contain codes only, not data.

## 4. Size and lifecycle

Implemented defaults: 32 KiB per slot, 128 KiB aggregate serialized payload, 128 UTF-8 bytes per key, 64 slots. Maintain incremental byte accounting including keys; accounting is O(1) in slot count, but copying the candidate map and measuring the aggregate Parcel are not. Unchanged bytes bypass the Parcel check. Check actual aggregate Parcel size before publishing; reject an oversized candidate without truncation or silent entry loss. A native Parcel cap is local to this provider, not the entire Activity transaction. Restore validates aggregate and per-slot limits before decode; malformed or incompatible slots are rejected with diagnostics. Serialization may allocate before size checking; do not pass unbounded data. Register after native restoration and before save; dispose on owner thread. No guarantee of save delivery after force-stop or arbitrary process termination. Never store credentials, tokens, sensitive data, images or essential records.

## 5. Navigation integration

**Current implementation:** UIManager.bindSavedState registers independently through SavedStatePlatform, using JSON-encoded Map<String, String> version 1. It saves selected tab, instance IDs, route names, tab and parent IDs for restorable PAGE and MODAL entries. Arguments are not saved; restored instances receive emptyMap(). Restore validates IDs, routes, roots, parent chains and count. Oversized or failing save returns an empty map and emits navigation.save.rejected; rejected decoded snapshots emit navigation.restore.rejected. Jobs, Deferreds and queued prompts are not serialized; restored instances receive new runtime resources. No generic slot dependency is required.

**Target, not implemented:** replace the flat map with versioned NavSnapshot(version, selected, tabs), PageEntry IDs and explicitly registered polymorphic NavKey serializers. Restore non-sensitive typed route parameters on successful restore, validate byte/count bounds and fall back to roots safely. Transient presentations are not restored by default. UIHost has wired native page saveable-state and ViewModel decorators, but their behavior is not build- or device-verified. See [UIManager](ui-manager.md).

## 6. Potential problems and mitigations

| Risk | Mitigation |
|---|---|
| Schema changes | Explicit serializer and caller-owned schema/version; warn and reject incompatible payload |
| Oversized write | Validate serialized bytes and aggregate native size before commit; retain old value |
| Native transaction overflow | Conservative local cap; no claim of whole-Activity safety |
| Wrong thread or reentry | Main-thread contract; no arbitrary provider callbacks |
| Mutable object aliasing | Serialize on write, decode on read |
| Sensitive data or durability | Use secure/persistent storage instead |
| Navigation restore corruption | Versioned snapshot, validation and root fallback |

## 7. Acceptance and source locations

Test typed round trip, malformed/schema mismatch diagnostics, UTF-8 keys, duplicate slot rejection, exact limits, replacement accounting, rejected-write retention, Parcel overflow, owner close and wrong-thread rejection. Verify Activity recreation and actual process-death restoration; run Gradle unit tests and assembleDebug. Typed-slot source migration is complete; successful compilation and test execution are not claimed. Verify the remaining typed navigation migration separately.

Source: android/core/src/main/kotlin/appbase/SavedState.kt, AndroidSavedState.kt, UIManager.kt. The untyped store and tagged codec have already been removed without compatibility aliases. Remaining work: verify callers, compile and run tests, complete typed navigation restoration.
