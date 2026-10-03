package com.noop

/** Process-only demo policy; never changes radio state or persisted user settings. */
class DemoRuntimePolicy(demoEnabled: Boolean) {
    val allowsBluetooth = !demoEnabled

    fun <T> createBluetooth(factory: () -> T): T? =
        if (allowsBluetooth) factory() else null

    fun runBluetooth(action: () -> Unit) {
        if (allowsBluetooth) action()
    }

    companion object {
        val current = DemoRuntimePolicy(BuildConfig.ENABLE_DEMO)
    }
}
