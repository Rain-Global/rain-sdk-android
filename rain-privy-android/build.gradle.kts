plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.dokka)
    alias(libs.plugins.vanniktech.maven.publish)
}

version = libs.versions.rain.sdk.get()

android {
    namespace = "com.rain.sdk.privy"
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

dependencies {
    // Vendor-free core: the WalletProvider port, models, registry, and domain logic.
    api(project(":rain-core-android"))

    // Privy SDK. `api` (not `implementation`) because PrivyConfig exposes the `Privy` type to
    // consumers — the host initializes/authenticates Privy and hands the singleton in, mirroring
    // how rain-turnkey-android's TurnkeyConfig exposes TurnkeyContext. The Bouncy Castle exclusion
    // is declared on this edge so it publishes into the module metadata: Privy's own graph declares
    // bcprov-jdk18on (1.79, via kmp-embedded-wallet-impl-android), the parallel build of the artifact
    // core standardizes on, and a consumer that reaches privy-core only through this module must
    // not resolve it.
    api(libs.privy.core) {
        exclude(group = "org.bouncycastle", module = "bcprov-jdk18on")
    }
    constraints {
        // Privy's datastore dependency declares kotlin-parcelize-runtime 1.9.22, and through it the
        // deprecated kotlin-android-extensions-runtime, on the runtime classpath, three Kotlin releases
        // behind the stdlib. The 2.x artifact drops that dependency. Declared on the api scope so the
        // constraint publishes with the module and consumers resolve the same version.
        api("org.jetbrains.kotlin:kotlin-parcelize-runtime") {
            version { require(libs.versions.kotlin.get()) }
        }
    }

    // Web3j for ERC-20 ABI encoding in sendToken calldata. Shares core's Bouncy Castle note.
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
    implementation(libs.okhttp)
    // Privy's Ktor engine declares okhttp-sse 4.12.0, which still references a class OkHttp 5 removed
    // (okhttp3.internal.Util). Nothing here uses server-sent events, but the pair must stay on one
    // version so R8 sees a complete graph.
    runtimeOnly(libs.okhttp.sse)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.truth)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.json)
}

mavenPublishing {
    coordinates("xyz.rain", "rain-privy-android", libs.versions.rain.sdk.get())

    pom {
        name.set("Rain SDK Android — Privy adapter")
        description.set("Privy embedded-wallet provider for the Rain Android SDK")
        url.set("https://github.com/SignifyHQ/rain-sdk-android")
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
            connection.set("scm:git:https://github.com/SignifyHQ/rain-sdk-android.git")
            developerConnection.set("scm:git:ssh://git@github.com/SignifyHQ/rain-sdk-android.git")
            url.set("https://github.com/SignifyHQ/rain-sdk-android")
        }
    }

    publishToMavenCentral(automaticRelease = false)
    signAllPublications()
}
