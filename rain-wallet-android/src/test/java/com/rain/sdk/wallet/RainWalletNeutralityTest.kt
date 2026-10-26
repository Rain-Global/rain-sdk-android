package com.rain.sdk.wallet

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.rain.sdk.turnkey.TurnkeyProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Test
import kotlin.reflect.KCallable
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.KVisibility
import kotlin.reflect.full.declaredMembers
import kotlin.reflect.jvm.javaType
import kotlin.reflect.jvm.jvmErasure

/**
 * The Rain wallet's public surface names no wallet-vendor type. `checkVendorFreeClasspaths` proves
 * the vendor is off a host's compile classpath; this test proves that nothing on the surface would
 * need it there: every public constructor's and member's parameter and return types, type
 * arguments included, come from the SDK's own packages, the Kotlin and Java standard libraries,
 * coroutines or `android.app`. A vendor type reaching the surface fails here, and the failure names
 * the member, without an edit to the allowlist.
 */
class RainWalletNeutralityTest {

    @Test
    fun `the provider's public surface names only neutral types`() {
        assertNeutral(RainProvider::class)
    }

    @Test
    fun `the value types' public surfaces name only neutral types`() {
        listOf(
            RainWalletConfig::class,
            RainWalletEnvironment::class,
            RainWalletContact::class,
            RainWalletSessionPolicy::class,
            RainWalletKeyAccount::class,
            RainWalletSessionState::class,
            RainWalletAuthState::class,
        ).forEach(::assertNeutral)
    }

    @Test
    fun `the walker names a vendor type in a parameter, a type argument and a supertype`() {
        assertThat(offendersOf(Leaky::class)).containsExactly(
            "com.rain.sdk.wallet.RainWalletNeutralityTest.Leaky.leak: com.rain.sdk.turnkey.TurnkeyProvider",
            "com.rain.sdk.wallet.RainWalletNeutralityTest.Leaky.flow: com.rain.sdk.turnkey.TurnkeyProvider",
            "com.rain.sdk.wallet.RainWalletNeutralityTest.Leaky.compareTo: com.rain.sdk.turnkey.TurnkeyKeyFamily",
            "com.rain.sdk.wallet.RainWalletNeutralityTest.Leaky <: com.rain.sdk.turnkey.TurnkeyKeyFamily",
        )
    }

    /** A deliberately leaky surface, so the walker is known to bite: one parameter, one type argument, one supertype. */
    @Suppress("unused")
    private class Leaky : Comparable<com.rain.sdk.turnkey.TurnkeyKeyFamily> {
        fun leak(backing: TurnkeyProvider) = Unit
        val flow: Flow<TurnkeyProvider> = emptyFlow()
        override fun compareTo(other: com.rain.sdk.turnkey.TurnkeyKeyFamily): Int = 0
    }

    private fun assertNeutral(root: KClass<*>) {
        assertWithMessage("types outside the allowlist on the Rain wallet surface").that(offendersOf(root)).isEmpty()
    }

    /** Every type outside the allowlist named by a public constructor, member or supertype, as `owner.member: type`. */
    private fun offendersOf(root: KClass<*>): List<String> {
        val offenders = mutableListOf<String>()
        for (owner in root.publicClosure()) {
            owner.supertypes.flatMap { it.erasures() }
                .filterNot { it.isAllowed() }
                .forEach { offenders += "${owner.qualifiedName} <: $it" }
            val callables: List<KCallable<*>> = owner.constructors + owner.declaredMembers
            callables.filter { it.visibility == KVisibility.PUBLIC }.forEach { callable ->
                val named = callable.parameters.map { it.type } + callable.returnType
                named.flatMap { it.erasures() }
                    .filterNot { it.isAllowed() }
                    .forEach { offenders += "${owner.qualifiedName}.${callable.name}: $it" }
            }
        }
        return offenders
    }

    /** The class and every public nested class, so a sealed hierarchy's cases are covered too. */
    private fun KClass<*>.publicClosure(): List<KClass<*>> =
        listOf(this) + nestedClasses.filter { it.visibility == KVisibility.PUBLIC }.flatMap { it.publicClosure() }

    /**
     * The raw class behind a type and behind each of its type arguments, as qualified names. A
     * `suspend` function type has no Kotlin class for reflection to erase to (its classifier is the
     * synthetic `kotlin.coroutines.SuspendFunctionN`), so it is named by the raw class of its JVM
     * signature instead, `kotlin.jvm.functions.FunctionN`; its arguments are walked like any other.
     */
    private fun KType.erasures(): List<String> {
        val own = runCatching { jvmErasure.qualifiedName.orEmpty() }.getOrElse { javaType.typeName.substringBefore('<') }
        return listOf(own) + arguments.mapNotNull { it.type }.flatMap { it.erasures() }
    }

    private fun String.isAllowed(): Boolean = ALLOWED_PACKAGES.any { this == it || startsWith("$it.") }

    private companion object {
        val ALLOWED_PACKAGES = listOf(
            "com.rain.sdk.wallet",
            "com.rain.sdk.provider",
            "com.rain.sdk.error",
            "kotlin",
            "kotlinx.coroutines",
            "android.app",
            "java",
        )
    }
}
