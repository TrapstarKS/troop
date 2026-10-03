package com.noop.ingest

import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.noop.data.DailyMetric
import com.noop.data.MetricSeriesRow
import com.noop.data.WhoopDao
import com.noop.data.WhoopRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.lang.reflect.Proxy
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ImportedEnergyMappingTest {
    private class Writes {
        var days = emptyList<DailyMetric>()
        var series = emptyList<MetricSeriesRow>()
        @Suppress("UNCHECKED_CAST")
        val repo = WhoopRepository(Proxy.newProxyInstance(WhoopDao::class.java.classLoader,
            arrayOf(WhoopDao::class.java)) { _, method, args ->
            when (method.name) {
                "device" -> null
                "upsertDevice" -> Unit
                "upsertDailyMetrics" -> { days = args!![0] as List<DailyMetric>; Unit }
                "upsertMetricSeries" -> { series = args!![0] as List<MetricSeriesRow>; Unit }
                else -> throw AssertionError("unexpected DAO call ${method.name}")
            }
        } as WhoopDao)
    }

    @Test fun wearableImportsKnownTotalAndActiveAsDistinctQuantities() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "oura-energy.zip")
        try {
            ZipOutputStream(file.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("oura-energy.json"))
                zip.write("""{"daily_activity":[{"day":"2026-06-01","active_calories":520,"total_calories":2450},{"day":"2026-06-02","active_calories":312}]}""".toByteArray())
                zip.closeEntry()
            }
            val writes = Writes()
            val summary = WearableExportImporter.importExport(context, Uri.fromFile(file), writes.repo)
            assertTrue(summary.message, writes.days.isNotEmpty())
            assertEquals(listOf(520.0, 312.0), writes.days.map { it.activeEnergyKcalEst })
            assertEquals(listOf(2450.0, null), writes.days.map { it.activeKcalEst })
            assertEquals(listOf(520.0, 312.0), writes.series.filter { it.key == "active_kcal" }.map { it.value })
            assertEquals(listOf(2450.0), writes.series.filter { it.key == "energy_kcal" }.map { it.value })
        } finally { file.delete() }
    }

    @Test fun xiaomiActiveOnlyImportLeavesTotalUnknown() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "xiaomi-energy.db")
        try {
            SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
                db.execSQL("CREATE TABLE steps (sid TEXT, key TEXT, time INTEGER, value TEXT, zone_offset INTEGER, time_zero INTEGER, deleted INTEGER DEFAULT 0)")
                db.execSQL("CREATE TABLE calories_day (sid TEXT, key TEXT, time INTEGER, value TEXT, zone_offset INTEGER, time_zero INTEGER, deleted INTEGER DEFAULT 0)")
                db.execSQL("""INSERT INTO calories_day VALUES ('default','calories_day',1742601600,'{"calories":312}',0,1742601600,0)""")
            }
            val writes = Writes()
            val summary = XiaomiBandImporter.importExport(context, Uri.fromFile(file), writes.repo)
            assertTrue(summary.message, writes.days.isNotEmpty())
            assertEquals(312.0, writes.days.single().activeEnergyKcalEst!!, 0.0)
            assertNull(writes.days.single().activeKcalEst)
            assertTrue(writes.series.none { it.key == "energy_kcal" })
        } finally { file.delete() }
    }
}
