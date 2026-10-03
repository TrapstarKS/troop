package com.noop.data

import androidx.room.Room
import kotlinx.coroutines.runBlocking
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Opens the last released Room schema through the production migration chain (#1803).
 *
 * The structural tests can prove that migration numbers are consecutive, but they cannot prove the
 * SQL produces the entity shape Room expects. That gap once shipped a staging build which crashed
 * every existing install on launch while fresh installs and the whole JVM suite stayed green.
 *
 * Exported schemas under `src/test/resources/roomSchemas` are release inputs, not generated test
 * answers. Starting from `SCHEMA_VERSION - 1` makes an entity edit under an unchanged version fail
 * Room's own identity check; after a legitimate version bump, the new previous-version export becomes
 * the next fixture.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class WhoopDatabaseUpgradeTest {

    private val databaseName = "noop-room-upgrade-${WhoopDatabase.SCHEMA_VERSION}.db"
    private val previousVersion = WhoopDatabase.SCHEMA_VERSION - 1
    private val schemaArchive = createSchemaArchive()

    private val instrumentation = InstrumentationRegistry.getInstrumentation().also { instrumentation ->
        listOf(instrumentation.context.assets, instrumentation.targetContext.assets).forEach { assets ->
            val cookie = ReflectionHelpers.callInstanceMethod<Int>(
                assets,
                "addAssetPath",
                ClassParameter.from(String::class.java, schemaArchive.toString()),
            )
            check(cookie != 0) { "Robolectric could not mount the Room schema test assets" }
        }
    }

    @get:Rule
    val helper = MigrationTestHelper(
        instrumentation,
        WhoopDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun previousReleasedSchemaOpensThroughTheProductionMigrationChain() {
        helper.createDatabase(databaseName, previousVersion).apply {
            execSQL("INSERT INTO dailyMetric (deviceId, day, activeKcalEst, recovery) VALUES ('my-whoop-noop', '2026-10-01', 2345, 72)")
        }.close()

        val migrated = Room.databaseBuilder(
            instrumentation.targetContext,
            WhoopDatabase::class.java,
            databaseName,
        )
            .addMigrations(*WhoopDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()

        try {
            val db = migrated.openHelper.writableDatabase
            db.query("SELECT activeKcalEst, activeEnergyKcalEst, recovery FROM dailyMetric").use { row ->
                check(row.moveToFirst())
                assertEquals(2345.0, row.getDouble(0), 0.0)
                check(row.isNull(1)) { "legacy total must not become active energy" }
                assertEquals(72.0, row.getDouble(2), 0.0)
            }
            runBlocking {
                val dao = migrated.whoopDao()
                val original = dao.dailyMetricsRange("my-whoop-noop", "2026-10-01", "2026-10-01").single()
                dao.upsertDailyMetrics(listOf(original.copy(activeEnergyKcalEst = 456.0)))
                val updated = dao.dailyMetricsRange("my-whoop-noop", "2026-10-01", "2026-10-01").single()
                assertEquals(456.0, updated.activeEnergyKcalEst!!, 0.0)
                assertEquals(2345.0, updated.activeKcalEst!!, 0.0)
                assertEquals(72.0, updated.recovery!!, 0.0)
            }
            val cursor = db.query("PRAGMA user_version")
            cursor.use {
                check(it.moveToFirst()) { "PRAGMA user_version returned no row" }
                assertEquals(WhoopDatabase.SCHEMA_VERSION, it.getInt(0))
            }
        } finally {
            migrated.close()
        }
    }

    /** A version bump is not complete until its exact Room export is preserved for the next upgrade. */
    @Test
    fun currentGeneratedSchemaIsCommitted() {
        val asset = "${WhoopDatabase::class.java.canonicalName}/${WhoopDatabase.SCHEMA_VERSION}.json"
        val committed = checkNotNull(javaClass.classLoader?.getResourceAsStream("roomSchemas/$asset")) {
            "committed Room schema missing: roomSchemas/$asset"
        }.use { it.readBytes() }
        val generatedRoot = checkNotNull(System.getProperty("room.schemaLocation")) {
            "room.schemaLocation is not configured by app/build.gradle.kts"
        }
        val generated = Files.readAllBytes(Path.of(generatedRoot).resolve(asset))

        assertArrayEquals(
            "The committed v${WhoopDatabase.SCHEMA_VERSION} schema must exactly match Room's export",
            generated,
            committed,
        )
    }

    @After
    fun removeSchemaArchive() {
        Files.deleteIfExists(schemaArchive)
    }

    /** Robolectric's AssetManager mounts archives, while Gradle keeps these fixtures as JVM resources. */
    private fun createSchemaArchive(): Path =
        Files.createTempFile("noop-room-schemas-", ".zip").also { archive ->
            archive.toFile().deleteOnExit()
            ZipOutputStream(Files.newOutputStream(archive)).use { zip ->
                for (version in previousVersion..WhoopDatabase.SCHEMA_VERSION) {
                    val asset = "${WhoopDatabase::class.java.canonicalName}/$version.json"
                    val resource = "roomSchemas/$asset"
                    val input = checkNotNull(javaClass.classLoader?.getResourceAsStream(resource)) {
                        "committed Room schema missing: $resource"
                    }
                    input.use {
                        // addAssetPath treats the archive like an APK, whose visible asset root is
                        // the `assets/` directory rather than the archive root.
                        zip.putNextEntry(ZipEntry("assets/$asset"))
                        it.copyTo(zip)
                        zip.closeEntry()
                    }
                }
            }
        }
}
