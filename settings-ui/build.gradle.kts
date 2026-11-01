plugins {
  id("com.android.library")
  id("org.jetbrains.kotlin.android")
  id("org.jetbrains.kotlin.plugin.compose")
}

android {
  namespace = "dev.improve.vrcode.settings"
  compileSdk = 35
  defaultConfig {
    minSdk = 34
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }
  buildFeatures { compose = true }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  kotlinOptions { jvmTarget = "17" }
}

dependencies {
  api("dev.improve.xmux:xmux-window-frame:0.2.0-SNAPSHOT")
  api("dev.improve.xmux:xmux-terminal-grid:0.2.0-SNAPSHOT")
  api("dev.improve.xmux:xmux-screen-view:0.2.0-SNAPSHOT")
  implementation("dev.improve.xmux:xmux-connect-panel:0.2.0-SNAPSHOT")
  implementation(platform("androidx.compose:compose-bom:2024.09.03"))
  implementation("androidx.compose.material3:material3")
  implementation("androidx.compose.material:material-icons-core")
  testImplementation("junit:junit:4.13.2")
  androidTestImplementation(platform("androidx.compose:compose-bom:2024.09.03"))
  androidTestImplementation("androidx.compose.ui:ui-test-junit4")
  androidTestImplementation("androidx.test:runner:1.6.2")
  debugImplementation("androidx.compose.ui:ui-test-manifest")
}
