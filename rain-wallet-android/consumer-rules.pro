# Consumer R8/ProGuard rules for rain-wallet-android — merged into any host app that minifies.
#
# Nothing to keep here. This module has no reflective code of its own. The rules a host needs come
# from the modules on its runtime classpath: rain-core-android carries the web3j keep rules and the
# wallet backend adapter carries its own. AGP collects consumer rules from the app's runtime
# classpath, and both modules are on it for every consumer of this one.
