package com.noop.ui

import com.noop.testcentre.ReportReviewGate
import com.noop.testcentre.TestDomain
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class PendingReportReviewTest {
    private fun report(text: String): PendingReport {
        val entries = listOf("report.txt" to text.toByteArray())
        return PendingReport(TestDomain.MASTER, text, entries, ReportReviewGate(entries))
    }

    @Test fun olderBuilderCannotReplaceOrConfirmNewerReport() = runBlocking {
        val reviews = PendingReportReview()
        val old = report("old")
        val latest = report("latest")
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val job = launch {
            reviews.prepare { entered.complete(Unit); resume.await(); old }
        }
        withTimeout(5_000) { entered.await() }
        reviews.prepare { latest }
        resume.complete(Unit)
        withTimeout(5_000) { job.join() }
        assertSame(latest, reviews.pending)
        assertFalse(reviews.take(old))
        assertTrue(reviews.take(latest))
        assertFalse(reviews.take(latest))
        assertNull(reviews.pending)
    }

    @Test fun cancellationDuringBuilderPreventsLaterPublication() = runBlocking {
        val reviews = PendingReportReview()
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val old = report("old")
        val job = launch {
            reviews.prepare { entered.complete(Unit); resume.await(); old }
        }
        withTimeout(5_000) { entered.await() }
        reviews.cancel()
        resume.complete(Unit)
        withTimeout(5_000) { job.join() }
        assertNull(reviews.pending)
        assertFalse(reviews.take(old))
    }

    @Test fun alreadyCancelledProducerCannotClearCurrentReport() = runBlocking {
        val reviews = PendingReportReview()
        val latest = report("latest")
        reviews.prepare { latest }
        val entered = CompletableDeferred<Unit>()
        var resume: kotlin.coroutines.Continuation<Unit>? = null
        var builds = 0
        var attempted = false
        val job = launch {
            kotlin.coroutines.suspendCoroutine<Unit> { resume = it; entered.complete(Unit) }
            attempted = true
            val error = runCatching { reviews.prepare { builds++; report("cancelled") } }.exceptionOrNull()
            assertTrue(error is CancellationException)
        }
        withTimeout(5_000) { entered.await() }
        job.cancel()
        requireNotNull(resume).resumeWith(Result.success(Unit))
        withTimeout(5_000) { job.join() }
        assertTrue(attempted)
        assertEquals(0, builds)
        assertSame(latest, reviews.pending)
    }
}
