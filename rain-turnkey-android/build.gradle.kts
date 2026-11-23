plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.dokka)
    alias(libs.plugins.vanniktech.maven.publish)
}

version = libs.versions.rain.sdk.get()

android {
    namespace = "com.rain.sdk.turnkey"
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
            // The managed authentication surface is marked @InternalRainTurnkeyApi (a @RequiresOptIn
            // marker at error level). This module defines and uses it, so it opts in as a whole;
            // host apps do not.
            "-opt-in=com.rain.sdk.turnkey.InternalRainTurnkeyApi",
            // Core's cross-module seams (chain readers, RPC clients, Solana encoders) are marked
            // @RainAdapterApi. Adapter modules are the intended callers, so this one opts in.
            "-opt-in=com.rain.sdk.internal.RainAdapterApi",
            // Implementing WalletProvider and ProviderDescriptor needs @ExperimentalRainApi, and this
            // module implements both.
            "-opt-in=com.rain.sdk.ExperimentalRainApi",
        )
    }
}

// Two `org.bouncycastle.*` builds ship the same class names, so dex-ing both fails with
// "Duplicate class" errors. The project standardizes on `bcprov-jdk15to18` — which the Turnkey
// artifacts below are compiled against — and excludes web3j's parallel `bcprov-jdk18on`. The root
// build applies this to every module; kept here too for direct module builds.
configurations.all {
    exclude(group = "org.bouncycastle", module = "bcprov-jdk18on")
}

dependencies {
    // Vendor-free core: exposes the WalletProvider port, models, registry, and domain logic.
    api(project(":rain-core-android"))

    // Security: force Turnkey's transitive deps above their CVE-affected versions. Constraints
    // publish into Gradle Module Metadata, so downstream consumers inherit the bumped versions;
    // they only raise versions, never downgrade. Both reach the classpath solely through Turnkey,
    // which is why the floors live with the adapter rather than in core.
    //   bitcoinj < 0.17.1 -> GHSA-hfcf-v2f8-x9pc (P2PKH/P2WPKH verify bypass)
    //   protobuf-javalite < 3.25.5 -> GHSA-735f-pc8j-v9w8 (DoS)
    constraints {
        api(libs.bitcoinj.core) {
            because("CVE GHSA-hfcf-v2f8-x9pc: P2PKH/P2WPKH verification bypass in bitcoinj < 0.17.1")
        }
        api(libs.protobuf.javalite) {
            because("CVE GHSA-735f-pc8j-v9w8: DoS in protobuf-javalite < 3.25.5")
        }
    }

    // Turnkey SDK. `api` on the Kotlin SDK because TurnkeyConfig exposes the `TurnkeyContext` type
    // to consumers in bring-your-own mode — the host authenticates it and hands it in. `http` and
    // `types` carry no type into a Rain public signature, so they are declared
    // `implementation`; note that sdk-kotlin already re-exports both in its own module metadata,
    // so this is bookkeeping rather than isolation.
    api(libs.turnkey.sdk.kotlin)
    implementation(libs.turnkey.http)
    implementation(libs.turnkey.types)
    // `crypto` reaches the compile classpath through sdk-kotlin as well. The adapter's key export
    // imports it directly, so it is named here for the same bookkeeping reason.
    implementation(libs.turnkey.crypto)
    // `passkey` and `stamper` reach the compile classpath through sdk-kotlin too. The adapter runs
    // the passkey ceremony and matches their error types directly, so both are named here.
    implementation(libs.turnkey.passkey)
    implementation(libs.turnkey.stamper)

    // ed25519 public-key derivation for the exported Solana keypair, the vendor's own method. The
    // same bcprov-jdk15to18 build and 1.84 floor are already on every consumer's runtime classpath
    // through core and the vendor; the bcprov-jdk18on exclusion above is unchanged.
    implementation(libs.bouncycastle.bcprov)

    // Referenced only by the error mapping, for typed checks on the Credential Manager exceptions a
    // failed passkey ceremony carries. The vendor's passkey package already puts this same version on
    // every consumer's runtime classpath; declaring it here only brings the types to compile time.
    implementation(libs.androidx.credentials)

    // `Flow` appears in this module's public signatures (session and auth state), so the coroutines library is
    // part of its contract with hosts.
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.timber)
    implementation(libs.okhttp)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.truth)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.json)
    testImplementation(libs.kotlinx.coroutines.test)
    // Already on the test runtime classpath via mockk; declared so reflection compiles cleanly.
    testImplementation(libs.kotlin.reflect)
}

mavenPublishing {
    coordinates("xyz.rain", "rain-turnkey-android", libs.versions.rain.sdk.get())

    pom {
        name.set("Rain SDK Android — Turnkey adapter")
        description.set("Turnkey embedded-wallet provider for the Rain Android SDK")
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
