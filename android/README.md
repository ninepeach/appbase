# Appbase 0.3.0
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
