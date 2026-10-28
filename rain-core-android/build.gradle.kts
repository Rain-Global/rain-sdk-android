plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.dokka)
    alias(libs.plugins.vanniktech.maven.publish)
}

version = libs.versions.rain.sdk.get()

android {
    namespace = "com.rain.sdk"
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
            // Core's own cross-module seams are marked @RainAdapterApi. This module declares and
            // uses them, so it opts in as a whole; host apps do not.
            "-opt-in=com.rain.sdk.internal.RainAdapterApi",
            // Implementing RainClient, WalletProvider or ProviderDescriptor needs @ExperimentalRainApi.
            // Core implements RainClient and its tests stub the other two, so it opts in as a whole.
            "-opt-in=com.rain.sdk.ExperimentalRainApi",
        )
    }
}

// This module needs Bouncy Castle at runtime: web3j's crypto helpers (Keccak hashing behind
// `RainHexUtils.validateAndChecksum` and the Solana withdrawal path) call into it. It is declared
// directly below rather than inherited from a wallet vendor's transitive graph, so a Portal-only
// or Privy-only consumer still gets it.
//
// Two `org.bouncycastle.*` builds exist and ship the same class names, so dex-ing both fails with
// "Duplicate class" errors. The project standardizes on `bcprov-jdk15to18` and excludes web3j's
// parallel `bcprov-jdk18on:1.73`. The targeted exclusion below (on the web3j dependency) is also
// published into Gradle Module Metadata so downstream Gradle consumers inherit it automatically.
// The configurations-wide exclusion is belt-and-suspenders for direct module builds and non-Gradle
// consumers — see docs/TURNKEY_SUPPORT.md for the Maven-POM workaround.
configurations.all {
    exclude(group = "org.bouncycastle", module = "bcprov-jdk18on")
}

dependencies {
    // Bouncy Castle: web3j's Keccak hashing needs it, so core owns the dependency and its CVE
    // floor rather than inheriting either from a wallet vendor. `implementation` because no public
    // signature names a BC type. Constraints publish into Gradle Module Metadata, so downstream
    // consumers inherit the floor; they only raise versions, never downgrade.
    //   bcprov-jdk15to18 < 1.84 -> GHSA-574f-3g2m-x479 (GOST 28147 CTR keystream reuse, critical)
    constraints {
        implementation(libs.bouncycastle.bcprov) {
            because(
                "GHSA-574f-3g2m-x479: GOST 28147 CTR keystream reuse in bcprov < 1.84. " +
                    "bcprov-jdk15to18 is the single BC artifact on the classpath (it replaces " +
                    "web3j's bcprov-jdk18on:1.73)"
            )
        }
    }

    implementation(libs.bouncycastle.bcprov)

    // Web3j for ABI Encoding. See note above about Bouncy Castle conflict.
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

    // Coroutines
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    
    // Timber
    implementation(libs.timber)

    // Networking
    implementation(libs.okhttp)
    
    // Utilities
    implementation(libs.zxing.core)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    // Already on the test runtime classpath via mockk; declared so reflection compiles cleanly.
    testImplementation(kotlin("reflect"))
    testImplementation(libs.truth)
    testImplementation(libs.okhttp.mockwebserver)
    // Real org.json implementation for unit tests — production code uses Android's bundled
    // org.json (which AGP stubs on the test JVM and makes throw "not mocked" at runtime).
    testImplementation(libs.json)
    // Reference implementation of Solana address/transaction encoding, used ONLY as a test
    // oracle: the SDK's own `internal.solana` package is asserted byte-for-byte against it
    // (SolanaAddressesTest, SolanaTransactionBuilderTest). Deliberately test-scoped — this is a
    // published artifact, so consumers must not inherit a Solana library they don't call.
    testImplementation(libs.sol4k) {
        // sol4k publishes against kotlin-stdlib 2.4.0, whose metadata this project's Kotlin 2.2
        // compiler cannot read. Gradle would otherwise raise the whole test classpath to it and
        // fail every test compile. The project's own stdlib is enough to run sol4k's encoders.
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    }
    testImplementation(libs.sol4k.tweetnacl)
}

mavenPublishing {
    coordinates("xyz.rain", "rain-core-android", libs.versions.rain.sdk.get())

    pom {
        name.set("Rain SDK Android — Core")
        description.set("Core of the Rain Android SDK: collateral withdrawals, balances and token reads through any wallet provider. Add a wallet module or bring your own.")
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

    // Uploads to the Sonatype Central Portal without releasing: after publishToMavenCentral, the deployment
    // waits in the Portal until someone presses Publish there, even if the uploading machine sets
    // mavenCentralAutomaticPublishing. The plugin's publishAndReleaseToMavenCentral would release at once,
    // so the root build makes it fail. A published version can never be changed or deleted.
    publishToMavenCentral(automaticRelease = false)

    // Signs every published file. The key comes from the Gradle properties signingInMemoryKey and
    // signingInMemoryKeyPassword (in ~/.gradle/gradle.properties, or as ORG_GRADLE_PROJECT_ env vars
    // in CI); local.properties is not read.
    signAllPublications()
}

// RainHttpSourceScanTest reads the other library modules' sources, so they are inputs of this
// module's tests; without this an adapter-only change leaves the task up to date and the scan idle.
tasks.withType<Test>().configureEach {
    inputs.files(
        rootProject.fileTree("rain-turnkey-android/src/main"),
        rootProject.fileTree("rain-portal-android/src/main"),
        rootProject.fileTree("rain-privy-android/src/main"),
        rootProject.fileTree("rain-wallet-android/src/main"),
    ).withPropertyName("libraryMainSources").withPathSensitivity(PathSensitivity.RELATIVE)
}
