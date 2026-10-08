package com.noop.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StressMonitorReadingSurfaceTest {
    private fun source(path: String): String {
        val userDir = File(System.getProperty("user.dir") ?: ".")
        val root = listOf(userDir, File(userDir, ".."), File(userDir, "../..")).firstOrNull {
            File(it, "android/app/src/main/java/com/noop/ui/StressMonitorScreen.kt").isFile
        } ?: error("could not locate the repo root from ${userDir.absolutePath}")
        return File(root, "android/app/src/main/java/com/noop/$path").readText()
    }

    @Test fun todayAndDetailShareTheScoredResultAndReadinessResolver() {
        val detail = source("ui/StressMonitorScreen.kt")
        val today = source("ui/TodayScreen.kt")
        val card = source("ui/StressScreen.kt").substringAfter("internal fun StressTodayCard(")
        assertTrue("today's detail must read the same producer result as its hosted curve",
            detail.contains("StressWidgetProducer.todayCurve(") && detail.contains("curve?.daytime ?: DaytimeStress.analyze("))
        assertTrue("detail must resolve recorded readiness with its one display clock",
            Regex("monitorReading\\([\\s\\S]*?now = nowSeconds,").containsMatchIn(detail))
        assertTrue("Today must resolve readiness from the curve's observation bounds and clock",
            today.contains("currentStressCurve?.daytime?.monitorReading(") && today.contains("now = stressNowSeconds,"))
        assertTrue("Today must reset its recorded curve when the active strap changes",
            today.contains("stressCurve by remember(viewModel.activeStrapId)"))
        assertTrue("Today must reset its persisted seed when the active strap changes",
            today.contains("stressSeed by remember(viewModel.activeStrapId)"))
        assertFalse("freshness cannot remove the latest scored window", detail.contains("latest.takeUnless"))
        assertFalse("the card cannot infer data readiness from the wall-clock hour", card.contains("LocalTime.now()"))
        assertTrue("the card must display the same resolved readiness as detail", card.contains("stressMonitorReadingStatus(reading)"))
    }

    @Test fun producerMemoIncludesEveryInputThatCanChangeItsCurve() {
        val producer = source("widget/StressWidgetProducer.kt")
        assertTrue("unioned HR, PPG, R-R and gravity must invalidate the memo", producer.contains("repo.stressFingerprintUnion("))
        for (identity in listOf("it.deviceId == deviceId", "it.zoneId == zone.id", "it.mode == mode")) {
            assertTrue("memo omitted $identity", producer.contains(identity))
        }
        assertTrue("a changed historical personal baseline must be resolved before memo reuse",
            producer.indexOf("val mode = selectedDaytimeStressMode(") < producer.indexOf("val memoHit ="))
    }
}
