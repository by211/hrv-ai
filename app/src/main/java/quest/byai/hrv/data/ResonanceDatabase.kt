package quest.byai.hrv.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        SessionEntity::class,
        RrSampleEntity::class,
        BreathingSegmentEntity::class,
        AnalysisWindowEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class ResonanceDatabase : RoomDatabase() {
    abstract fun resonanceDao(): ResonanceDao
}
