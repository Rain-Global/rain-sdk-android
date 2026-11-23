plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.dokka)
    alias(libs.plugins.vanniktech.maven.publish)
}

version = libs.versions.rain.sdk.get()

android {
    namespace = "com.rain.sdk.wallet"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        // Partner apps on compileSdk 36, all Play asks for today, can still consume this AAR. Without
        // this, AGP 9 stamps the module's own compileSdk as the consumer floor.
        aarMetadata {
            minCompileSdk = libs.versions.minCompileSdk.get().toInt()
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    lint {
        // Libraries carry no targetSdk of their own; lint still checks behaviour changes up to this level.
        targetSdk = libs.versions.targetSdk.get().toInt()
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        freeCompilerArgs.addAll(
            // The wallet backend's managed authentication surface is marked @InternalRainTurnkeyApi
            // (a @RequiresOptIn marker at error level). This module is its intended consumer and opts
            // in as a whole; the flag, rather than @OptIn annotations, also keeps the marker's name
            // out of the rendered API docs.
            "-opt-in=com.rain.sdk.turnkey.InternalRainTurnkeyApi",
            // RainProvider implements ProviderDescriptor, which needs @ExperimentalRainApi to implement.
            "-opt-in=com.rain.sdk.ExperimentalRainApi",
        )
    }
}

// Two `org.bouncycastle.*` builds ship the same class names, so dex-ing both fails with
// "Duplicate class" errors. The project standardizes on `bcprov-jdk15to18`; the root build applies
// this to every module and it is kept here too for direct module builds.
configurations.all {
    exclude(group = "org.bouncycastle", module = "bcprov-jdk18on")
}

dependencies {
    // Vendor-free core: the port, models, registry and domain logic. `api`, so `RainSdk`,
    // `RainClient`, `ProviderId` and `RainError` reach a host through this one dependency.
    api(project(":rain-core-android"))

    // The wallet backend adapter. `implementation`, never `api`: nothing from it appears in this
    // module's public signatures, and keeping it off a host's compile classpath is what makes the
    // Rain wallet surface vendor-neutral. The vendor SDK still ships on the host's runtime classpath.
    implementation(project(":rain-turnkey-android"))

    // `Flow` appears in this module's public signatures (session and auth state), so the
    // coroutines library is part of its contract with hosts.
    api(libs.kotlinx.coroutines.core)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    // The neutrality test walks the public signatures.
    testImplementation(libs.kotlin.reflect)
}

mavenPublishing {
    coordinates("xyz.rain", "rain-wallet-android", libs.versions.rain.sdk.get())

    pom {
        name.set("Rain SDK Android — Rain wallet")
        description.set("The Rain wallet provider for the Rain Android SDK")
        url.set("https://github.com/Rain-Global/rain-sdk-android")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        developers {
            developer {
                id.set("rain")
                name.set("Rain Engineering")
                email.set("maven@rain.xyz")
                organization.set("Rain")
                organizationUrl.set("https://rain.xyz")
            }
        }
        organization {
            name.set("Rain")
            url.set("https://rain.xyz")
        }
        scm {
            connection.set("scm:git:https://github.com/Rain-Global/rain-sdk-android.git")
            developerConnection.set("scm:git:ssh://git@github.com/Rain-Global/rain-sdk-android.git")
            url.set("https://github.com/Rain-Global/rain-sdk-android")
        }
    }

    publishToMavenCentral(automaticRelease = false)
    signAllPublications()
}
