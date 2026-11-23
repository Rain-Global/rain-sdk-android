plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.dokka)
    alias(libs.plugins.vanniktech.maven.publish)
}

version = libs.versions.rain.sdk.get()

android {
    namespace = "com.rain.sdk.portal"
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
        // This module implements WalletProvider and ProviderDescriptor, which need @ExperimentalRainApi
        // to implement, so it opts in as a whole; a host opts in on its own implementing class.
        freeCompilerArgs.addAll(
            "-opt-in=com.rain.sdk.ExperimentalRainApi",
            // Core's cross-module seams (token store, Solana support, ABI and error helpers) are marked
            // @RainAdapterApi. Adapter modules are the intended callers, so this one opts in.
            "-opt-in=com.rain.sdk.internal.RainAdapterApi",
        )
    }
}

// The root build's subprojects block already forces the project onto a single Bouncy Castle
// artifact (bcprov-jdk15to18, which core declares directly), which web3j's transitive bcprov-jdk18on would otherwise
// duplicate. Kept here too as belt-and-suspenders for direct module builds.
configurations.all {
    exclude(group = "org.bouncycastle", module = "bcprov-jdk18on")
}

dependencies {
    // Vendor-free core: exposes the WalletProvider port, models, registry, and domain logic.
    api(project(":rain-core-android"))

    // Portal SDK. `api` (not `implementation`) because PortalProvider's public constructor takes an
    // `onPortalCreated: ((Portal) -> Unit)?` callback, which puts the `Portal` type in a signature
    // consumers compile against. Same rule as rain-privy-android and rain-turnkey-android: a vendor
    // type in a public Rain signature makes that dependency part of the API.
    api(libs.portal.android)

    // Web3j for ABI encoding in Portal balance/transaction paths. See the BC note above.
    implementation(libs.web3j.core) {
        exclude(group = "org.bouncycastle", module = "bcprov-jdk18on")
        // Desktop-only pieces web3j ships (Unix-socket IPC through jnr and its native libraries, ASM,
        // a WebSocket client, OkHttp's logging interceptor). Nothing on Android uses them, and leaving
        // them out makes a shrunk app about 250 KB smaller.
        exclude(group = "com.github.jnr")
        exclude(group = "org.ow2.asm")
        exclude(group = "org.java-websocket")
        exclude(group = "com.squareup.okhttp3", module = "logging-interceptor")
    }

    // `Flow` appears in this module's public signatures (session state), so the coroutines library is
    // part of its contract with hosts.
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.timber)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
}

mavenPublishing {
    coordinates("xyz.rain", "rain-portal-android", libs.versions.rain.sdk.get())

    pom {
        name.set("Rain SDK Android — Portal adapter")
        description.set("Portal MPC wallet provider for the Rain Android SDK")
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
