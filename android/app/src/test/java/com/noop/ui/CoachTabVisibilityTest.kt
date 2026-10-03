package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import android.content.Context
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Coach is a separate orb; its settings never change the four primary destinations. */
class CoachTabVisibilityTest {
    @Test
    fun `coach settings never change primary navigation`() {
        assertEquals(barTrailingTabs, barTrailingTabsFor(coachEnabled = true))
        assertEquals(barTrailingTabs, barTrailingTabsFor(coachEnabled = false))
        assertFalse(barTrailingTabs.any { it.dest == Destination.Coach })
        assertEquals(listOf(Destination.Plan), barTrailingTabs.map { it.dest })
    }
}

/**
 * The stored default, which is the invariant that actually matters on upgrade.
 *
 * Separate from the filter tests because it needs a Context. The first version of this asserted
 * `BottomBarStyleStore.coachEnabled`, which is an in-memory singleton seeded to true -- that passes
 * whatever the PREF does, so it would not have noticed the one mistake worth catching here: a pref
 * default of false, which silently removes the Coach tab from every existing install on upgrade.
 */
@RunWith(RobolectricTestRunner::class)
class CoachEnabledPrefDefaultTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun `an install that never touched the toggle has coach enabled`() {
        NoopPrefs.of(context).edit().remove(NoopPrefs.KEY_COACH_ENABLED).commit()
        assertTrue("unset must read as ON, or upgrades lose the tab", NoopPrefs.coachEnabled(context))
    }

    @Test
    fun `the stored value round-trips in both directions`() {
        NoopPrefs.setCoachEnabled(context, false)
        assertFalse(NoopPrefs.coachEnabled(context))
        NoopPrefs.setCoachEnabled(context, true)
        assertTrue(NoopPrefs.coachEnabled(context))
    }
}
