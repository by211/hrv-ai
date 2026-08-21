package quest.byai.hrv.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ResonanceDatabaseMigrationTest {
    @Test
    fun migrationFrom1To2PreservesSessionsAndAddsEliteHrvColumns() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(DATABASE_NAME)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(DATABASE_NAME)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(CREATE_VERSION_1_SESSIONS)
                        db.execSQL(INSERT_VERSION_1_SESSION)
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        )
        val migrated = helper.writableDatabase
        ResonanceDatabase.MIGRATION_1_2.migrate(migrated)

        migrated.query(
            "SELECT id, resonanceScore, eliteHrvScore, lnRmssd, eliteArtifactPercent FROM sessions WHERE id = 1",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1L, cursor.getLong(0))
            assertEquals(75.0, cursor.getDouble(1), 0.0)
            assertTrue(cursor.isNull(2))
            assertTrue(cursor.isNull(3))
            assertTrue(cursor.isNull(4))
        }
        helper.close()
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun migrationFrom2To3PreservesSessionsAndAddsHrvMeasurementHistory() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(DATABASE_NAME_2)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(DATABASE_NAME_2)
                .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(CREATE_VERSION_2_SESSIONS)
                        db.execSQL(INSERT_VERSION_2_SESSION)
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        )
        val migrated = helper.writableDatabase
        ResonanceDatabase.MIGRATION_2_3.migrate(migrated)
        migrated.execSQL(
            """
            INSERT INTO hrv_measurements (
                sessionId, elapsedRealtimeMs, windowDurationMs, rrIntervalCount,
                rmssdMs, lnRmssd, displayedHrvScore, unroundedHrvScore
            ) VALUES (1, 2000, 15000, 15, 50.0, 3.912023, 60.0, 60.184969)
            """.trimIndent(),
        )

        migrated.query(
            "SELECT sessionId, elapsedRealtimeMs, rmssdMs, displayedHrvScore FROM hrv_measurements",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1L, cursor.getLong(0))
            assertEquals(2_000L, cursor.getLong(1))
            assertEquals(50.0, cursor.getDouble(2), 0.0)
            assertEquals(60.0, cursor.getDouble(3), 0.0)
        }
        migrated.query(
            "SELECT name FROM sqlite_master WHERE type = 'index' AND name = 'index_hrv_measurements_sessionId'",
        ).use { cursor -> assertTrue(cursor.moveToFirst()) }
        helper.close()
        context.deleteDatabase(DATABASE_NAME_2)
    }

    private companion object {
        const val DATABASE_NAME = "migration-test"
        const val DATABASE_NAME_2 = "migration-test-2"
        const val CREATE_VERSION_1_SESSIONS = """
            CREATE TABLE sessions (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                type TEXT NOT NULL,
                startedAtEpochMs INTEGER NOT NULL,
                endedAtEpochMs INTEGER,
                status TEXT NOT NULL,
                initialRate REAL NOT NULL,
                finalRate REAL NOT NULL,
                inhaleFraction REAL NOT NULL,
                durationSeconds INTEGER NOT NULL,
                averageHeartRate REAL,
                rmssdMs REAL,
                sdnnMs REAL,
                resonanceScore REAL,
                confidence REAL,
                usableDataFraction REAL,
                ease INTEGER,
                symptomFlags TEXT NOT NULL,
                notes TEXT NOT NULL,
                analysisVersion INTEGER NOT NULL
            )
        """
        const val INSERT_VERSION_1_SESSION = """
            INSERT INTO sessions (
                id, type, startedAtEpochMs, endedAtEpochMs, status,
                initialRate, finalRate, inhaleFraction, durationSeconds,
                averageHeartRate, rmssdMs, sdnnMs, resonanceScore,
                confidence, usableDataFraction, ease, symptomFlags,
                notes, analysisVersion
            ) VALUES (
                1, 'FIXED', 1000, 2000, 'COMPLETE',
                6.0, 6.0, 0.5, 60,
                60.0, 50.0, 40.0, 75.0,
                0.8, 0.95, 4, '', '', 1
            )
        """
        const val CREATE_VERSION_2_SESSIONS = """
            CREATE TABLE sessions (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                type TEXT NOT NULL,
                startedAtEpochMs INTEGER NOT NULL,
                endedAtEpochMs INTEGER,
                status TEXT NOT NULL,
                initialRate REAL NOT NULL,
                finalRate REAL NOT NULL,
                inhaleFraction REAL NOT NULL,
                durationSeconds INTEGER NOT NULL,
                averageHeartRate REAL,
                rmssdMs REAL,
                sdnnMs REAL,
                eliteHrvScore REAL,
                lnRmssd REAL,
                eliteArtifactPercent REAL,
                resonanceScore REAL,
                confidence REAL,
                usableDataFraction REAL,
                ease INTEGER,
                symptomFlags TEXT NOT NULL,
                notes TEXT NOT NULL,
                analysisVersion INTEGER NOT NULL
            )
        """
        const val INSERT_VERSION_2_SESSION = """
            INSERT INTO sessions (
                id, type, startedAtEpochMs, endedAtEpochMs, status,
                initialRate, finalRate, inhaleFraction, durationSeconds,
                symptomFlags, notes, analysisVersion
            ) VALUES (
                1, 'FIXED', 1000, 2000, 'COMPLETE',
                6.0, 6.0, 0.5, 60, '', '', 2
            )
        """
    }
}
