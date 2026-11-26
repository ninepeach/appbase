# Appbase Emulator Baseline — Phase 0

Recorded 2026-11-27 on emulator `Pixel_9_Pro` (emulator-5554), API 36.
Build from commit `27be8b1` (redesign v3 docs). APK: `appbaseTest-debug.apk`.

## Automated tests (Run all tests — 10/10 passed)

| Test | Result | Detail |
| --- | --- | --- |
| Command | PASS | execution and timeout |
| Event | PASS | emit and typed observation |
| State | PASS | set, update, and StateFlow |
| Channel | PASS | ordered Flow collection |
| Lifecycle | PASS | background cancellation and foreground scope |
| Diagnostics | PASS | bounded log and command trace |
| SavedState | PASS | typed slot set/get, exclusive claim and release |
| UIManager | PASS | page, modal queue, and back |
| UIHost | PASS | mounted with live root renderer |
| WakeRouter | PASS | dispatch and in-memory deduplication |

## Manual UIHost / UIManager checks

| Check | Result | Observation |
| --- | --- | --- |
| Page | PASS | Tapped Page; nav pushed detail screen showing "Page rendered by UIHost" and instance ID; Back key returned to home |
| Modal | PASS | Tapped Modal; modal dialog appeared with "Modal rendered by UIHost" and instance ID; Back key dismissed it |
| Overlay | PASS | Tapped Overlay; overlay banner appeared at bottom of home screen; "Close overlay" button dismissed it |
| Save and recreate Activity | PASS | Tapped button; recreation count incremented from 0 to 1 and persisted; navigation snapshot restored (same page instance ID, confirming nav state round-trip) |

## Known limitations at this baseline (to be fixed in later phases)

- Navigation snapshot saves a flat string map; **page arguments are not saved** (`emptyMap()` on restore). Fixed in phase 4.
- Lifecycle transitions driven by `Activity.onStart/onStop`; rotating the screen briefly enters BACKGROUND and cancels `foregroundScope`. Fixed in phase 3.
- UIManager uses `Mutex` + `Dispatchers.Default`; not main-thread confined. Fixed in phase 5.
- All code is in one Android library (`core`); kernel cannot be tested without Android SDK. Fixed in phase 1.
- Package name is single-level `appbase`. Fixed in phase 1.
- No explicit API mode; public surface is uncontrolled. Fixed in phase 0 (this commit).
- JVM smoke test (`tests/run.sh`) requires `kotlinc`, which is not installed locally; test runs on CI only.
