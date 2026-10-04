package com.noop.data

import androidx.room.Room
import org.robolectric.RuntimeEnvironment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorkoutCopyIdentityTest {
    private suspend fun withDatabase(block: suspend (WhoopDao) -> Unit) {
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, WhoopDatabase::class.java).allowMainThreadQueries().build()
        try { block(db.whoopDao()) } finally { db.close() }
    }

    @Test fun manualCopiesProduceNoHealthConnectRecords() {
        val copy = WorkoutRow("my-whoop", 1_000, 2_000, "Running (manual copy)", WorkoutCopyIdentity.SOURCE)
        assertEquals(emptyList<Any>(), com.noop.ingest.HealthConnectWriter.buildExerciseRecords(copy, 0))
    }

    @Test fun naturalKeysAndProvenanceMatchStandaloneSwiftOracle() {
        val lines = mutableListOf<String>()
        for (mask in 0 until 32) {
            val names = (1..5).map { if (it == 1) "Cycling (manual copy)" else "Cycling (manual copy $it)" }
            val occupied = names.filterIndexed { i, _ -> mask and (1 shl i) != 0 }
            lines += WorkoutCopyIdentity.sport("Cycling", occupied)
        }
        for ((base, occupied) in listOf("Café" to listOf("Cafe\u0301 (manual copy)"),
                "Cafe\u0301" to listOf("Café (manual copy)"), "跑步" to listOf("跑步 (manual copy)"),
                "" to emptyList(), "Run (manual copy)" to emptyList())) {
            lines += WorkoutCopyIdentity.sport(base, occupied)
        }
        val output = lines.joinToString("|") { s -> s.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it.toInt() and 0xff) } } + "\n" +
            listOf("manual-copy", "MANUAL-COPY", "Manual-Copy", "manual", " manual-copy", "manual-copy ", "whoop", "").joinToString("|") {
                if (WorkoutCopyIdentity.isCopy(it)) "1" else "0"
            }
        assertEquals("""
        4379636c696e6720286d616e75616c20636f707929|4379636c696e6720286d616e75616c20636f7079203229|4379636c696e6720286d616e75616c20636f707929|4379636c696e6720286d616e75616c20636f7079203329|4379636c696e6720286d616e75616c20636f707929|4379636c696e6720286d616e75616c20636f7079203229|4379636c696e6720286d616e75616c20636f707929|4379636c696e6720286d616e75616c20636f7079203429|4379636c696e6720286d616e75616c20636f707929|4379636c696e6720286d616e75616c20636f7079203229|4379636c696e6720286d616e75616c20636f707929|4379636c696e6720286d616e75616c20636f7079203329|4379636c696e6720286d616e75616c20636f707929|4379636c696e6720286d616e75616c20636f7079203229|4379636c696e6720286d616e75616c20636f707929|4379636c696e6720286d616e75616c20636f7079203529|4379636c696e6720286d616e75616c20636f707929|4379636c696e6720286d616e75616c20636f7079203229|4379636c696e6720286d616e75616c20636f707929|4379636c696e6720286d616e75616c20636f7079203329|4379636c696e6720286d616e75616c20636f707929|4379636c696e6720286d616e75616c20636f7079203229|4379636c696e6720286d616e75616c20636f707929|4379636c696e6720286d616e75616c20636f7079203429|4379636c696e6720286d616e75616c20636f707929|4379636c696e6720286d616e75616c20636f7079203229|4379636c696e6720286d616e75616c20636f707929|4379636c696e6720286d616e75616c20636f7079203329|4379636c696e6720286d616e75616c20636f707929|4379636c696e6720286d616e75616c20636f7079203229|4379636c696e6720286d616e75616c20636f707929|4379636c696e6720286d616e75616c20636f7079203629|436166c3a920286d616e75616c20636f707929|43616665cc8120286d616e75616c20636f707929|e8b791e6ada520286d616e75616c20636f7079203229|20286d616e75616c20636f707929|52756e20286d616e75616c20636f70792920286d616e75616c20636f707929
        1|1|1|0|0|0|0|0
        """.trimIndent(), output)
    }

    @Test fun repeatedCopiesPreserveOriginalAndAllCopiedFields() = runBlocking {
        withDatabase { dao ->
            val original = WorkoutRow("my-whoop", 1_000, 4_009, "Cycling", "whoop",
                durationS = 3009.25, energyKcal = 296.8, avgHr = 139, maxHr = 167, strain = 52.25,
                distanceM = 8723.6, zonesJSON = "{\"z1\":7.3,\"z2\":28.3}", notes = "Original import",
                routePolyline = "recorded-route", steps = 1_234)
            dao.upsertWorkouts(listOf(original))
            val repo = WhoopRepository(dao)
            repo.saveManualWorkout(original, asCopy = true)
            repo.saveManualWorkout(original, asCopy = true)
            val rows = dao.workouts("my-whoop", 0, 10_000, 100)
            assertEquals(3, rows.size)
            assertEquals(original, rows.single { it.sport == original.sport })
            val first = rows.single { it.sport == "Cycling (manual copy)" }
            val second = rows.single { it.sport == "Cycling (manual copy 2)" }
            for (copy in listOf(first, second)) {
                assertEquals(original.copy(sport = copy.sport, source = WorkoutCopyIdentity.SOURCE), copy)
            }
        }
    }

    @Test fun movedCopyCollisionPreservesOriginalAndExistingCopies() = runBlocking {
        withDatabase { dao ->
            val original = WorkoutRow("my-whoop", 1_000, 2_000, "Cycling", "whoop",
                durationS = 1000.25, energyKcal = 296.8, avgHr = 139, maxHr = 167, strain = 52.25,
                distanceM = 8723.6, notes = "Original import", routePolyline = "recorded-route", steps = 1_234)
            dao.upsertWorkouts(listOf(original))
            val first = dao.insertManualWorkoutCopy(original)
            dao.insertManualWorkoutCopy(original)
            val before = dao.workouts("my-whoop", 0, 10_000, 100)
            val repo = WhoopRepository(dao)
            for (target in listOf(original.sport, "Cycling (manual copy 2)")) {
                val moved = first.copy(sport = target, energyKcal = 999.0, notes = "Edited copy")
                var rejected = false
                try { repo.saveManualWorkout(moved, replacing = first) } catch (_: Exception) { rejected = true }
                org.junit.Assert.assertTrue("A moved copy cannot replace another activity", rejected)
                assertEquals(before, dao.workouts("my-whoop", 0, 10_000, 100))
            }
        }
    }

    @Test fun movedKeysMatchStandaloneSwiftOracle() {
        val pairs = listOf("" to "", "Running" to "Running", "Running" to "Cycling",
            "Café" to "Cafe\u0301", "跑步" to "跑步", "Cycling (manual copy)" to "Cycling")
        val output = listOf(1_000L, 1_001L).flatMap { start ->
            pairs.map { (old, new) -> if (WorkoutCopyIdentity.keyMoved(1_000L, old, start, new)) "1" else "0" }
        }.joinToString("|")
        assertEquals("0|0|1|1|0|1|1|1|1|1|1|1", output)
    }

    @Test fun concurrentCopiesCannotClobberOriginalOrAnotherCopy() = runBlocking {
        withDatabase { dao ->
            val original = WorkoutRow("my-whoop", 1_000, 2_000, "Running", "whoop")
            dao.upsertWorkouts(listOf(original))
            coroutineScope {
                (1..12).map { async(Dispatchers.IO) { dao.insertManualWorkoutCopy(original) } }.awaitAll()
            }
            val rows = dao.workouts("my-whoop", 0, 10_000, 100)
            assertEquals(13, rows.size)
            assertEquals(13, rows.map { it.sport }.toSet().size)
            assertEquals(original, rows.single { it.sport == original.sport })
        }
    }
}
