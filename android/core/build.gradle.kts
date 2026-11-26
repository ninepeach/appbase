plugins { id("com.android.library"); kotlin("android"); id("org.jetbrains.kotlin.plugin.compose") }
android {
    namespace = "appbase"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
kotlin { jvmToolchain(17) }
dependencies {
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    api("androidx.navigation3:navigation3-runtime:1.2.2")
    implementation("androidx.navigation3:navigation3-ui:1.2.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-navigation3:2.10.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    api(platform("androidx.compose:compose-bom:2025.04.01"))
    api("androidx.compose.runtime:runtime")
    implementation("androidx.compose.material3:material3")
    api("androidx.activity:activity-compose:1.10.1")
    api("androidx.savedstate:savedstate:1.2.1")
}
