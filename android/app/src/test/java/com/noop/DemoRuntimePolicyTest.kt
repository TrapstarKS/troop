package com.noop

import org.junit.Assert.*
import org.junit.Test

class DemoRuntimePolicyTest {
    @Test fun demoRejectsResourceCreationAndActions() {
        val policy = DemoRuntimePolicy(true)
        var calls = 0
        assertFalse(policy.allowsBluetooth)
        assertNull(policy.createBluetooth { calls++; Any() })
        policy.runBluetooth { calls++ }
        assertEquals(0, calls)
    }

    @Test fun fullBuildPreservesResourceAndActionExecution() {
        val policy = DemoRuntimePolicy(false)
        val resource = Any()
        var calls = 0
        assertTrue(policy.allowsBluetooth)
        assertSame(resource, policy.createBluetooth { calls++; resource })
        policy.runBluetooth { calls++ }
        assertEquals(2, calls)
    }

    @Test fun processPolicyUsesOnlyTheDedicatedBuildFlavor() {
        assertEquals(!BuildConfig.ENABLE_DEMO, DemoRuntimePolicy.current.allowsBluetooth)
    }
}
