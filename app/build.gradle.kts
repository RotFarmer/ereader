plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// CI passes the run number so every build installs over the previous one.
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.rotfarmer.ereader"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.rotfarmer.ereader"
        minSdk = 29
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
        // Only 64-bit ARM (every current Samsung phone/tablet): the text-recognition engine
        // is ~10 MB per processor type, so this keeps downloads small.
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    // A fixed key kept in the repo, so new builds update the app in place
    // instead of forcing an uninstall (which would wipe your notes).
    signingConfigs {
        create("shared") {
            storeFile = file("ereader.keystore")
            storePassword = "ereader"
            keyAlias = "ereader"
            keyPassword = "ereader"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("shared")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "META-INF/versions/**")
    }
}

dependencies {
    implementation("org.jsoup:jsoup:1.18.3")
    // On-device text recognition (offline), used to check whether a cover shows the title.
    implementation("com.google.mlkit:text-recognition:16.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
