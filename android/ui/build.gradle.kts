plugins { id("com.android.library"); kotlin("android"); id("org.jetbrains.kotlin.plugin.compose") }
android { namespace = "appbase.ui"; compileSdk = 36; defaultConfig { minSdk = 26 }; buildFeatures { compose = true }; compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 } }
kotlin { jvmToolchain(17) }
dependencies { implementation(project(":core")); implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2"); implementation(platform("androidx.compose:compose-bom:2025.04.01")); implementation("androidx.compose.material3:material3"); implementation("androidx.activity:activity-compose:1.10.1") }
