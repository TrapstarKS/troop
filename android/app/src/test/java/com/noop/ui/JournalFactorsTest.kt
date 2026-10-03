package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JournalFactorsTest {
    @Test
    fun canonicalMetadataMatchesSwiftOracle() {
        assertEquals(CANONICAL_METADATA, JournalFactor.all.joinToString("\n") {
            "${it.canonical}|${it.groupKey}|${it.unit ?: "-"}"
        })
        assertEquals(JournalFactor.all.size, JournalFactor.all.map { it.canonical }.toSet().size)
    }

    @Test
    fun everyFactorResolvesCaseAndUnicodeWhitespace() {
        for (factor in JournalFactor.all) {
            val imported = "\n\u00a0" + factor.canonical.uppercase().replace(" ", "\t  ") + "\u00a0\n"
            assertEquals(factor.canonical, JournalFactor.find(imported)?.canonical)
            val item = resolveJournalItems(listOf(imported), emptyList()).first()
            assertEquals(factor.groupKey, item.group.name.lowercase())
            assertEquals(factor.unit, item.kind.unitLabel)
            assertEquals(factor.unit != null, item.kind.isNumeric)
        }
        assertNull(JournalFactor.find("My custom habit"))
        assertNull(JournalFactor.find(" \n\t"))
    }

    @Test
    fun renameAndCustomLabelsPreserveRawKeys() {
        val canonical = "DID YOU TAKE  MAGNESIUM?"
        val item = JournalCatalogItem(canonical, displayName = "My supplement")
        assertEquals("My supplement", journalLocalizedLabel(item))
        assertEquals("My supplement", item.display)
        assertEquals(canonical, item.canonical)
        val custom = JournalCatalogItem("My custom habit", custom = true)
        assertEquals("My custom habit", journalLocalizedLabel(custom))
        assertEquals("My custom habit", custom.display)
    }

    @Test
    fun importedCasingAndInternalSpacingWinWhileOuterWhitespaceTrims() {
        val imported = "\n\u00a0DID YOU TAKE  MAGNESIUM?\u00a0\n"
        val resolved = resolveJournalItems(listOf(imported), emptyList())
        val matches = resolved.filter { JournalFactor.find(it.canonical)?.canonical == "Did you take magnesium?" }
        assertEquals(1, matches.size)
        assertEquals("DID YOU TAKE  MAGNESIUM?", matches.single().canonical)
        assertEquals("DID YOU TAKE  MAGNESIUM?", matches.single().display)
        assertEquals(JournalFactor.all.size, resolved.size)

        val renamed = renameJournalItem(emptyList(), matches.single().canonical, "My supplement")
        assertEquals("My supplement", journalLocalizedLabel(resolveJournalItems(listOf(imported), renamed).first()))
        val cleared = renameJournalItem(renamed, matches.single().canonical, " \n")
        val restored = resolveJournalItems(listOf(imported), cleared).first()
        assertNull(restored.displayName)
        assertEquals("DID YOU TAKE  MAGNESIUM?", restored.canonical)
        assertEquals("DID YOU TAKE  MAGNESIUM?", restored.display)
    }

    companion object {
        // Verbatim stdout of actual JournalFactors.swift + Tools/JournalFactorsOracle/main.swift.
        private val CANONICAL_METADATA = """
            Did you drink any alcohol?|nutrition|-
            Did you have caffeine late in the day?|nutrition|-
            Did you view a screen in bed?|lifestyle|-
            Did you eat close to bedtime?|nutrition|-
            Did you feel stressed?|behaviour|-
            Did you use a sauna?|lifestyle|-
            Did you share your bed?|lifestyle|-
            Did you feel sick or ill?|health|-
            Did you take magnesium?|supplements|-
            Did you read before bed?|lifestyle|-
            Did you meditate?|behaviour|-
            Did you do breathing exercises?|behaviour|-
            Did you spend time outdoors?|lifestyle|-
            Did you travel?|lifestyle|-
            Was your bedroom noisy?|environment|-
            Was your bedroom too warm?|environment|-
            Did you use blackout curtains?|environment|-
            Did you sleep at high altitude?|environment|-
            Did you have allergy symptoms?|health|-
            Did you take prescribed medication?|health|-
            Did you take vitamin D?|supplements|-
            How much caffeine did you consume?|nutrition|mg
            How much water did you drink?|nutrition|mL
            How many minutes did you meditate?|behaviour|min
            What was your bedroom temperature?|environment|°C
            How many hours did you work?|lifestyle|h
        """.trimIndent()
    }
}
