pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // Auto-provisions the JDK 24 unit-test toolchain when it is not installed locally.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

// CodeQL's default setup restores the Gradle home from its dependency cache, the local build cache
// included, so a compile task answered from the cache leaves its Kotlin extractor no compiler run to
// trace and the check fails with "could not process any code". The action exports CODEQL_ACTION_*
// into the job; under it the compilers run for real.
buildCache {
    local {
        isEnabled = System.getenv().keys.none { it.startsWith("CODEQL_ACTION_") }
    }
}

rootProject.name = "rain-sdk-android"
include(":rain-core-android")
include(":rain-turnkey-android")
include(":rain-portal-android")
include(":rain-privy-android")
include(":rain-wallet-android")
include(":app")
