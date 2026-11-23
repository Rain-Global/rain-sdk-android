# Consumer R8/ProGuard rules for rain-privy-android — merged into any host app that minifies.
#
# PrivyWalletProvider decodes ABI values via web3j TypeReference<T> (getGenericSuperclass() +
# reflective datatype constructors). See rain-core-android/consumer-rules.pro for the full
# rationale; the rules are duplicated here so this module is safe standalone.
-keepattributes Signature
-keep class org.web3j.abi.datatypes.** { <init>(...); }
-keep,allowobfuscation,allowshrinking class * extends org.web3j.abi.TypeReference
-keep,allowobfuscation,allowshrinking class org.web3j.abi.TypeReference
