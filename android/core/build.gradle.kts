plugins { id("com.android.library"); kotlin("android"); id("org.jetbrains.kotlin.plugin.compose") }
android {
    namespace = "appbase"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
kotlin { jvmToolchain(17) }
dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    api(platform("androidx.compose:compose-bom:2025.04.01"))
    api("androidx.compose.runtime:runtime")
    implementation("androidx.compose.material3:material3")
    api("androidx.activity:activity-compose:1.10.1")
    api("androidx.savedstate:savedstate:1.2.1")
}
