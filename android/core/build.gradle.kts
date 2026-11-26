plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
}
android {
    namespace = "appbase"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    explicitApi()
}
ktlint { ignoreFailures.set(true) }
detekt { ignoreFailures = true }
tasks.withType(io.gitlab.arturbosch.detekt.Detekt::class.java).configureEach { jvmTarget = "17" }
dependencies {
    api(libs.kotlinx.serialization.json)
    api(libs.navigation3.runtime)
    implementation(libs.navigation3.ui)
    implementation(libs.lifecycle.viewmodel.navigation3)
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    api(platform(libs.compose.bom))
    api(libs.compose.runtime)
    implementation(libs.compose.material3)
    api(libs.activity.compose)
    api(libs.savedstate)
}
