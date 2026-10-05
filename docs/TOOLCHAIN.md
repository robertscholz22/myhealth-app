# Verified toolchain (built, unit-tested and linted successfully on this machine, 2026-09-12)

Result of a throwaway bootstrap: `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug` → BUILD SUCCESSFUL (0 lint errors). **Use exactly these versions in P0.** A ready-made template project with these files lived in a temporary scratchpad directory (`toolchain-template`; may vanish between sessions — this document is the durable record). Deviations that were tried and FAILED are listed at the bottom.

- JDK: 21 (Temurin, `~/jdk/current`). Only JDK 21 exists → do **not** use `jvmToolchain(17)`; instead `compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }` and `kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }`.
- Gradle wrapper 8.14.5 (`distributionUrl=https\://services.gradle.org/distributions/gradle-8.14.5-bin.zip`); generate the wrapper jar/script with the cached Gradle 8.11.1: `$(ls -d ~/.gradle/wrapper/dists/gradle-8.11.1-all/*/gradle-8.11.1/bin/gradle) wrapper --gradle-version 8.14.5 --distribution-type bin`.
- Android SDK: platforms android-35 and android-36 installed (android-37 is NOT available in the SDK repo), build-tools 34/35/36. **compileSdk = 36, targetSdk = 36, minSdk = 34.**
- `gradle.properties`: `org.gradle.jvmargs=-Xmx3g`, `android.useAndroidX=true`, `org.gradle.caching=true`, `kotlin.code.style=official`.
- Room plugin `androidx.room` with `room { schemaDirectory("$projectDir/schemas") }` works (schema JSON exported).
- **No Hilt.** Plugins applied in :app: android-application, kotlin-android, kotlin-compose, kotlin-serialization, ksp, room.

## gradle/libs.versions.toml (verified verbatim)

```toml
[versions]
agp = "8.13.2"
kotlin = "2.3.21"
ksp = "2.3.12"
composeBom = "2026.06.01"
room = "2.8.5"
[libraries]
compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "composeBom" }
compose-material3 = { group = "androidx.compose.material3", name = "material3" }
compose-ui = { group = "androidx.compose.ui", name = "ui" }
activity-compose = { group = "androidx.activity", name = "activity-compose", version = "1.12.4" }
room-runtime = { group = "androidx.room", name = "room-runtime", version.ref = "room" }
room-ktx = { group = "androidx.room", name = "room-ktx", version.ref = "room" }
room-compiler = { group = "androidx.room", name = "room-compiler", version.ref = "room" }
health-connect = { group = "androidx.health.connect", name = "connect-client", version = "1.1.0" }
junit = { group = "junit", name = "junit", version = "4.13.2" }
core-ktx = { group = "androidx.core", name = "core-ktx", version = "1.17.0" }
lifecycle-vm-compose = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-compose", version = "2.9.4" }
lifecycle-runtime-compose = { group = "androidx.lifecycle", name = "lifecycle-runtime-compose", version = "2.9.4" }
navigation-compose = { group = "androidx.navigation", name = "navigation-compose", version = "2.9.8" }
work-runtime = { group = "androidx.work", name = "work-runtime-ktx", version = "2.11.2" }
datastore-prefs = { group = "androidx.datastore", name = "datastore-preferences", version = "1.2.1" }
camerax-core = { group = "androidx.camera", name = "camera-camera2", version = "1.6.2" }
camerax-lifecycle = { group = "androidx.camera", name = "camera-lifecycle", version = "1.6.2" }
camerax-view = { group = "androidx.camera", name = "camera-view", version = "1.6.2" }
mlkit-text = { group = "com.google.android.gms", name = "play-services-mlkit-text-recognition", version = "19.0.1" }
mlkit-barcode = { group = "com.google.android.gms", name = "play-services-mlkit-barcode-scanning", version = "18.3.1" }
okhttp = { group = "com.squareup.okhttp3", name = "okhttp", version = "5.1.0" }
kotlinx-serialization-json = { group = "org.jetbrains.kotlinx", name = "kotlinx-serialization-json", version = "1.11.0" }
kotlinx-coroutines-android = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-android", version = "1.11.0" }
kotlinx-coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version = "1.11.0" }
garmin-fit = { group = "com.garmin", name = "fit", version = "21.214.0" }
compose-icons-extended = { group = "androidx.compose.material", name = "material-icons-extended" }
compose-ui-tooling-preview = { group = "androidx.compose.ui", name = "ui-tooling-preview" }
security-crypto = { group = "androidx.security", name = "security-crypto", version = "1.1.0" }
mockk = { group = "io.mockk", name = "mockk", version = "1.14.11" }
truth = { group = "com.google.truth", name = "truth", version = "1.4.5" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
room = { id = "androidx.room", version.ref = "room" }
```

## Known-bad combinations (do not retry)

- Compose BOM 2026.09.00 / Compose 1.12.x → requires AGP ≥ 9.1 (fails on AGP 8.13.2).
- androidx.core 1.18/1.19, activity-compose 1.13.0, navigation-compose 2.10.x, lifecycle 2.11.0, okhttp 5.5.0, Vico 3.x and 2.5.x → each transitively requires compileSdk 37 and/or AGP 9.1 and/or Compose 1.12. Vico is therefore dropped: draw charts with Compose `Canvas` (simple line/bar charts are all that is needed).
- AGP 9.4.0 would need compileSdk 37, which cannot be installed here → stay on AGP 8.13.2.
- Garmin FIT SDK coordinates are `com.garmin:fit` (NOT `com.garmin.fit:fit`).

## Notes

- ML Kit uses the **unbundled** Play-Services artifacts (`play-services-mlkit-text-recognition:19.0.1`, `play-services-mlkit-barcode-scanning:18.3.1`): debug APK with the full dependency set is ~81 MB unminified (bundled models made it ~149 MB). Release builds should enable `isMinifyEnabled = true` with keep rules for Room/FIT/ML Kit.
- Lint warnings present in the empty project: GradleDependency/NewerVersionAvailable (expected, because versions are pinned deliberately), MissingApplicationIcon (fix in P0 with an adaptive icon), AndroidGradlePluginVersion (expected). Configure lint to `abortOnError = true` but treat those four ids as informational.
