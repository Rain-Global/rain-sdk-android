# Consumer R8/ProGuard rules for rain-core-android — merged into any host app that minifies.
#
# web3j decodes ABI values reflectively: a TypeReference<T> subclass is resolved via
# getGenericSuperclass() (requires the Signature attribute) and the target datatype is
# instantiated through Class.getDeclaredConstructor(...) (requires the datatype classes
# and their constructors to survive shrinking). The SDK only constructs these types
# directly on encode paths, so a host that only reads (balances/allowances) has no code
# reference to them — without these rules R8 strips them and decoding crashes in
# TypeReference.getClassType().
#
# The TypeReference subclass rule keeps only METADATA: in R8 full mode, -keepattributes
# applies solely to classes matched by a keep rule, so without it the anonymous
# `object : TypeReference<T>() {}` subclasses lose their generic signature (verified in
# dexdump: no dalvik.annotation.Signature) and getClassType() throws before the datatype
# rule even matters. allowobfuscation/allowshrinking make it free: R8 may still rename
# and drop unused subclasses. Same pattern as Gson's shipped TypeToken rules, which keep the
# base class as well as its subclasses: without the base-class rule, R8 full mode still erases
# the type argument and building an ABI function throws "Missing type parameter".
-keepattributes Signature
-keep class org.web3j.abi.datatypes.** { <init>(...); }
-keep,allowobfuscation,allowshrinking class * extends org.web3j.abi.TypeReference
-keep,allowobfuscation,allowshrinking class org.web3j.abi.TypeReference

# web3j builds and parses its JSON-RPC classes with Jackson, through getters and setters that R8
# can't see being used. Without these rules a shrunk host app sends a broken eth_call:
# getLatestNonce fails and isCollateralAdmin returns null, so the admin check is skipped.
-keep class org.web3j.protocol.core.Request { *; }
-keep class org.web3j.protocol.core.Response { *; }
-keep class org.web3j.protocol.core.Response$Error { *; }
-keep class org.web3j.protocol.core.methods.request.Transaction { *; }
-keep class org.web3j.protocol.core.methods.response.EthCall { *; }
-keep class org.web3j.protocol.core.DefaultBlockParameterName { *; }
-keep class org.web3j.protocol.core.DefaultBlockParameter { *; }
# Response$Error.data names this deserializer in @JsonDeserialize(using = ...), and Jackson creates it
# through its no-argument constructor. Without this rule R8 drops that constructor, and an RPC error
# whose data is an object no longer parses. The misspelled class name is web3j's own.
-keep class org.web3j.protocol.deserializer.KeepAsJsonDeserialzier { <init>(); }

# slf4j-api, pulled in by web3j, looks up a logging binder class that no Android app ships.
# Without this line R8 stops a host's release build with "Missing class org.slf4j.impl.StaticLoggerBinder".
-dontwarn org.slf4j.impl.StaticLoggerBinder
