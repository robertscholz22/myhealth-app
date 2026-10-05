import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.myhealth"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.myhealth"
        minSdk = 34
        targetSdk = 36
        versionCode = 193
        versionName = "0.9.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // P8.7 — personal-use sideload only: the app is never published, so the release APK is
            // signed with the debug keystore. That keeps `adb install -r` working over an existing
            // debug install (same signature, data preserved) and means there is no release key to
            // manage or lose. It must be replaced with a real upload key if this ever ships.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    // MigrationTestHelper reads the exported schemas; the database moved to :shared in P20.2.
    sourceSets {
        getByName("androidTest").assets.srcDir("$rootDir/shared/schemas")
    }

    lint {
        abortOnError = true
        lintConfig = file("lint.xml")
    }
}


dependencies {
    val composeBom = platform(libs.compose.bom)
    implementation(project(":shared"))
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons.extended)
    implementation(libs.activity.compose)
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.vm.compose)
    implementation(libs.navigation.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)

    implementation(libs.datastore.prefs)

    implementation(libs.health.connect)

    implementation(libs.work.runtime)

    // P4.8 — camera + unbundled ML Kit (amendment A3).
    implementation(libs.camerax.camera.core)
    implementation(libs.camerax.core)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.mlkit.text)
    implementation(libs.mlkit.barcode)

    // P4.10 — Open Food Facts client.

    // P7.1 — Garmin FIT SDK: since P20.2 only the oracle for the shared FIT decoder.
    testImplementation(libs.garmin.fit)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(kotlin("test"))

    androidTestImplementation(libs.androidx.test.junit.ext)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
