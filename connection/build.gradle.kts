plugins {
  id("com.android.library")
  id("org.jetbrains.kotlin.android")
  id("org.jetbrains.kotlin.plugin.compose")
}
android {
  namespace = "dev.improve.vrcode.connection"
  compileSdk = 35
  defaultConfig { minSdk = 34; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
  buildFeatures { compose = true }
  compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
  kotlinOptions { jvmTarget = "17" }
}
dependencies {
  implementation(platform("androidx.compose:compose-bom:2024.09.03"))
  implementation("androidx.compose.material3:material3")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
  testImplementation("junit:junit:4.13.2")
  androidTestImplementation("androidx.test:runner:1.6.2")
  androidTestImplementation(platform("androidx.compose:compose-bom:2024.09.03"))
  androidTestImplementation("androidx.compose.ui:ui-test-junit4")
  debugImplementation("androidx.compose.ui:ui-test-manifest")
}
