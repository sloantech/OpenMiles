plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "org.openmiles.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.openmiles.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 16
        versionName = "0.13.1"
        // When this copy was built. The app uses it to suggest checking for a newer version
        // without ever going online.
        buildConfigField("long", "BUILD_TIME_MS", "${System.currentTimeMillis()}L")
    }

    // The permanent signing key is never stored in the code. The build reads it from
    // environment variables that GitHub fills in from its encrypted secrets. Without them
    // (for example on a copy of this project), the release build is simply left unsigned.
    val keystorePath: String? = System.getenv("OPENMILES_KEYSTORE_PATH")
    signingConfigs {
        if (!keystorePath.isNullOrBlank()) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("OPENMILES_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("OPENMILES_KEY_ALIAS")
                keyPassword = System.getenv("OPENMILES_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (!keystorePath.isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // Security-related lint checks are errors, so the build fails if any of them ever fires.
    lint {
        abortOnError = true
        checkDependencies = true
        error += listOf(
            "ExportedContentProvider", "ExportedReceiver", "ExportedService",
            "GrantAllUris", "UnsafeIntentLaunch", "UnprotectedSMSBroadcastReceiver",
            "UnsafeProtectedBroadcastReceiver", "HardcodedDebugMode", "SetJavaScriptEnabled",
            "AllowBackup", "PendingIntentMutability", "WorldReadableFiles", "WorldWriteableFiles",
            "SecureRandom", "TrustAllX509TrustManager", "MissingPermission",
            "ProtectedPermissions", "InsecureBaseConfiguration", "UnsafeNativeCodeLocation"
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("com.google.android.gms:play-services-location:21.3.0")

    // Reads large Google Timeline files piece by piece, so the phone never runs out of memory.
    implementation("com.google.code.gson:gson:2.11.0")

    testImplementation("junit:junit:4.13.2")
    // Runs the real database on the build server, so upgrades are tested before they reach a phone.
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
}
