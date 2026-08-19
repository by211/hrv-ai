package quest.byai.hrv.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long? = null,
    val status: String,
    val initialRate: Double,
    val finalRate: Double,
    val inhaleFraction: Double,
    val durationSeconds: Long = 0,
    val averageHeartRate: Double? = null,
    val rmssdMs: Double? = null,
    val sdnnMs: Double? = null,
    val eliteHrvScore: Double? = null,
    val lnRmssd: Double? = null,
    val eliteArtifactPercent: Double? = null,
    val resonanceScore: Double? = null,
    val confidence: Double? = null,
    val usableDataFraction: Double? = null,
    val ease: Int? = null,
    val symptomFlags: String = "",
    val notes: String = "",
    val analysisVersion: Int = 2,
)

@Entity(
    tableName = "rr_samples",
    foreignKeys = [ForeignKey(
        entity = SessionEntity::class,
        parentColumns = ["id"],
        childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("sessionId")],
)
data class RrSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val elapsedRealtimeMs: Long,
    val rawRrMs: Int,
    val analysisRrMs: Double?,
    val qualityFlags: String,
    val heartRateBpm: Int,
    val contactStatus: Boolean?,
)

@Entity(
    tableName = "breathing_segments",
    foreignKeys = [ForeignKey(
        entity = SessionEntity::class,
        parentColumns = ["id"],
        childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("sessionId")],
)
data class BreathingSegmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val startedAtElapsedMs: Long,
    val endedAtElapsedMs: Long? = null,
    val breathsPerMinute: Double,
    val inhaleFraction: Double,
    val controllerAction: String,
    val reason: String,
)

@Entity(
    tableName = "analysis_windows",
    foreignKeys = [ForeignKey(
        entity = SessionEntity::class,
        parentColumns = ["id"],
        childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("sessionId")],
)
data class AnalysisWindowEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val endedAtElapsedMs: Long,
    val breathsPerMinute: Double,
    val score: Double,
    val confidence: Double,
    val targetAmplitudeBpm: Double,
    val regularity: Double,
    val spectralConcentration: Double,
    val dominantFrequencyHz: Double,
    val usableDataFraction: Double,
    val qualified: Boolean,
    val rejectionReason: String?,
)
