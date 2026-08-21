package quest.byai.hrv.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        SessionEntity::class,
        RrSampleEntity::class,
        BreathingSegmentEntity::class,
        AnalysisWindowEntity::class,
        HrvMeasurementEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class ResonanceDatabase : RoomDatabase() {
    abstract fun resonanceDao(): ResonanceDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN eliteHrvScore REAL")
                db.execSQL("ALTER TABLE sessions ADD COLUMN lnRmssd REAL")
                db.execSQL("ALTER TABLE sessions ADD COLUMN eliteArtifactPercent REAL")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS hrv_measurements (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        sessionId INTEGER NOT NULL,
                        elapsedRealtimeMs INTEGER NOT NULL,
                        windowDurationMs INTEGER NOT NULL,
                        rrIntervalCount INTEGER NOT NULL,
                        rmssdMs REAL NOT NULL,
                        lnRmssd REAL NOT NULL,
                        displayedHrvScore REAL NOT NULL,
                        unroundedHrvScore REAL NOT NULL,
                        FOREIGN KEY(sessionId) REFERENCES sessions(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_hrv_measurements_sessionId ON hrv_measurements(sessionId)",
                )
            }
        }
    }
}
