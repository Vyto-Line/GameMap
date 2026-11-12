plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "gr.gamemap"
    compileSdk = 34

    defaultConfig {
        applicationId = "gr.gamemap"
        minSdk = 26
        targetSdk = 34
        // CI passes -PbuildNumber=<run number>; the updater compares against it.
        val build = (project.findProperty("buildNumber") as String?)?.toIntOrNull() ?: 1
        versionCode = build
        versionName = "build-$build"
        // Built-in TomTom key, from the TOMTOM_KEY secret at build time (never stored in the code).
        buildConfigField("String", "TOMTOM_KEY", "\"${System.getenv("TOMTOM_KEY") ?: ""}\"")
    }
    signingConfigs {
        // Fixed key in the repo so every build can update the previous one in place.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { test ->
                listOf("previewData", "previewOut").forEach { k ->
                    System.getProperty(k)?.let { test.systemProperty(k, it) }
                }
                test.maxHeapSize = "2g"
            }
        }
    }
}

dependencies {
    implementation("androidx.car.app:app:1.4.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
