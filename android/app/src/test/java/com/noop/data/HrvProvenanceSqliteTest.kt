package com.noop.data

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import org.junit.Assert.*
import org.junit.Test

class HrvProvenanceSqliteTest {
    private fun seed(db: Connection) {
        db.createStatement().use {
            it.execute("PRAGMA journal_mode=WAL")
            it.execute("CREATE TABLE dailyMetric(deviceId TEXT NOT NULL, day TEXT NOT NULL, totalSleepMin REAL, efficiency REAL, deepMin REAL, remMin REAL, lightMin REAL, disturbances INTEGER, restingHr INTEGER, avgHrv REAL, recovery REAL, strain REAL, exerciseCount INTEGER, spo2Pct REAL, skinTempDevC REAL, respRateBpm REAL, steps INTEGER, activeKcalEst REAL, spo2Red INTEGER, spo2Ir INTEGER, avgSdnn REAL, skinTempC REAL, sleepHrOnly INTEGER, activeEnergyKcalEst REAL, PRIMARY KEY(deviceId,day))")
            it.execute("CREATE TABLE metricSeries(deviceId TEXT, day TEXT, key TEXT, value REAL, PRIMARY KEY(deviceId,day,key))")
            for (id in listOf("active-noop", "my-whoop-noop", "unrelated-noop"))
                it.execute("INSERT INTO dailyMetric(deviceId, day, avgHrv, respRateBpm) VALUES('$id','2026-06-10',40,16)")
            it.execute("INSERT INTO metricSeries VALUES('my-whoop-noop','2026-06-10','hrv_fresh_scoring_valid',1)")
            it.execute("INSERT INTO metricSeries VALUES('active-noop','2026-06-09','hrv_fresh_scoring_valid',1)")
            it.execute("INSERT INTO metricSeries VALUES('my-whoop-noop','2026-06-10','hrv_rr_overcount',0)")
            it.execute("INSERT INTO metricSeries VALUES('my-whoop-noop','2026-06-10','resp_fresh_scoring_valid',1)")
            it.execute("INSERT INTO metricSeries VALUES('active-noop','2026-06-09','resp_fresh_scoring_valid',1)")
        }
    }

    private fun ResultSet.doubleOrNull(column: String): Double? =
        getDouble(column).let { if (wasNull()) null else it }

    private fun ResultSet.intOrNull(column: String): Int? =
        getInt(column).let { if (wasNull()) null else it }

    private fun read(db: Connection, ids: List<String>, from: String = "2026-06-10", to: String = "2026-06-10"): List<HrvProvenanceRow> {
        val sql = HRV_PROVENANCE_SQL.replace(":deviceIds", ids.joinToString(",") { "?" })
            .replace(":from", "?").replace(":to", "?")
        return db.prepareStatement(sql).use { s ->
            (ids + listOf(from, to)).forEachIndexed { i, value -> s.setString(i + 1, value) }
            s.executeQuery().use { r -> buildList {
                while (r.next()) add(HrvProvenanceRow(
                    r.getString("deviceId"), r.getString("day"), r.doubleOrNull("value"),
                    r.doubleOrNull("freshScoringValid"), r.doubleOrNull("overcount"),
                    r.doubleOrNull("respValue"), r.doubleOrNull("respFreshScoringValid"),
                    metric = DailyMetric(
                        deviceId = r.getString("metric_deviceId"),
                        day = r.getString("metric_day"),
                        totalSleepMin = r.doubleOrNull("metric_totalSleepMin"),
                        efficiency = r.doubleOrNull("metric_efficiency"),
                        deepMin = r.doubleOrNull("metric_deepMin"),
                        remMin = r.doubleOrNull("metric_remMin"),
                        lightMin = r.doubleOrNull("metric_lightMin"),
                        disturbances = r.intOrNull("metric_disturbances"),
                        restingHr = r.intOrNull("metric_restingHr"),
                        avgHrv = r.doubleOrNull("metric_avgHrv"),
                        recovery = r.doubleOrNull("metric_recovery"),
                        strain = r.doubleOrNull("metric_strain"),
                        exerciseCount = r.intOrNull("metric_exerciseCount"),
                        spo2Pct = r.doubleOrNull("metric_spo2Pct"),
                        skinTempDevC = r.doubleOrNull("metric_skinTempDevC"),
                        respRateBpm = r.doubleOrNull("metric_respRateBpm"),
                        steps = r.intOrNull("metric_steps"),
                        activeKcalEst = r.doubleOrNull("metric_activeKcalEst"),
                        spo2Red = r.intOrNull("metric_spo2Red"),
                        spo2Ir = r.intOrNull("metric_spo2Ir"),
                        avgSdnn = r.doubleOrNull("metric_avgSdnn"),
                        skinTempC = r.doubleOrNull("metric_skinTempC"),
                        sleepHrOnly = r.intOrNull("metric_sleepHrOnly")?.let { it != 0 },
                        activeEnergyKcalEst = r.doubleOrNull("metric_activeEnergyKcalEst"),
                    ),
                ))
            } }
        }
    }

    @Test fun markersJoinOnlyTheirPhysicalSourceAndDay() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { db ->
            seed(db)
            val rows = read(db, listOf("active-noop", "my-whoop-noop"))
            assertEquals(2, rows.size)
            val active = rows.single { it.deviceId == "active-noop" }
            assertEquals(active.deviceId, active.metric?.deviceId)
            assertEquals(active.day, active.metric?.day)
            assertEquals(active.value, active.metric?.avgHrv)
            assertEquals(active.respValue, active.metric?.respRateBpm)
            assertNull(active.freshScoringValid)
            assertNull(active.overcount)
            assertEquals(16.0, active.respValue!!, 0.0)
            assertNull(active.respFreshScoringValid)
            assertEquals(1.0, rows.single { it.deviceId == "my-whoop-noop" }.respFreshScoringValid!!, 0.0)
            assertEquals(1.0, rows.single { it.deviceId == "my-whoop-noop" }.freshScoringValid!!, 0.0)
            assertTrue(read(db, emptyList()).isEmpty())
        }
    }

    @Test fun vendorRespirationSurvivesWithoutAnyHrvRowOrMarker() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { db ->
            seed(db)
            db.createStatement().use {
                it.execute("INSERT INTO dailyMetric(deviceId, day, avgHrv, respRateBpm) VALUES('oura-ring-noop','2026-06-10',NULL,16)")
                it.execute("INSERT INTO metricSeries VALUES('oura-ring-noop','2026-06-10','resp_fresh_scoring_valid',1)")
            }
            val row = read(db, listOf("oura-ring-noop")).single()
            assertNull(row.value)
            assertNull(row.freshScoringValid)
            assertEquals(16.0, row.metric!!.respRateBpm!!, 0.0)
            assertEquals(16.0, row.respValue!!, 0.0)
            assertEquals(1.0, row.respFreshScoringValid!!, 0.0)
        }
    }

    @Test fun everyPhysicalDailyRowSurvivesWithoutHrvOrRespiration() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { db ->
            seed(db)
            val columns = listOf("avgHrv", "restingHr", "respRateBpm", "spo2Pct", "skinTempDevC", "skinTempC", "recovery", "totalSleepMin")
            val values = listOf(40.0, 60.0, 16.0, 98.0, 0.4, 33.5, 75.0, 480.0)
            val days = (1..9).map { "2026-06-${it.toString().padStart(2, '0')}" }
            db.createStatement().use { statement ->
                statement.execute("DELETE FROM dailyMetric")
                statement.execute("DELETE FROM metricSeries")
                columns.forEachIndexed { index, column ->
                    statement.execute("INSERT INTO dailyMetric(deviceId, day, $column) VALUES ('active-noop', '${days[index]}', ${values[index]})")
                }
                statement.execute("INSERT INTO dailyMetric(deviceId, day) VALUES ('active-noop', '2026-06-09')")
                statement.execute("INSERT INTO dailyMetric(deviceId, day, recovery) VALUES ('active-noop', '2026-05-31', 75), ('active-noop', '2026-06-10', 75), ('my-whoop-noop', '2026-06-01', 75)")
                statement.execute("INSERT INTO metricSeries VALUES ('active-noop', '2026-06-08', 'hrv_fresh_scoring_valid', 1), ('my-whoop-noop', '2026-06-01', 'hrv_fresh_scoring_valid', 1), ('active-noop', '2026-06-12', 'hrv_fresh_scoring_valid', 1)")
            }
            val rows = read(db, listOf("active-noop"), days.first(), days.last())
            assertEquals(days, rows.map { it.day })
            assertTrue(rows.all { it.deviceId == "active-noop" })
            val metrics = rows.map { requireNotNull(it.metric) }
            assertEquals(days, metrics.map { it.day })
            assertTrue(metrics.all { it.deviceId == "active-noop" })
            assertEquals(listOf(40.0, null, null, null, null, null, null, null, null), metrics.map { it.avgHrv })
            assertEquals(listOf(null, 60, null, null, null, null, null, null, null), metrics.map { it.restingHr })
            assertEquals(listOf(null, null, 16.0, null, null, null, null, null, null), metrics.map { it.respRateBpm })
            assertEquals(listOf(null, null, null, 98.0, null, null, null, null, null), metrics.map { it.spo2Pct })
            assertEquals(listOf(null, null, null, null, 0.4, null, null, null, null), metrics.map { it.skinTempDevC })
            assertEquals(listOf(null, null, null, null, null, 33.5, null, null, null), metrics.map { it.skinTempC })
            assertEquals(listOf(null, null, null, null, null, null, 75.0, null, null), metrics.map { it.recovery })
            assertEquals(listOf(null, null, null, null, null, null, null, 480.0, null), metrics.map { it.totalSleepMin })
            for (row in rows) {
                assertEquals(row.metric?.avgHrv, row.value)
                assertEquals(row.metric?.respRateBpm, row.respValue)
                assertNull(row.overcount)
                assertNull(row.respFreshScoringValid)
                assertEquals(if (row.day == "2026-06-08") 1.0 else null, row.freshScoringValid)
            }
        }
    }

    @Test fun joinedReadCannotMixAnUncommittedDailyAndMarkerGeneration() {
        val file = File.createTempFile("hrv-provenance", ".sqlite")
        val oldMetric = DailyMetric(deviceId = "active-noop", day = "2026-06-10", totalSleepMin = 480.0,
            efficiency = 91.0, deepMin = 80.0, remMin = 110.0, lightMin = 290.0,
            disturbances = 3, restingHr = 60, avgHrv = 40.0, recovery = 75.0, strain = 7.5,
            exerciseCount = 2, spo2Pct = 98.0, skinTempDevC = 0.4, respRateBpm = 16.0,
            steps = 12345, activeKcalEst = 2300.0, spo2Red = 501, spo2Ir = 601, avgSdnn = 45.0,
            skinTempC = 33.5, sleepHrOnly = true, activeEnergyKcalEst = 700.0)
        val newMetric = DailyMetric(deviceId = "active-noop", day = "2026-06-10", totalSleepMin = 360.0,
            efficiency = 75.0, deepMin = 40.0, remMin = 80.0, lightMin = 240.0,
            disturbances = 5, restingHr = 80, avgHrv = 20.0, recovery = 30.0, strain = 12.0,
            exerciseCount = 1, spo2Pct = 94.0, skinTempDevC = 1.2, respRateBpm = 20.0,
            steps = 4567, activeKcalEst = 1900.0, spo2Red = 503, spo2Ir = 603, avgSdnn = 25.0,
            skinTempC = 35.0, sleepHrOnly = false, activeEnergyKcalEst = 500.0)
        try {
            DriverManager.getConnection("jdbc:sqlite:${file.path}").use { writer ->
                seed(writer)
                writer.createStatement().use {
                    it.execute("UPDATE dailyMetric SET totalSleepMin=480,efficiency=91,deepMin=80,remMin=110,lightMin=290,disturbances=3,restingHr=60,avgHrv=40,recovery=75,strain=7.5,exerciseCount=2,spo2Pct=98,skinTempDevC=0.4,respRateBpm=16,steps=12345,activeKcalEst=2300,spo2Red=501,spo2Ir=601,avgSdnn=45,skinTempC=33.5,sleepHrOnly=1,activeEnergyKcalEst=700 WHERE deviceId='active-noop'")
                    it.execute("INSERT INTO metricSeries VALUES('active-noop','2026-06-10','hrv_fresh_scoring_valid',1)")
                    it.execute("INSERT INTO metricSeries VALUES('active-noop','2026-06-10','hrv_rr_overcount',0)")
                    it.execute("INSERT INTO metricSeries VALUES('active-noop','2026-06-10','resp_fresh_scoring_valid',1)")
                }
                DriverManager.getConnection("jdbc:sqlite:${file.path}").use { reader ->
                    writer.autoCommit = false
                    writer.createStatement().use {
                        it.execute("UPDATE dailyMetric SET totalSleepMin=360,efficiency=75,deepMin=40,remMin=80,lightMin=240,disturbances=5,restingHr=80,avgHrv=20,recovery=30,strain=12,exerciseCount=1,spo2Pct=94,skinTempDevC=1.2,respRateBpm=20,steps=4567,activeKcalEst=1900,spo2Red=503,spo2Ir=603,avgSdnn=25,skinTempC=35,sleepHrOnly=0,activeEnergyKcalEst=500 WHERE deviceId='active-noop'")
                    }
                    val before = read(reader, listOf("active-noop")).single()
                    assertEquals(oldMetric, before.metric)
                    assertEquals(40.0, before.value!!, 0.0)
                    assertEquals(1.0, before.freshScoringValid!!, 0.0)
                    assertEquals(0.0, before.overcount!!, 0.0)
                    assertEquals(16.0, before.respValue!!, 0.0)
                    assertEquals(1.0, before.respFreshScoringValid!!, 0.0)
                    writer.createStatement().use {
                        it.execute("UPDATE metricSeries SET value=CASE WHEN key='hrv_rr_overcount' THEN 1 ELSE 0 END WHERE deviceId='active-noop' AND day='2026-06-10'")
                    }
                    writer.commit()
                    val after = read(reader, listOf("active-noop")).single()
                    assertEquals(newMetric, after.metric)
                    assertEquals(20.0, after.value!!, 0.0)
                    assertEquals(0.0, after.freshScoringValid!!, 0.0)
                    assertEquals(1.0, after.overcount!!, 0.0)
                    assertEquals(20.0, after.respValue!!, 0.0)
                    assertEquals(0.0, after.respFreshScoringValid!!, 0.0)
                }
            }
        } finally { for (suffix in listOf("", "-wal", "-shm")) File(file.path + suffix).delete() }
    }
}
