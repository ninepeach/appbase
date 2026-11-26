# SavedState — Design specification

Status: target design approved for simplification; existing source still implements the untyped store. Migration and tests pending.

## 1. Purpose and boundaries

SavedState is an optional, small, typed native-owner transient state facility. It is not a global KV store, reactive state store, database, secret store or durability guarantee. Page-local state belongs to rememberSaveable or SavedStateHandle and Navigation 3 decorators. UIManager owns navigation snapshot registration. Services and business records use appropriate persistent storage such as DataStore or Room; runtime shared state uses StateStore. Android and iOS implement independently using native facilities.

## 2. Design principles

Use native SavedStateRegistry on Android, with one aggregate provider per SavedState owner. Main-thread-only creation, access, registration, snapshot and disposal; no locks, workers, cross-thread merge, namespace tree, Any values or custom tagged Bundle codec. Encode typed values immediately with kotlinx.serialization into immutable serialized payloads; decode on read. Store byte arrays directly in Bundle. No mutable caller objects retained. Keep the facility optional; do not add DataStore or Room dependencies to Core merely to support this separation.

## 3. Proposed API

```kotlin
val draft = saved.slot("editor.draft", Draft.serializer())
val restored: Draft? = draft.get()
val accepted: Boolean = draft.set(newDraft)
draft.clear()
```

A slot is bound to a unique key and explicit serializer; duplicate registration is rejected. get returns null for absent or rejected restored data, with a diagnostic for decode/schema failure. set serializes and validates before replacing; false on encoding or size rejection, preserving previous accepted value. clear removes payload. Owner close unregisters provider and invalidates slots. Invalid keys, closed handles and wrong-thread calls are programmer errors. No generic Any API, scope hierarchy or arbitrary provider callbacks. Slot payload schema/version is the caller's responsibility; incompatible data is rejected with a diagnostic, never silently coerced. Diagnostics contain codes only, not data.

## 4. Size and lifecycle

Proposed defaults: 32 KiB per slot, 128 KiB aggregate serialized payload, 128 UTF-8 bytes per key, 64 slots. Maintain incremental byte accounting including keys; replacement is O(1) in slot count after serialization. Check actual aggregate Parcel size before publishing; reject an oversized candidate without truncation or silent entry loss. A native Parcel cap is local to this provider, not the entire Activity transaction. Restore validates aggregate and per-slot limits before decode; malformed or incompatible slots are rejected with diagnostics. Serialization may allocate before size checking; do not pass unbounded data. Register after native restoration and before save; dispose on owner thread. No guarantee of save delivery after force-stop or arbitrary process termination. Never store credentials, tokens, sensitive data, images or essential records.

## 5. Navigation integration

UIManager separately registers a single native provider for a versioned serializable NavSnapshot(version, selected, tabs), including complete PageEntry IDs and polymorphic NavKey payloads. Explicit serializers, byte/count bounds, root/route/ID validation and safe root fallback are required. Route parameters survive successful restore; sensitive parameters must not be serialized. No generic slot dependency is required for navigation. Page decorators own page-local state and ViewModel stores; transient presentations, jobs and Deferreds are not restored. See [UIManager](ui-manager.md).

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

Test typed round trip, malformed/schema mismatch diagnostics, UTF-8 keys, duplicate slot rejection, exact limits, replacement accounting, rejected-write retention, Parcel overflow, owner close and wrong-thread rejection. Verify Activity recreation and actual process-death restoration; run Gradle unit tests and assembleDebug. No tests or migration are claimed complete.

Source: android/core/src/main/kotlin/appbase/SavedState.kt, AndroidSavedState.kt, UIManager.kt. Replace the untyped store and tagged codec without compatibility aliases, coordinate callers and tests in the same migration.
