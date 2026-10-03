package com.noop.ui

import android.content.ActivityNotFoundException
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
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
                share = { failed = it; throw ActivityNotFoundException("no share activity") }))
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
            }, share = { shares++ })
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
        })
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
}
