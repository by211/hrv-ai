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
    ],
    version = 2,
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
    }
}
