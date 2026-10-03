package com.noop.ui

import android.content.ActivityNotFoundException
import androidx.test.platform.app.InstrumentationRegistry
import com.noop.ble.WhoopBleClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class LogExportBundleLifecycleTest {
    private val entries = listOf("report.txt" to "reviewed report".toByteArray())

    @Test fun successfulSharesRetainDistinctReadableArtifactsForTheConsumer() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val shared = arrayListOf<File>()
        val first = requireNotNull(LogExport.exportBundle(context, entries, "report.zip", share = { shared.add(it) }))
        val second = requireNotNull(LogExport.exportBundle(context,
            listOf("report.txt" to "next report".toByteArray()), "report.zip", share = { shared.add(it) }))
        try {
            assertEquals(listOf(first, second), shared)
            assertNotEquals(first.parentFile, second.parentFile)
            assertNotEquals(first.toURI(), second.toURI())
            assertEquals("report.zip", first.name)
            ZipFile(first).use { zip ->
                assertEquals("reviewed report", zip.getInputStream(zip.getEntry("report.txt")).reader().readText())
            }
            ZipFile(second).use { zip ->
                assertEquals("next report", zip.getInputStream(zip.getEntry("report.txt")).reader().readText())
            }
        } finally {
            first.delete(); first.parentFile?.delete()
            second.delete(); second.parentFile?.delete()
        }
    }

    @Test fun textSharesRetainSameNameSnapshotsAndCleanFailedOutput() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val first = requireNotNull(LogExport.exportReviewedText(context, "first reviewed log".toByteArray(),
            "log.txt", share = {}))
        val second = requireNotNull(LogExport.exportReviewedText(context, "next reviewed log".toByteArray(),
            "log.txt", share = {}))
        var failed: File? = null
        try {
            assertNotEquals(first.toURI(), second.toURI())
            assertEquals("first reviewed log", first.readText())
            assertEquals("next reviewed log", second.readText())
            assertNull(LogExport.exportReviewedText(context, "failed reviewed log".toByteArray(), "log.txt",
                share = { failed = it; throw ActivityNotFoundException("no share activity") },
                failed = { assertTrue(it is ActivityNotFoundException) }))
            assertFalse(requireNotNull(failed).exists())
            assertFalse(requireNotNull(failed).parentFile!!.exists())
            assertEquals("first reviewed log", first.readText())
            assertEquals("next reviewed log", second.readText())
        } finally {
            first.delete(); first.parentFile?.delete()
            second.delete(); second.parentFile?.delete()
        }
    }

    @Test fun partialWriteFailureDeletesOnlyItsPrivateArtifact() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val retained = requireNotNull(LogExport.exportBundle(context, entries, "report.zip", share = {}))
        var partial: File? = null
        var shares = 0
        try {
            val result = LogExport.exportBundle(context, entries, "report.zip", write = { file, _ ->
                partial = file
                file.writeText("partial private report")
                throw IOException("write failed")
            }, share = { shares++ }, failed = { assertTrue(it is IOException) })
            assertNull(result)
            assertEquals(0, shares)
            assertFalse(requireNotNull(partial).exists())
            assertFalse(requireNotNull(partial).parentFile!!.exists())
            assertTrue(retained.exists())
        } finally {
            retained.delete(); retained.parentFile?.delete()
        }
    }

    @Test fun failedChooserLaunchDeletesTheCompletedArtifact() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var staged: File? = null
        val result = LogExport.exportBundle(context, entries, "report.zip", share = { file ->
            staged = file
            ZipFile(file).use { assertNotNull(it.getEntry("report.txt")) }
            throw ActivityNotFoundException("no share activity")
        }, failed = { assertTrue(it is ActivityNotFoundException) })
        assertNull(result)
        assertFalse(requireNotNull(staged).exists())
        assertFalse(requireNotNull(staged).parentFile!!.exists())
    }

    @Test fun cancelledReturnAfterCompletedWriteDeletesArtifactAndNeverShares() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var staged: File? = null
        var shares = 0
        val error = runCatching {
            LogExport.exportBundle(context, entries, "report.zip", write = { file, bytes ->
                staged = file
                file.writeBytes(bytes)
                ZipFile(file).use { assertNotNull(it.getEntry("report.txt")) }
                currentCoroutineContext().cancel()
            }, share = { shares++ })
        }.exceptionOrNull()
        assertTrue(error is CancellationException)
        assertEquals(0, shares)
        assertFalse(requireNotNull(staged).exists())
        assertFalse(requireNotNull(staged).parentFile!!.exists())
    }

    @Test fun actualWritersKeepSameNamePreparationsIndependent() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val raw = File(context.filesDir, WhoopBleClient.WHOOP5_CAPTURE_FILE)
        val log = File(context.filesDir, WhoopBleClient.CAPTURE_LOG_FILE)
        val previousRaw = if (raw.exists()) raw.readBytes() else null
        val previousLog = if (log.exists()) log.readBytes() else null
        val preparations = arrayListOf<File>()
        var text = "first snapshot"
        val writers: List<suspend () -> File?> = listOf(
            { LogExport.writeStrapLogFile(context, text, "same-preparation.txt") },
            { LogExport.writeCaptureFile(context, "same-preparation.jsonl") },
            { LogExport.writeCaptureLogFile(context, "same-preparation.txt") },
        )
        try {
            for (write in writers) {
                text = "first snapshot"
                raw.writeText(text + "\n"); log.writeText(text + "\n")
                val first = requireNotNull(write()).also { preparations.add(it) }
                val firstBytes = first.readBytes()
                text = "next snapshot"
                raw.writeText(text + "\n"); log.writeText(text + "\n")
                val second = requireNotNull(write()).also { preparations.add(it) }
                assertEquals(first.name, second.name)
                assertNotEquals(first.parentFile, second.parentFile)
                assertArrayEquals(firstBytes, first.readBytes())
                assertTrue(first.readText().contains("first snapshot"))
                assertTrue(second.readText().contains("next snapshot"))
                LogExport.reviewDebugFiles(context, listOf("report.txt" to first), first.name,
                    ownedPreparations = listOf(first))
                assertFalse(first.exists())
                assertFalse(first.parentFile!!.exists())
                assertTrue(second.exists())
                DebugExportReview.shared.cancel()
                LogExport.reviewDebugFiles(context, listOf("report.txt" to second), second.name,
                    ownedPreparations = listOf(second))
                DebugExportReview.shared.cancel()
                assertFalse(second.exists())
                assertEquals(text + "\n", raw.readText())
                assertEquals(text + "\n", log.readText())
            }
        } finally {
            for (file in preparations) { file.delete(); file.parentFile?.delete() }
            if (previousRaw == null) raw.delete() else raw.writeBytes(previousRaw)
            if (previousLog == null) log.delete() else log.writeBytes(previousLog)
            DebugExportReview.shared.cancel()
            StrapLogBuffer.clear()
        }
    }

    @Test fun missingOldProducerReadCannotRemoveNewerPreparationOrBorrowedSource() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.filesDir, WhoopBleClient.CAPTURE_LOG_FILE)
        val previous = if (source.exists()) source.readBytes() else null
        val preparations = arrayListOf<File>()
        try {
            source.writeText("first snapshot\n")
            val first = requireNotNull(LogExport.writeCaptureLogFile(context, "same-preparation.txt"))
                .also { preparations.add(it) }
            val originalBytes = first.readBytes()
            val entered = CompletableDeferred<Unit>()
            val resume = CompletableDeferred<Unit>()
            var failure: Throwable? = null
            val old = launch {
                failure = runCatching {
                    LogExport.reviewDebugFiles(context, listOf("report.txt" to first), first.name, read = {
                        entered.complete(Unit)
                        resume.await()
                        listOf("report.txt" to first.readBytes())
                    }, ownedPreparations = listOf(first))
                }.exceptionOrNull()
            }
            withTimeout(5_000) { entered.await() }
            val latestTicket = DebugExportReview.shared.beginPreparation()
            source.writeText("latest snapshot\n")
            val latest = requireNotNull(LogExport.writeCaptureLogFile(context, first.name))
                .also { preparations.add(it) }
            val latestBytes = latest.readBytes()
            assertArrayEquals(originalBytes, first.readBytes())
            first.delete()
            resume.complete(Unit)
            withTimeout(5_000) { old.join() }
            assertTrue(failure is IOException)
            assertFalse(first.parentFile!!.exists())
            assertArrayEquals(latestBytes, latest.readBytes())
            assertNull(DebugExportReview.shared.pending)
            LogExport.reviewDebugFiles(context, listOf("report.txt" to latest), latest.name,
                ticket = latestTicket, ownedPreparations = listOf(latest))
            val pending = requireNotNull(DebugExportReview.shared.pending)
            var output: ByteArray? = null
            DebugExportReview.shared.confirm(pending.id) { output = it.single().second }
            assertArrayEquals(latestBytes, output)
            assertFalse(latest.exists())
            assertEquals("latest snapshot\n", source.readText())
            LogExport.reviewDebugFiles(context, listOf("report.txt" to source), "borrowed-source.txt")
            DebugExportReview.shared.cancel()
            assertEquals("latest snapshot\n", source.readText())
        } finally {
            for (file in preparations) { file.delete(); file.parentFile?.delete() }
            if (previous == null) source.delete() else source.writeBytes(previous)
            DebugExportReview.shared.cancel()
        }
    }

    @Test fun cancelledReadDeletesOnlyItsOwnedPreparation() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.filesDir, WhoopBleClient.WHOOP5_CAPTURE_FILE)
        val previous = if (source.exists()) source.readBytes() else null
        val preparations = arrayListOf<File>()
        try {
            source.writeText("raw snapshot\n")
            val cancelledFile = requireNotNull(LogExport.writeCaptureFile(context, "same-preparation.jsonl"))
                .also { preparations.add(it) }
            val retained = requireNotNull(LogExport.writeCaptureFile(context, cancelledFile.name))
                .also { preparations.add(it) }
            val retainedBytes = retained.readBytes()
            val entered = CompletableDeferred<Unit>()
            val resume = CompletableDeferred<Unit>()
            val job = launch {
                LogExport.reviewDebugFiles(context, listOf("raw-capture.jsonl" to cancelledFile), cancelledFile.name,
                    read = { entered.complete(Unit); resume.await(); emptyList() },
                    ownedPreparations = listOf(cancelledFile))
            }
            withTimeout(5_000) { entered.await() }
            job.cancelAndJoin()
            assertFalse(cancelledFile.exists())
            assertFalse(cancelledFile.parentFile!!.exists())
            assertArrayEquals(retainedBytes, retained.readBytes())
            assertEquals("raw snapshot\n", source.readText())
            assertNull(DebugExportReview.shared.pending)
        } finally {
            for (file in preparations) { file.delete(); file.parentFile?.delete() }
            if (previous == null) source.delete() else source.writeBytes(previous)
            DebugExportReview.shared.cancel()
        }
    }

    @Test fun actualWriterFailureRemovesPartialPreparationAndPreservesSource() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.filesDir, WhoopBleClient.CAPTURE_LOG_FILE)
        val rotated = File(context.filesDir, "${WhoopBleClient.CAPTURE_LOG_FILE}.1")
        val previous = if (source.exists()) source.readBytes() else null
        val previousRotated = if (rotated.exists()) rotated.readBytes() else null
        rotated.writeText("private earlier capture\n")
        val rotatedBytes = rotated.readBytes()
        source.delete()
        source.mkdirs()
        val original = File(source, "private-source.json").apply { writeText("private original metadata") }
        val originalBytes = original.readBytes()
        val logs = File(context.cacheDir, "logs").apply { mkdirs() }
        val before = logs.listFiles().orEmpty().map { it.name }.toSet()
        try {
            val error = runCatching { LogExport.writeCaptureLogFile(context, "partial-preparation.txt") }.exceptionOrNull()
            assertTrue(error is IOException)
            assertEquals(before, logs.listFiles().orEmpty().map { it.name }.toSet())
            assertArrayEquals(originalBytes, original.readBytes())
            assertArrayEquals(rotatedBytes, rotated.readBytes())
        } finally {
            original.delete()
            source.delete()
            if (previous != null) source.writeBytes(previous)
            if (previousRotated == null) rotated.delete() else rotated.writeBytes(previousRotated)
        }
    }
}
