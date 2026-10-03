package com.noop.data

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import org.junit.Assert.*
import org.junit.Test

class HrvProvenanceSqliteTest {
    private fun seed(db: Connection) {
        db.createStatement().use {
            it.execute("PRAGMA journal_mode=WAL")
            it.execute("CREATE TABLE dailyMetric(deviceId TEXT, day TEXT, avgHrv REAL, PRIMARY KEY(deviceId,day))")
            it.execute("CREATE TABLE metricSeries(deviceId TEXT, day TEXT, key TEXT, value REAL, PRIMARY KEY(deviceId,day,key))")
            for (id in listOf("active-noop", "my-whoop-noop", "unrelated-noop"))
                it.execute("INSERT INTO dailyMetric VALUES('$id','2026-06-10',40)")
            it.execute("INSERT INTO metricSeries VALUES('my-whoop-noop','2026-06-10','hrv_fresh_scoring_valid',1)")
            it.execute("INSERT INTO metricSeries VALUES('active-noop','2026-06-09','hrv_fresh_scoring_valid',1)")
            it.execute("INSERT INTO metricSeries VALUES('my-whoop-noop','2026-06-10','hrv_rr_overcount',0)")
        }
    }

    private fun read(db: Connection, ids: List<String>): List<HrvProvenanceRow> {
        val sql = HRV_PROVENANCE_SQL.replace(":deviceIds", ids.joinToString(",") { "?" })
            .replace(":from", "?").replace(":to", "?")
        return db.prepareStatement(sql).use { s ->
            (ids + listOf("2026-06-10", "2026-06-10")).forEachIndexed { i, value -> s.setString(i + 1, value) }
            s.executeQuery().use { r -> buildList {
                while (r.next()) add(HrvProvenanceRow(r.getString("deviceId"), r.getString("day"),
                    r.getDouble("value"), r.getDouble("freshScoringValid").let { if (r.wasNull()) null else it },
                    r.getDouble("overcount").let { if (r.wasNull()) null else it }))
            } }
        }
    }

    @Test fun markersJoinOnlyTheirPhysicalSourceAndDay() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { db ->
            seed(db)
            val rows = read(db, listOf("active-noop", "my-whoop-noop"))
            assertEquals(2, rows.size)
            val active = rows.single { it.deviceId == "active-noop" }
            assertNull(active.freshScoringValid)
            assertNull(active.overcount)
            assertEquals(1.0, rows.single { it.deviceId == "my-whoop-noop" }.freshScoringValid!!, 0.0)
        }
    }

    @Test fun joinedReadCannotMixAnUncommittedDailyAndMarkerGeneration() {
        val file = File.createTempFile("hrv-provenance", ".sqlite")
        try {
            DriverManager.getConnection("jdbc:sqlite:${file.path}").use { writer ->
                seed(writer)
                writer.createStatement().use { it.execute("INSERT INTO metricSeries VALUES('active-noop','2026-06-10','hrv_fresh_scoring_valid',1)") }
                DriverManager.getConnection("jdbc:sqlite:${file.path}").use { reader ->
                    writer.autoCommit = false
                    writer.createStatement().use { it.execute("UPDATE dailyMetric SET avgHrv=20 WHERE deviceId='active-noop'") }
                    val before = read(reader, listOf("active-noop")).single()
                    assertEquals(40.0, before.value, 0.0)
                    assertEquals(1.0, before.freshScoringValid!!, 0.0)
                    writer.createStatement().use { it.execute("UPDATE metricSeries SET value=0 WHERE deviceId='active-noop' AND day='2026-06-10'") }
                    writer.commit()
                    val after = read(reader, listOf("active-noop")).single()
                    assertEquals(20.0, after.value, 0.0)
                    assertEquals(0.0, after.freshScoringValid!!, 0.0)
                }
            }
        } finally { for (suffix in listOf("", "-wal", "-shm")) File(file.path + suffix).delete() }
    }
}
