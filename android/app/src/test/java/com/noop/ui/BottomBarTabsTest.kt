package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The four-tab capsule stays disjoint from More's feature index and keeps stable labels. */
class BottomBarTabsTest {

    private val barTabs = barLeadingTabs + barTrailingTabs

    /** The bar and the More sheet must be disjoint, which is the invariant the drawer's own note claims. */
    @Test
    fun noBarTabIsAlsoListedInTheMoreSheet() {
        val inDrawer = drawerGroups.flatMap { it.items }.toSet()
        val both = barTabs.map { it.dest }.filter { it in inDrawer }
        assertTrue("a bar tab must not also appear in the More sheet, found $both", both.isEmpty())
    }

    /** Matching iOS: Home, Health, Plan, then More, which the bar appends itself. */
    @Test
    fun theCapsuleCarriesTheSameFourNamedTabsAsIOS() {
        assertEquals(
            listOf(Destination.Today, Destination.Health, Destination.Plan, Destination.More),
            barTabs.map { it.dest } + Destination.More,
        )
    }

    /** A destination cannot occupy two slots; the More slot's selected state derives from this list. */
    @Test
    fun theBarHasNoDuplicateDestinations() {
        assertEquals(barTabs.map { it.dest }.distinct().size, barTabs.size)
    }

    /** Every tab carries a label, so no slot can render an empty word or an empty a11y description. */
    @Test
    fun everyTabHasALabelResource() {
        assertTrue(barTabs.all { it.labelRes != 0 })
    }
}
