plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
  id("org.jetbrains.kotlin.plugin.compose")
}

android {
  namespace = "dev.improve.vrcode"
  compileSdk = 35
  ndkVersion = "27.2.12479018"
  defaultConfig {
    applicationId = "dev.improve.vrcode"
    minSdk = 34
    targetSdk = 34
    versionCode = 1
    versionName = "0.1.0"
    ndk { abiFilters += "arm64-v8a" }
    externalNativeBuild { cmake { cppFlags += "-std=c++17" } }
  }
  buildFeatures { compose = true; prefab = true }
  externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  kotlinOptions { jvmTarget = "17" }
  packaging { resources.excludes += "META-INF/LICENSE*" }
}

// Package only runtime GLBs; keep source .blend files and review renders out of the APK.
val prepareVoicePropAssets by tasks.registering(Sync::class) {
  from(rootProject.file("assets/microphone")) { include("microphone.glb") }
  // One news microphone per AI provider's logo (see scripts/brand-news-mic.ts).
  from(rootProject.file("assets/voice-props")) { include("news-microphone-*.glb", "megaphone.glb", "banana.glb") }
  into(layout.buildDirectory.dir("generated/voice-prop-assets/voice-props"))
}
android.sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/voice-prop-assets"))
tasks.named("preBuild").configure { dependsOn(prepareVoicePropAssets) }

dependencies {
  implementation(project(":connection"))
  implementation(project(":settings-ui"))
  implementation("dev.improve.xmux:xmux-spatial:0.2.0-SNAPSHOT")
  implementation("dev.improve.xmux:xmux-connect-panel:0.2.0-SNAPSHOT")
  implementation(platform("androidx.compose:compose-bom:2024.09.03"))
  implementation("androidx.compose.material3:material3")
  implementation("androidx.compose.material:material-icons-core")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
  // Headers only: the Spatial SDK owns the OpenXR loader and instance.
  compileOnly("org.khronos.openxr:openxr_loader_for_android:1.1.65")
  testImplementation("junit:junit:4.13.2")
  // The real org.json, in place of android.jar's stubs, for the cmux layout codec tests.
  testImplementation("org.json:json:20240303")
}

