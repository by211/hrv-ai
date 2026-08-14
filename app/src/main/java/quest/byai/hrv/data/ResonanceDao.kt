package quest.byai.hrv.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ResonanceDao {
    @Insert suspend fun insertSession(session: SessionEntity): Long
    @Update suspend fun updateSession(session: SessionEntity)
    @Insert suspend fun insertRrSamples(samples: List<RrSampleEntity>)
    @Insert suspend fun insertBreathingSegment(segment: BreathingSegmentEntity): Long
    @Insert suspend fun insertAnalysisWindow(window: AnalysisWindowEntity)

    @Query("SELECT * FROM sessions ORDER BY startedAtEpochMs DESC")
    fun observeSessions(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE id = :sessionId")
    suspend fun getSession(sessionId: Long): SessionEntity?

    @Query("SELECT * FROM rr_samples WHERE sessionId = :sessionId ORDER BY elapsedRealtimeMs")
    suspend fun getRrSamples(sessionId: Long): List<RrSampleEntity>

    @Query("SELECT * FROM breathing_segments WHERE sessionId = :sessionId ORDER BY startedAtElapsedMs")
    suspend fun getBreathingSegments(sessionId: Long): List<BreathingSegmentEntity>

    @Query("SELECT * FROM analysis_windows WHERE sessionId = :sessionId ORDER BY endedAtElapsedMs")
    suspend fun getAnalysisWindows(sessionId: Long): List<AnalysisWindowEntity>

    @Query("DELETE FROM sessions WHERE id = :sessionId")
    suspend fun deleteSession(sessionId: Long)

    @Query("DELETE FROM sessions")
    suspend fun deleteAllSessions()

    @Query("UPDATE sessions SET status = 'CANCELLED', endedAtEpochMs = :endedAtEpochMs WHERE status NOT IN ('COMPLETE', 'CANCELLED')")
    suspend fun cancelInterruptedSessions(endedAtEpochMs: Long)
}
