# Appbase 0.4.0-alpha.2
Native application microframework. Android core and UI are independent of examples. iOS is reserved, not implemented.

## Build
With JDK 17, Gradle 8.11.1 and Android SDK 35:
```
cd android
gradle :core:build :ui:assembleDebug
gradle -PwithDemo=true :appbaseTest:assembleDebug
bash tests/run.sh
```
The opt-in `appbaseTest` application is located in `examples/appbaseTest/android`. Android Studio can import `android` with `withDemo=true` in Gradle properties. No wrapper is included.

`appbaseTest` is a minimal verification panel, not a product simulation. It uses existing public APIs to exercise Command, Event, State, Channel, Lifecycle, Diagnostics, SavedState, UIManager, UIHost, and WakeRouter. `Run all tests` performs the non-interactive checks. The Page, Modal, Overlay, and `Save and recreate Activity` controls cover rendering, back handling, and Android saved-state restoration manually. The application has no network or VPN permissions and does not add Core behavior.

The standalone smoke test covers command execution, timeout, cancellation, capability checks, event delivery, state updates, channel collection, lifecycle scopes, diagnostics, saved state, UIManager navigation, and wake routing. UIHost and AndroidSavedState require the Android application because they depend on native Android owners.

## v0.4.0-alpha.1
Adds a JVM lifecycle state and cancellable foreground scope, bounded navigation and explicit push/pop. Android platform lifecycle binding and the remaining hardening work are not implemented. JVM smoke tests pass; Android build and device tests are pending.

## v0.4.0-alpha.2
Breaking rename: `AppHost` becomes `App` (default empty plugin list). Adds `cmd`, `event`, `state`, `inspector`, `log`, `platform` accessors. Lifecycle transitions publish best-effort events; StateFlow remains authoritative. Logger is a bounded in-memory code-only ring. CrashCapture is opt-in and best-effort, delegates fatal exceptions to the previous process handler. WakeRouter accepts explicit routes, bounded payloads and in-memory expiry/deduplication; BUSY requires caller retry policy. Handler failures propagate; handlers must be idempotent. No APNs/FCM integration, durable inbox, OS wake guarantees, file log rotation, crash-loop protection, Android lifecycle binding or iOS implementation yet. JVM tests only.
