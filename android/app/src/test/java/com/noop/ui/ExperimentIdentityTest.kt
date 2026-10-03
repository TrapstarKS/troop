package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ExperimentIdentityTest {
    @Test
    fun storedCanonicalIdentityMatchesVerbatimSwiftOracle() {
        data class Case(val label: String, val candidates: List<String>, val saved: String, val startedDay: String)
        val cases = listOf(
            Case("setupSaved", listOf("Caffeine", "Late meal"), "Late meal", ""),
            Case("setupStale", listOf("Caffeine", "Late meal"), "Removed custom", ""),
            Case("setupEmpty", listOf("Caffeine", "Late meal"), "", ""),
            Case("setupNoCandidates", listOf(), "Removed custom", ""),
            Case("setupNoCandidatesEmpty", listOf(), "", ""),
            Case("setupExactCanonical", listOf("Caffeine", "Late meal"), "CAFFEINE", ""),
            Case("activeEligible", listOf("Caffeine", "Late meal"), "Late meal", "2026-10-03"),
            Case("activeHidden", listOf("Caffeine"), "Late meal", "2026-10-03"),
            Case("activeHiddenNoCandidates", listOf(), "Late meal", "2026-10-03"),
            Case("activeStale", listOf("Caffeine"), "Removed custom", "2026-10-03"),
            Case("activeRenamedDisplay", listOf("Renamed label"), "Late meal", "2026-10-03"),
            Case("activeEmpty", listOf("Caffeine"), "", "2026-10-03"),
            Case("activeWhitespace", listOf("Caffeine"), " \t\r\n", "2026-10-03"),
            Case("activeUnicodeCanonical", listOf("Caffeine"), "Ma\u00f1ana \ud83d\udca4", "2026-10-03"),
            Case("activeTrim-asciiSpace", listOf("Late meal"), " Caffeine ", "2026-10-03"),
            Case("setupTrim-asciiSpace", listOf("Caffeine", "Late meal"), " Caffeine ", ""),
            Case("activeBlank-asciiSpace", listOf("Caffeine"), " ", "2026-10-03"),
            Case("activeTrim-tabCRLF", listOf("Late meal"), "\t\r\nCaffeine\t\r\n", "2026-10-03"),
            Case("setupTrim-tabCRLF", listOf("Caffeine", "Late meal"), "\t\r\nCaffeine\t\r\n", ""),
            Case("activeBlank-tabCRLF", listOf("Caffeine"), "\t\r\n", "2026-10-03"),
            Case("activeTrim-nbsp", listOf("Late meal"), "\u00a0Caffeine\u00a0", "2026-10-03"),
            Case("setupTrim-nbsp", listOf("Caffeine", "Late meal"), "\u00a0Caffeine\u00a0", ""),
            Case("activeBlank-nbsp", listOf("Caffeine"), "\u00a0", "2026-10-03"),
            Case("activeTrim-emSpace", listOf("Late meal"), "\u2003Caffeine\u2003", "2026-10-03"),
            Case("setupTrim-emSpace", listOf("Caffeine", "Late meal"), "\u2003Caffeine\u2003", ""),
            Case("activeBlank-emSpace", listOf("Caffeine"), "\u2003", "2026-10-03"),
            Case("activeTrim-narrowNbsp", listOf("Late meal"), "\u202fCaffeine\u202f", "2026-10-03"),
            Case("setupTrim-narrowNbsp", listOf("Caffeine", "Late meal"), "\u202fCaffeine\u202f", ""),
            Case("activeBlank-narrowNbsp", listOf("Caffeine"), "\u202f", "2026-10-03"),
            Case("activeTrim-ideographic", listOf("Late meal"), "\u3000Caffeine\u3000", "2026-10-03"),
            Case("setupTrim-ideographic", listOf("Caffeine", "Late meal"), "\u3000Caffeine\u3000", ""),
            Case("activeBlank-ideographic", listOf("Caffeine"), "\u3000", "2026-10-03"),
            Case("activeTrim-nel", listOf("Late meal"), "\u0085Caffeine\u0085", "2026-10-03"),
            Case("setupTrim-nel", listOf("Caffeine", "Late meal"), "\u0085Caffeine\u0085", ""),
            Case("activeBlank-nel", listOf("Caffeine"), "\u0085", "2026-10-03"),
            Case("activeTrim-zeroWidth", listOf("Late meal"), "\u200bCaffeine\u200b", "2026-10-03"),
            Case("setupTrim-zeroWidth", listOf("Caffeine", "Late meal"), "\u200bCaffeine\u200b", ""),
            Case("activeBlank-zeroWidth", listOf("Caffeine"), "\u200b", "2026-10-03"),
        )
        val rows = cases.map { case ->
            val value = resolveExperimentBehaviour(case.candidates, case.saved, case.startedDay)
            val encoded = value?.toByteArray(Charsets.UTF_8)?.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') } ?: "nil"
            "${case.label}|$encoded"
        }
        val trimmedScalars = (0..0xffff).filter { it !in 0xd800..0xdfff }.mapNotNull { code ->
            val edge = code.toChar().toString()
            val value = resolveExperimentBehaviour(emptyList(), edge + "Caffeine" + edge, "2026-10-03")
            if (value == "Caffeine") code.toString(16).padStart(4, '0') else null
        }
        val actual = rows.joinToString("\n") + "\ntrimmedBMP|" + trimmedScalars.joinToString(",") + "\n"
        // Executed Swift stdout, including all 63,488 non-surrogate BMP scalars.
        val expected = """
            setupSaved|4c617465206d65616c
            setupStale|4361666665696e65
            setupEmpty|4361666665696e65
            setupNoCandidates|nil
            setupNoCandidatesEmpty|nil
            setupExactCanonical|4361666665696e65
            activeEligible|4c617465206d65616c
            activeHidden|4c617465206d65616c
            activeHiddenNoCandidates|4c617465206d65616c
            activeStale|52656d6f76656420637573746f6d
            activeRenamedDisplay|4c617465206d65616c
            activeEmpty|nil
            activeWhitespace|nil
            activeUnicodeCanonical|4d61c3b1616e6120f09f92a4
            activeTrim-asciiSpace|4361666665696e65
            setupTrim-asciiSpace|4361666665696e65
            activeBlank-asciiSpace|nil
            activeTrim-tabCRLF|4361666665696e65
            setupTrim-tabCRLF|4361666665696e65
            activeBlank-tabCRLF|nil
            activeTrim-nbsp|4361666665696e65
            setupTrim-nbsp|4361666665696e65
            activeBlank-nbsp|nil
            activeTrim-emSpace|4361666665696e65
            setupTrim-emSpace|4361666665696e65
            activeBlank-emSpace|nil
            activeTrim-narrowNbsp|4361666665696e65
            setupTrim-narrowNbsp|4361666665696e65
            activeBlank-narrowNbsp|nil
            activeTrim-ideographic|4361666665696e65
            setupTrim-ideographic|4361666665696e65
            activeBlank-ideographic|nil
            activeTrim-nel|4361666665696e65
            setupTrim-nel|4361666665696e65
            activeBlank-nel|nil
            activeTrim-zeroWidth|4361666665696e65
            setupTrim-zeroWidth|4361666665696e65
            activeBlank-zeroWidth|nil
            trimmedBMP|0009,000a,000b,000c,000d,0020,0085,00a0,1680,2000,2001,2002,2003,2004,2005,2006,2007,2008,2009,200a,200b,2028,2029,202f,205f,3000
        """.trimIndent() + "\n"
        assertEquals(expected, actual)
    }
}
