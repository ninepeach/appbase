# Appbase Android

The Android workspace contains the Core library, the optional UI library, and the opt-in `appbaseTest` verification application.

## Build
With JDK 17, Gradle 8.11.1 and Android SDK 35:
```
cd android
gradle :core:build :ui:assembleDebug
gradle -PwithDemo=true :appbaseTest:assembleDebug
bash tests/run.sh
```
The test application source is in `examples/appbaseTest/android`. Android Studio can import the `android` workspace with `withDemo=true` in Gradle properties. No wrapper is included.

## appbaseTest

The previous Shadowrocket-style product demo has been removed. `appbaseTest` is intentionally small and contains only:

- `Run all tests` for Command, Event, State, Channel, Lifecycle, Diagnostics, SavedState, UIManager, UIHost, and WakeRouter.
- Page, Modal, and Overlay buttons for manual UIManager and UIHost checks.
- `Save and recreate Activity` for AndroidSavedState and navigation restoration.

The application uses only existing Core public APIs. It has no network or VPN permissions and does not extend Core.
