package com.noop.testcentre

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class TestReportFlowOutputTest {
    private val entries = listOf("report.txt" to "reviewed report".toByteArray())

    @Test fun nullOutputCannotAnnounceSuccessOrCopyReport() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val gate = ReportReviewGate(entries).apply { confirm() }
        var outputs = 0
        var effects = 0
        TestReportFlow.run(context, TestDomain.MASTER, "report", "1", "Android", "34", gate, entries,
            output = { _, _ -> outputs++; null }, saved = { effects++ }, copy = { effects++ })
        assertEquals(1, outputs)
        assertEquals(0, effects)
    }

    @Test fun cancelledOutputPropagatesWithoutSuccessOrCopy() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val gate = ReportReviewGate(entries).apply { confirm() }
        var effects = 0
        val cancellation = CancellationException("output cancelled")
        val error = runCatching {
            TestReportFlow.run(context, TestDomain.MASTER, "report", "1", "Android", "34", gate, entries,
                output = { _, _ -> throw cancellation }, saved = { effects++ }, copy = { effects++ })
        }.exceptionOrNull()
        assertSame(cancellation, error)
        assertEquals(0, effects)
    }

    @Test fun successfulOutputPrecedesFeedbackAndCopiesExactReviewedReport() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val gate = ReportReviewGate(entries).apply { confirm() }
        val events = arrayListOf<String>()
        TestReportFlow.run(context, TestDomain.MASTER, "report", "1", "Android", "34", gate, entries,
            output = { reviewed, _ ->
                assertSame(entries, reviewed)
                events.add("output")
                File(context.cacheDir, "actual-report.zip")
            }, saved = { events.add("saved:$it") }, copy = { events.add("copy:$it") })
        assertEquals(listOf("output", "saved:actual-report.zip", "copy:reviewed report"), events)
    }

    @Test fun unclearedReviewCannotReachOutputOrFeedback() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var effects = 0
        TestReportFlow.run(context, TestDomain.MASTER, "report", "1", "Android", "34",
            ReportReviewGate(entries), entries, output = { _, _ -> effects++; null },
            saved = { effects++ }, copy = { effects++ })
        assertEquals(0, effects)
    }
}
