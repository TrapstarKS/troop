package com.noop.analytics

import org.junit.Assert.assertEquals
import org.junit.Test

class PhysiologicalStepsClassificationOracleTest {
    private fun describe(blocks: List<PhysiologicalSteps.SleepBlock>, offset: Long = 0, habitual: Long? = null): String {
        val selected = PhysiologicalSteps.classifyForCycle(blocks, offset, habitual)
        val kinds = selected.joinToString("") { if (it.kind == PhysiologicalSteps.SleepKind.MAIN_SLEEP) "M" else "N" }
        return "$kinds:${PhysiologicalSteps.mainSleepOnset(blocks, offset, habitual) ?: "nil"}"
    }

    @Test fun classificationMatchesStandaloneSwiftOracle() {
        val rows = mutableListOf<String>()
        for (offset in listOf(-43200L, 0L, 19800L, 50400L)) {
            for (hour in 0 until 24) {
                val onset = 172800L + hour * 3600 - offset
                rows += describe(listOf(PhysiologicalSteps.SleepBlock(onset, onset + 10800)), offset)
            }
        }
        for (length in listOf(10799L,10800L,10801L)) rows += describe(listOf(PhysiologicalSteps.SleepBlock(244800,244800 + length)))
        for (gap in listOf(0L,3599L,3600L,5399L,5400L,7200L)) {
            rows += describe(listOf(PhysiologicalSteps.SleepBlock(252000,257400),PhysiologicalSteps.SleepBlock(257400 + gap,262800 + gap)))
        }
        rows += describe(listOf(PhysiologicalSteps.SleepBlock(219600,241200,kind = PhysiologicalSteps.SleepKind.NAP)))
        rows += describe(listOf(PhysiologicalSteps.SleepBlock(176400,189000),PhysiologicalSteps.SleepBlock(219600,241200)))
        rows += describe(listOf(PhysiologicalSteps.SleepBlock(176400,189000),PhysiologicalSteps.SleepBlock(219600,241200,kind = PhysiologicalSteps.SleepKind.NAP)))
        rows += describe(listOf(PhysiologicalSteps.SleepBlock(176400,189000,kind = PhysiologicalSteps.SleepKind.MAIN_SLEEP),PhysiologicalSteps.SleepBlock(219600,244800,kind = PhysiologicalSteps.SleepKind.MAIN_SLEEP)))
        rows += describe(listOf(PhysiologicalSteps.SleepBlock(176400,189000,kind = PhysiologicalSteps.SleepKind.MAIN_SLEEP),PhysiologicalSteps.SleepBlock(219600,244800,kind = PhysiologicalSteps.SleepKind.MAIN_SLEEP)),0,232200L % 86400)
        rows += describe(listOf(PhysiologicalSteps.SleepBlock(219600,230400,editedOnset = 219601)))
        // Verbatim stdout from swiftc -O PhysiologicalSteps.swift SleepStageTotals.swift main.swift.
        val expected = """
            M:216000
            M:219600
            M:223200
            M:226800
            M:230400
            M:234000
            M:237600
            M:241200
            M:244800
            M:248400
            M:252000
            M:255600
            M:259200
            M:262800
            M:266400
            M:270000
            M:273600
            M:277200
            M:280800
            M:284400
            M:288000
            M:291600
            M:295200
            M:298800
            M:172800
            M:176400
            M:180000
            M:183600
            M:187200
            M:190800
            M:194400
            M:198000
            M:201600
            M:205200
            M:208800
            M:212400
            M:216000
            M:219600
            M:223200
            M:226800
            M:230400
            M:234000
            M:237600
            M:241200
            M:244800
            M:248400
            M:252000
            M:255600
            M:153000
            M:156600
            M:160200
            M:163800
            M:167400
            M:171000
            M:174600
            M:178200
            M:181800
            M:185400
            M:189000
            M:192600
            M:196200
            M:199800
            M:203400
            M:207000
            M:210600
            M:214200
            M:217800
            M:221400
            M:225000
            M:228600
            M:232200
            M:235800
            M:122400
            M:126000
            M:129600
            M:133200
            M:136800
            M:140400
            M:144000
            M:147600
            M:151200
            M:154800
            M:158400
            M:162000
            M:165600
            M:169200
            M:172800
            M:176400
            M:180000
            M:183600
            M:187200
            M:190800
            M:194400
            M:198000
            M:201600
            M:205200
            N:nil
            M:244800
            M:244800
            MM:252000
            MM:252000
            MM:252000
            MM:252000
            NN:nil
            NN:nil
            N:nil
            NM:219600
            MN:176400
            MM:219600
            MM:219600
            N:nil
        """.trimIndent()
        assertEquals(expected, rows.joinToString("\n"))
    }
}
