plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.rain.sdk.sample"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.rain.sdk.sample"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        // Committed on purpose. The demo's passkeys are bound to this certificate's fingerprint through
        // passkeys.uptop.xyz/.well-known/assetlinks.json, so every developer's build must carry it. Standard
        // debug alias and passwords; no other key may live here.
        getByName("debug") {
            storeFile = file("demo-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            // Minified on purpose: the sample doubles as the SDK's build-time R8 consumer
            // check. CI builds :app:assembleRelease, which proves R8 completes against the
            // SDK modules and exercises their consumer-rules.pro keep rules. It does NOT
            // run the shrunk app — runtime behavior under R8 is not verified in CI.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed with the demo keystore too, so the minified build runs the passkey ceremony under
            // the same fingerprint; the sample is never published.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    implementation(project(":rain-core-android"))
    implementation(project(":rain-wallet-android"))
    // Bring-your-own Turnkey tab: the public TurnkeyProvider / TurnkeyConfig(turnkey) API, plus the
    // Turnkey Kotlin SDK the sample drives itself in TurnkeyAuthSample (host-side reference code).
    implementation(project(":rain-turnkey-android"))
    implementation(libs.turnkey.sdk.kotlin)
    implementation(project(":rain-portal-android"))
    implementation(project(":rain-privy-android"))

    implementation(libs.portal.android)
    implementation(libs.timber)
    // The demo's own Rain API client (RainApiClient.kt): the Rain issuing API is the host's call, not the SDK's.
    implementation(libs.okhttp)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    // LocalLifecycleOwner for the export card; Compose UI carries it transitively, declared so a
    // BOM bump cannot drop it silently.
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    // implementation("androidx.activity:activity-compose:1.9.3")

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    // ui-tooling-preview above is only the @Preview annotation. Rendering a preview needs
    // ComposeViewAdapter from ui-tooling, which Android Studio instantiates through layoutlib.
    // Debug-only: it must not reach the release build, which the R8 CI leg checks.
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.navigation.compose)

    // JVM tests for the demo's Rain API client. The real org.json stands in for android.jar's stubs.
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.json)
}
