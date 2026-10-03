package com.noop.ui

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.noop.ble.WhoopBleClient
import com.noop.data.WhoopDatabase
import com.noop.data.WhoopRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class DebugExportReviewTest {
    @Test fun confirmExportsTheReviewedSnapshotOnce() = runBlocking {
        val review = DebugExportReview()
        val raw = "{\"console\":\"WHOOP 4C1594026 connected\"}\n".repeat(40).toByteArray()
        val exported = arrayListOf<List<Pair<String, ByteArray>>>()
        review.stage(listOf("report.txt" to "WHOOP 4C1594026".toByteArray(), "raw-capture.jsonl" to raw), 256) {
            exported.add(it)
        }
        val pending = requireNotNull(review.pending)
        assertTrue(exported.isEmpty())
        assertTrue(pending.gate.previewText.contains("raw-capture.jsonl"))
        assertFalse(pending.gate.previewText.contains("4C1594026"))
        val snapshot = pending.entries.map { it.first to it.second.copyOf() }
        assertEquals(setOf("report.txt", "raw-capture.jsonl"), snapshot.map { it.first }.toSet())
        assertTrue(snapshot.sumOf { it.second.size } <= 256)
        assertFalse(snapshot.any { String(it.second).contains("4C1594026") })
        raw.fill(0)
        pending.entries.forEach { it.second.fill(0) }
        review.confirm(pending.id)
        review.confirm(pending.id)
        assertEquals(1, exported.size)
        snapshot.zip(exported.single()).forEach { (expected, actual) ->
            assertEquals(expected.first, actual.first)
            assertArrayEquals(expected.second, actual.second)
        }
    }

    @Test fun oversizedTextIsScrubbedAndBounded() = runBlocking {
        val review = DebugExportReview()
        var copied: ByteArray? = null
        review.stage(listOf("report.txt" to "WHOOP 4C1594026\n".repeat(40).toByteArray()), 128) {
            copied = it.single().second
        }
        val pending = requireNotNull(review.pending)
        review.confirm(pending.id)
        assertArrayEquals(pending.entries.single().second, copied)
        assertTrue(requireNotNull(copied).size <= 128)
        assertFalse(String(requireNotNull(copied)).contains("4C1594026"))
    }

    @Test fun cancelAndMissingGateCannotOutput() = runBlocking {
        val review = DebugExportReview()
        var outputs = 0
        review.confirm(123) { outputs++ }
        review.stage(listOf("report.txt" to "log".toByteArray())) { outputs++ }
        val id = requireNotNull(review.pending).id
        review.cancel()
        review.confirm(id) { outputs++ }
        assertEquals(0, outputs)
        assertNull(review.pending)
    }

    @Test fun actualPairIncludesScrubbedRawCaptureBeforeConfirmation() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val capture = File(context.filesDir, WhoopBleClient.WHOOP5_CAPTURE_FILE)
        capture.writeText("{\"console\":\"WHOOP 4C1594026\"}\n")
        try {
            LogExport.shareRawAndLog(context, "strap log", whoop5Connected = true, encryptedBond = false)
            val pending = requireNotNull(DebugExportReview.shared.pending)
            assertFalse(pending.gate.isCleared)
            val raw = pending.entries.first { it.first == "raw-capture.jsonl" }
            assertTrue(String(raw.second).contains("WHOOP <serial>"))
            var output = emptyList<Pair<String, ByteArray>>()
            DebugExportReview.shared.confirm(pending.id) { output = it }
            assertEquals(pending.entries.map { it.first }, output.map { it.first })
            pending.entries.zip(output).forEach { (expected, actual) -> assertArrayEquals(expected.second, actual.second) }
        } finally {
            capture.delete()
            DebugExportReview.shared.cancel()
        }
    }

    @Test fun scheduledPrivateOutputScrubsActualRawCaptureWithoutReview() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val capture = File(context.filesDir, WhoopBleClient.WHOOP5_CAPTURE_FILE)
        capture.writeText("{\"console\":\"WHOOP 4C1594026\"}\n")
        DebugExportReview.shared.cancel()
        try {
            val files = LogExport.writeScheduledExport(context, "WHOOP 4C1594026", 1781683200000L)
            assertEquals(2, files.size)
            assertTrue(files.all { !it.readText().contains("4C1594026") })
            assertNull(DebugExportReview.shared.pending)
            files.forEach { it.delete() }
        } finally {
            capture.delete()
        }
    }
    @Test fun researchPublicShareKeepsSensorBytesAndScrubsMetadata() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "research-review.zip")
        val sensor = "WHOOP 4C1594026,Bearer sensor-token,414141414141414141\n".toByteArray()
        val imu = byteArrayOf(0, -1, -128) + sensor
        val entries = listOf(
            "meta.json" to """{"device_id":"private-strap","comment":"WHOOP 4C1594026 Bearer secret.token"}""".toByteArray(),
            "events.jsonl" to """{"strap_device_id":"private-other","text":"Bearer\tjson-secret"}""".toByteArray(),
            "events.csv" to "text\nBearer csv-secret\n".toByteArray(),
            "raw-sensors.csv" to sensor,
            "imu/frame.bin" to imu,
        )
        java.util.zip.ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, data) in entries) {
                zip.putNextEntry(java.util.zip.ZipEntry(name)); zip.write(data); zip.closeEntry()
            }
        }
        val privateZip = file.readBytes()
        try {
            com.noop.testcentre.GroundTruthCollector.from(context).share(file)
            val pending = requireNotNull(DebugExportReview.shared.pending)
            assertFalse(pending.gate.isCleared)
            assertTrue(pending.gate.previewText.contains("imu/frame.bin"))
            assertFalse(pending.gate.previewText.contains("=== imu/frame.bin ==="))
            assertArrayEquals(sensor, pending.entries.first { it.first == "raw-sensors.csv" }.second)
            assertArrayEquals(imu, pending.entries.first { it.first == "imu/frame.bin" }.second)
            for ((_, data) in pending.entries.filter { it.first in setOf("meta.json", "events.jsonl", "events.csv") }) {
                val text = String(data)
                assertFalse(text.contains("private-strap"))
                assertFalse(text.contains("private-other"))
                assertFalse(text.contains("secret.token"))
                assertFalse(text.contains("csv-secret"))
                assertFalse(text.contains("json-secret"))
                assertFalse(text.contains("4C1594026"))
            }
            var output = emptyList<Pair<String, ByteArray>>()
            val oldId = pending.id
            DebugExportReview.shared.cancel()
            DebugExportReview.shared.confirm(oldId) { output = it }
            assertTrue(output.isEmpty())
            com.noop.testcentre.GroundTruthCollector.from(context).share(file)
            val current = requireNotNull(DebugExportReview.shared.pending)
            DebugExportReview.shared.confirm(current.id) { output = it }
            current.entries.zip(output).forEach { (expected, actual) -> assertArrayEquals(expected.second, actual.second) }
            assertArrayEquals(privateZip, file.readBytes())
        } finally {
            file.delete()
            DebugExportReview.shared.cancel()
        }
    }

    @Test fun oversizedResearchZipAndFixedEntriesFailClosed() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "research-too-large.zip")
        val cap = 20 * 1024 * 1024
        java.util.zip.ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("imu/frame.bin"))
            zip.write(ByteArray(cap + 1))
            zip.closeEntry()
        }
        try {
            DebugExportReview.shared.cancel()
            assertTrue(runCatching { com.noop.testcentre.GroundTruthCollector.from(context).share(file) }.isFailure)
            assertNull(DebugExportReview.shared.pending)
            val entries = listOf("imu/frame.bin" to ByteArray(1025))
            assertTrue(runCatching { DebugExportReview.prepare(entries, 1024) }.isFailure)
            var outputs = 0
            assertTrue(runCatching { DebugExportReview.shared.stageResearch(entries, 1024) { outputs++ } }.isFailure)
            DebugExportReview.shared.confirm(123)
            assertEquals(0, outputs)
            assertNull(DebugExportReview.shared.pending)
        } finally {
            file.delete()
        }
    }

    @Test fun actualSessionExportMarksCompletionOnlyAfterSuccessfulReviewedOutput() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, WhoopDatabase::class.java).build()
        val collector = com.noop.testcentre.GroundTruthCollector.from(context)
        val startedAt = 1_700_000_000_000L
        val id = requireNotNull(collector.start("private-strap", startedAt).sessionId)
        collector.addMarker(id, startedAt + 2_000, "issue", "WHOOP 4C1594026 Bearer private-token")
        collector.stop(startedAt + 5_000)
        val source = File(context.filesDir, "ground-truth/session-$id.jsonl")
        val sourceBytes = source.readBytes()
        val before = collector.sessions().single { it.id == id }
        var completions = 0
        var outputs = 0
        val reviewedFile = File(context.cacheDir, "session-reviewed.zip")
        val output: suspend (List<Pair<String, ByteArray>>) -> File? = { entries ->
            outputs++
            assertFalse(entries.filter { it.first in setOf("meta.json", "events.jsonl", "events.csv") }
                .any { String(it.second).contains("private-strap") || String(it.second).contains("private-token") })
            reviewedFile.apply { writeBytes(requireNotNull(LogExport.zipEntries(entries))) }
        }
        try {
            val repo = WhoopRepository(db.whoopDao())
            val preparation = collector.export(repo, id)
            assertFalse(collector.snapshot().exported)
            assertEquals(before, collector.sessions().single { it.id == id })
            collector.share(preparation, id, onExported = { completions++ }, output = output)
            assertFalse(preparation.exists())
            val cancelledId = requireNotNull(DebugExportReview.shared.pending).id
            DebugExportReview.shared.cancel()
            DebugExportReview.shared.confirm(cancelledId)
            assertEquals(0, outputs)
            assertEquals(0, completions)
            assertEquals(before, collector.sessions().single { it.id == id })
            assertArrayEquals(sourceBytes, source.readBytes())

            collector.share(collector.export(repo, id), id, onExported = { completions++ }, output = { null })
            DebugExportReview.shared.confirm(requireNotNull(DebugExportReview.shared.pending).id)
            assertEquals(before, collector.sessions().single { it.id == id })
            assertFalse(collector.snapshot().exported)
            assertEquals(0, completions)

            collector.share(collector.export(repo, id), id, onExported = { completions++ }, output = output)
            val confirmedId = requireNotNull(DebugExportReview.shared.pending).id
            assertFalse(collector.snapshot().exported)
            DebugExportReview.shared.confirm(confirmedId)
            DebugExportReview.shared.confirm(confirmedId)
            val after = collector.sessions().single { it.id == id }
            assertTrue(after.exported)
            assertNotNull(after.lastExportedAtMs)
            assertTrue(collector.snapshot().exported)
            assertEquals(1, outputs)
            assertEquals(1, completions)
            assertArrayEquals(sourceBytes, source.readBytes())

            collector.share(collector.export(repo, id), id, onExported = { completions++ }, output = output)
            DebugExportReview.shared.cancel()
            assertEquals(after, collector.sessions().single { it.id == id })
            assertEquals(1, completions)
        } finally {
            DebugExportReview.shared.cancel()
            collector.deleteSession(id)
            reviewedFile.delete()
            db.close()
        }
    }

    @Test fun actualSessionPreparationReadAndOversizeFailuresKeepExportState() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, WhoopDatabase::class.java).build()
        val collector = com.noop.testcentre.GroundTruthCollector.from(context)
        val startedAt = 1_700_000_010_000L
        val id = requireNotNull(collector.start("private-strap", startedAt).sessionId)
        collector.stop(startedAt + 5_000)
        val source = File(context.filesDir, "ground-truth/session-$id.jsonl")
        val sourceBytes = source.readBytes()
        val before = collector.sessions().single { it.id == id }
        var completions = 0
        try {
            val repo = WhoopRepository(db.whoopDao())
            val tooLarge = collector.export(repo, id)
            java.util.zip.ZipOutputStream(tooLarge.outputStream()).use { zip ->
                zip.putNextEntry(java.util.zip.ZipEntry("imu/frame.bin"))
                zip.write(ByteArray(20 * 1024 * 1024 + 1))
                zip.closeEntry()
            }
            assertTrue(runCatching { collector.share(tooLarge, id, onExported = { completions++ }) }.isFailure)
            assertFalse(tooLarge.exists())
            assertNull(DebugExportReview.shared.pending)
            assertEquals(before, collector.sessions().single { it.id == id })
            assertFalse(collector.snapshot().exported)

            val missing = collector.export(repo, id)
            missing.delete()
            assertTrue(runCatching { collector.share(missing, id, onExported = { completions++ }) }.isFailure)
            assertNull(DebugExportReview.shared.pending)
            assertEquals(before, collector.sessions().single { it.id == id })
            assertEquals(0, completions)
            assertArrayEquals(sourceBytes, source.readBytes())
        } finally {
            DebugExportReview.shared.cancel()
            collector.deleteSession(id)
            db.close()
        }
    }

    @Test fun incompleteSessionPreparationIsDeletedOnWriterFailureAndCancellation() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val collector = com.noop.testcentre.GroundTruthCollector.from(context)
        val startedAt = 1_700_000_020_000L
        val id = requireNotNull(collector.start("private-strap", startedAt).sessionId)
        collector.stop(startedAt + 5_000)
        val source = File(context.filesDir, "ground-truth/session-$id.jsonl")
        val sourceBytes = source.readBytes()
        val before = collector.sessions().single { it.id == id }
        val preparation = File(context.cacheDir, "logs/noop-5mg-raw-$id.zip")
        try {
            val failure = runCatching {
                collector.writePreparation(preparation) { zip ->
                    zip.putNextEntry(java.util.zip.ZipEntry("meta.json"))
                    zip.write("private metadata".toByteArray())
                    throw java.io.IOException("writer failed")
                }
            }.exceptionOrNull()
            assertTrue(failure is java.io.IOException)
            assertFalse(preparation.exists())
            assertEquals(before, collector.sessions().single { it.id == id })
            assertArrayEquals(sourceBytes, source.readBytes())

            val entered = CompletableDeferred<Unit>()
            val job = launch {
                collector.writePreparation(preparation) { zip ->
                    zip.putNextEntry(java.util.zip.ZipEntry("meta.json"))
                    zip.write("private metadata".toByteArray())
                    entered.complete(Unit)
                    awaitCancellation()
                }
            }
            entered.await()
            job.cancelAndJoin()
            assertFalse(preparation.exists())
            assertEquals(before, collector.sessions().single { it.id == id })
            assertFalse(collector.snapshot().exported)
            assertArrayEquals(sourceBytes, source.readBytes())
        } finally {
            collector.deleteSession(id)
        }
    }

    @Test fun logSourceReadCannotUndoCancellationOrReplaceNewerReview() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val oldFile = File(context.cacheDir, "old-review.txt").apply { writeText("old report") }
        val latestFile = File(context.cacheDir, "latest-review.txt").apply { writeText("latest report") }
        try {
            val entered = CompletableDeferred<Unit>()
            val resume = CompletableDeferred<Unit>()
            val old = launch {
                LogExport.reviewDebugFiles(context, listOf("report.txt" to oldFile), oldFile.name, read = {
                    entered.complete(Unit)
                    resume.await()
                    listOf("report.txt" to oldFile.readBytes())
                })
            }
            withTimeout(5_000) { entered.await() }
            LogExport.reviewDebugFiles(context, listOf("report.txt" to latestFile), latestFile.name)
            val latest = requireNotNull(DebugExportReview.shared.pending)
            resume.complete(Unit)
            withTimeout(5_000) { old.join() }
            assertEquals(latest.id, DebugExportReview.shared.pending?.id)
            var output = emptyList<Pair<String, ByteArray>>()
            DebugExportReview.shared.confirm(latest.id) { output = it }
            assertEquals("latest report", String(output.single().second))

            val cancelledEntered = CompletableDeferred<Unit>()
            val resumeCancelled = CompletableDeferred<Unit>()
            val cancelled = launch {
                LogExport.reviewDebugFiles(context, listOf("report.txt" to oldFile), oldFile.name, read = {
                    cancelledEntered.complete(Unit)
                    resumeCancelled.await()
                    listOf("report.txt" to oldFile.readBytes())
                })
            }
            withTimeout(5_000) { cancelledEntered.await() }
            DebugExportReview.shared.cancel()
            resumeCancelled.complete(Unit)
            withTimeout(5_000) { cancelled.join() }
            assertNull(DebugExportReview.shared.pending)
        } finally {
            oldFile.delete()
            latestFile.delete()
            DebugExportReview.shared.cancel()
        }
    }

    @Test fun researchZipReadCannotUndoCancellationOrReplaceNewerReview() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val collector = com.noop.testcentre.GroundTruthCollector.from(context)
        val oldFile = File(context.cacheDir, "old-research-review.zip")
        val latestFile = File(context.cacheDir, "latest-research-review.zip")
        oldFile.writeBytes(requireNotNull(LogExport.zipEntries(listOf("meta.json" to "old metadata".toByteArray()))))
        latestFile.writeBytes(requireNotNull(LogExport.zipEntries(listOf("meta.json" to "latest metadata".toByteArray()))))
        try {
            val entered = CompletableDeferred<Unit>()
            val resume = CompletableDeferred<Unit>()
            val old = launch {
                collector.share(oldFile, read = { file ->
                    entered.complete(Unit)
                    resume.await()
                    DebugExportReview.readResearchZip(file)
                })
            }
            withTimeout(5_000) { entered.await() }
            collector.share(latestFile)
            val latest = requireNotNull(DebugExportReview.shared.pending)
            resume.complete(Unit)
            withTimeout(5_000) { old.join() }
            assertEquals(latest.id, DebugExportReview.shared.pending?.id)
            var output = emptyList<Pair<String, ByteArray>>()
            DebugExportReview.shared.confirm(latest.id) { output = it }
            assertEquals("latest metadata", String(output.single().second))

            val cancelledEntered = CompletableDeferred<Unit>()
            val resumeCancelled = CompletableDeferred<Unit>()
            val cancelled = launch {
                collector.share(oldFile, read = { file ->
                    cancelledEntered.complete(Unit)
                    resumeCancelled.await()
                    DebugExportReview.readResearchZip(file)
                })
            }
            withTimeout(5_000) { cancelledEntered.await() }
            DebugExportReview.shared.cancel()
            resumeCancelled.complete(Unit)
            withTimeout(5_000) { cancelled.join() }
            assertNull(DebugExportReview.shared.pending)
        } finally {
            oldFile.delete()
            latestFile.delete()
            DebugExportReview.shared.cancel()
        }
    }

    @Test fun alreadyCancelledProducerCannotClearNewerReview() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val review = DebugExportReview.shared
        val file = File(context.cacheDir, "cancelled-source-review.zip")
        val source = requireNotNull(LogExport.zipEntries(listOf("meta.json" to "private source".toByteArray())))
        file.writeBytes(source)
        val collector = com.noop.testcentre.GroundTruthCollector.from(context)
        try {
            review.stage(listOf("report.txt" to "latest report".toByteArray())) { }
            val latest = requireNotNull(review.pending)
            val entered = CompletableDeferred<Unit>()
            var resume: kotlin.coroutines.Continuation<Unit>? = null
            var reads = 0
            var attempts = 0
            val cancelled = launch {
                kotlin.coroutines.suspendCoroutine<Unit> { resume = it; entered.complete(Unit) }
                attempts++
                assertTrue(runCatching { review.beginPreparation() }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
                assertTrue(runCatching { review.stage(listOf("report.txt" to "cancelled".toByteArray())) { } }
                    .exceptionOrNull() is kotlinx.coroutines.CancellationException)
                assertTrue(runCatching {
                    LogExport.reviewDebugFiles(context, listOf("report.txt" to file), file.name, read = {
                        reads++
                        listOf("report.txt" to "cancelled".toByteArray())
                    })
                }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
                assertTrue(runCatching {
                    collector.share(file, read = {
                        reads++
                        DebugExportReview.readResearchZip(it)
                    })
                }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
            }
            withTimeout(5_000) { entered.await() }
            cancelled.cancel()
            requireNotNull(resume).resumeWith(Result.success(Unit))
            withTimeout(5_000) { cancelled.join() }
            assertEquals(latest.id, review.pending?.id)
            assertEquals(1, attempts)
            assertEquals(0, reads)
            assertArrayEquals(source, file.readBytes())
            var output = emptyList<Pair<String, ByteArray>>()
            review.confirm(latest.id) { output = it }
            assertEquals("latest report", String(output.single().second))
        } finally {
            file.delete()
            review.cancel()
        }
    }

    @Test fun bearerRedactionMatchesSwiftOracle() {
        val cases = listOf(
            "Authorization: Bearer secret.token", "bearer\tabc",
            """{"text":"Bearer\tjson-secret"}""", """{"text":"Bearer\njson-secret"}""",
            "not a token", "Bearer a+/b==", "prefixBearer secret", "Bearer \nabc", "BEARER s3cr3t",
            "Bearer\u00A0secret", "Bearer\u0085secret", "Bearer\u2007secret",
            "Bearer Ksecret", "Bearer ſsecret", "Bearer aKb", "Bearer aſb", "Bearer βeta",
        )
        val actual = cases.mapIndexed { index, text ->
            "$index:" + java.util.Base64.getEncoder().encodeToString(DebugExportReview.redactBearer(text).toByteArray(Charsets.UTF_8))
        }.joinToString("\n")
        val expected = """
            0:QXV0aG9yaXphdGlvbjogQmVhcmVyIDxyZWRhY3RlZD4=
            1:QmVhcmVyIDxyZWRhY3RlZD4=
            2:eyJ0ZXh0IjoiQmVhcmVyIDxyZWRhY3RlZD4ifQ==
            3:eyJ0ZXh0IjoiQmVhcmVyIDxyZWRhY3RlZD4ifQ==
            4:bm90IGEgdG9rZW4=
            5:QmVhcmVyIDxyZWRhY3RlZD4=
            6:cHJlZml4QmVhcmVyIDxyZWRhY3RlZD4=
            7:QmVhcmVyIDxyZWRhY3RlZD4=
            8:QmVhcmVyIDxyZWRhY3RlZD4=
            9:QmVhcmVyIDxyZWRhY3RlZD4=
            10:QmVhcmVyIDxyZWRhY3RlZD4=
            11:QmVhcmVyIDxyZWRhY3RlZD4=
            12:QmVhcmVyIOKEqnNlY3JldA==
            13:QmVhcmVyIMW/c2VjcmV0
            14:QmVhcmVyIDxyZWRhY3RlZD7ihKpi
            15:QmVhcmVyIDxyZWRhY3RlZD7Fv2I=
            16:QmVhcmVyIM6yZXRh
        """.trimIndent()
        assertEquals(expected, actual)
    }

}
