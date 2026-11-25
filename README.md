# Appbase 0.4.0-alpha.2
Native application microframework. Android core and UI are independent of examples. iOS is reserved, not implemented.

## Build
With JDK 17, Gradle 8.11.1 and Android SDK 35:
```
cd android
gradle :core:build :ui:assembleDebug
gradle -PwithDemo=true :demo:assembleDebug
bash tests/run.sh
```
The demo is opt-in and located in examples/demo/android. Android Studio can import android with withDemo=true in Gradle properties. No wrapper is included.

Demo uses only public UIManager and Navigation APIs; all displayed network data and connection toggles are simulated. No VPN or network permissions. The core is inherited from 0.2.0; this release primarily separates the demo and preserves saveable screen state across navigation. Android compilation and device execution are unverified. Activity recreation, full UI lifecycle ownership, middleware and graceful shutdown remain open.

## v0.4.0-alpha.1
Adds a JVM lifecycle state and cancellable foreground scope, bounded navigation and explicit push/pop. Android platform lifecycle binding and the remaining hardening work are not implemented. JVM smoke tests pass; Android build and device tests are pending.

## v0.4.0-alpha.2
Breaking rename: `AppHost` becomes `App` (default empty plugin list). Adds `cmd`, `event`, `state`, `inspector`, `log`, `platform` accessors. Lifecycle transitions publish best-effort events; StateFlow remains authoritative. Logger is a bounded in-memory code-only ring. CrashCapture is opt-in and best-effort, delegates fatal exceptions to the previous process handler. WakeRouter accepts explicit routes, bounded payloads and in-memory expiry/deduplication; BUSY requires caller retry policy. Handler failures propagate; handlers must be idempotent. No APNs/FCM integration, durable inbox, OS wake guarantees, file log rotation, crash-loop protection, Android lifecycle binding or iOS implementation yet. JVM tests only.
