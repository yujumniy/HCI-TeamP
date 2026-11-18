package com.hci.teamp.shared.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * 모든 INSERT 는 OnConflictStrategy.ABORT — 기존 기록을 자동으로 덮어쓰지 않는다(12.3).
 * DELETE 쿼리는 의도적으로 두지 않는다(명세서 18장: 기록 데이터 자동 삭제 금지).
 */
@Dao
interface ExperimentDao {

    // ---- participants ----
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertParticipant(p: ParticipantEntity)

    @Query("SELECT COALESCE(MAX(seq), 0) FROM participants")
    suspend fun maxParticipantSeq(): Int

    @Query("SELECT * FROM participants WHERE participantId = :id")
    suspend fun participant(id: String): ParticipantEntity?

    @Query("SELECT * FROM participants ORDER BY seq")
    suspend fun allParticipants(): List<ParticipantEntity>

    @Query("SELECT * FROM participants ORDER BY seq")
    fun observeParticipants(): Flow<List<ParticipantEntity>>

    @Query("UPDATE participants SET finishedAtWallMs = :wallMs WHERE participantId = :id")
    suspend fun markParticipantFinished(id: String, wallMs: Long)

    // ---- attempts ----
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAttempt(a: AttemptEntity)

    @Update
    suspend fun updateAttempt(a: AttemptEntity)

    @Query("SELECT * FROM attempts WHERE attemptId = :id")
    suspend fun attempt(id: String): AttemptEntity?

    @Query("SELECT * FROM attempts WHERE participantId = :pid ORDER BY startWallMs, attemptId")
    suspend fun attemptsOf(pid: String): List<AttemptEntity>

    @Query("SELECT * FROM attempts ORDER BY participantId, startWallMs, attemptId")
    suspend fun allAttempts(): List<AttemptEntity>

    @Query("SELECT COALESCE(MAX(attemptNo), 0) FROM attempts WHERE logicalTaskId = :logicalTaskId")
    suspend fun maxAttemptNo(logicalTaskId: String): Int

    @Query("SELECT * FROM attempts WHERE endStatus = 'IN_PROGRESS'")
    suspend fun inProgressAttempts(): List<AttemptEntity>

    @Query("SELECT * FROM attempts WHERE syncState = :state ORDER BY startWallMs")
    suspend fun attemptsBySyncState(state: String): List<AttemptEntity>

    @Query("SELECT COUNT(*) FROM attempts WHERE syncState = :state")
    suspend fun countBySyncState(state: String): Int

    @Query("SELECT COUNT(*) FROM attempts WHERE syncState = :state")
    fun observeCountBySyncState(state: String): Flow<Int>

    @Query("UPDATE attempts SET syncState = :state WHERE attemptId = :id")
    suspend fun setSyncState(id: String, state: String)

    @Query("UPDATE attempts SET lastSyncAttemptWallMs = :wallMs, syncAttempts = syncAttempts + 1 WHERE attemptId = :id")
    suspend fun markSyncTried(id: String, wallMs: Long)

    @Query("SELECT attemptId FROM attempts WHERE syncState = 'SYNCED' ORDER BY endWallMs DESC LIMIT 1")
    suspend fun lastSyncedAttemptId(): String?

    // ---- events ----
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertEvents(events: List<EventEntity>)

    @Query("SELECT * FROM events WHERE attemptId = :attemptId ORDER BY seq")
    suspend fun eventsOf(attemptId: String): List<EventEntity>

    @Query("SELECT e.* FROM events e JOIN attempts a ON a.attemptId = e.attemptId WHERE a.participantId = :pid ORDER BY e.attemptId, e.seq")
    suspend fun eventsOfParticipant(pid: String): List<EventEntity>

    @Query("SELECT * FROM events ORDER BY attemptId, seq")
    suspend fun allEvents(): List<EventEntity>

    /** 시도 테이블에 없는 attemptId 를 가진 이벤트 (무결성 검사 2번) */
    @Query("SELECT DISTINCT e.attemptId FROM events e LEFT JOIN attempts a ON a.attemptId = e.attemptId WHERE a.attemptId IS NULL")
    suspend fun orphanEventAttemptIds(): List<String>

    // ---- conflicts ----
    @Insert
    suspend fun insertConflict(c: ConflictEntity): Long

    @Query("SELECT * FROM conflicts WHERE resolved = 0 ORDER BY detectedAtWallMs")
    suspend fun unresolvedConflicts(): List<ConflictEntity>

    @Query("SELECT * FROM conflicts ORDER BY detectedAtWallMs")
    suspend fun allConflicts(): List<ConflictEntity>

    @Query("SELECT COUNT(*) FROM conflicts WHERE resolved = 0")
    fun observeUnresolvedConflictCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM conflicts WHERE attemptId = :attemptId AND incomingHash = :hash")
    suspend fun conflictExists(attemptId: String, hash: String): Int

    /** 관리자가 검토 후 '해결 표시'만 한다. 어떤 기록도 삭제·수정하지 않는다. */
    @Query("UPDATE conflicts SET resolved = 1, resolutionNote = :note WHERE id = :id")
    suspend fun markConflictResolved(id: Long, note: String)

    // ---- received configs (watch) ----
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertConfig(c: ReceivedConfigEntity)

    @Query("SELECT * FROM received_configs WHERE attemptId = :id")
    suspend fun config(id: String): ReceivedConfigEntity?

    @Query("SELECT * FROM received_configs WHERE state = 'RECEIVED' ORDER BY receivedAtWallMs DESC LIMIT 1")
    suspend fun latestUnstartedConfig(): ReceivedConfigEntity?

    @Query("SELECT * FROM received_configs WHERE state = 'RECEIVED' ORDER BY receivedAtWallMs DESC LIMIT 1")
    fun observeLatestUnstartedConfig(): Flow<ReceivedConfigEntity?>

    @Query("UPDATE received_configs SET state = :state WHERE attemptId = :id")
    suspend fun setConfigState(id: String, state: String)

    // ---- sync log ----
    @Insert
    suspend fun insertSyncLog(l: SyncLogEntity)

    @Query("SELECT * FROM sync_log ORDER BY id")
    suspend fun allSyncLog(): List<SyncLogEntity>

    // ---- backup log ----
    @Insert
    suspend fun insertBackupLog(b: BackupLogEntity)

    @Query("SELECT * FROM backup_log WHERE participantId = :pid ORDER BY wallMs DESC")
    suspend fun backupLogOf(pid: String): List<BackupLogEntity>

    @Query("SELECT * FROM backup_log ORDER BY wallMs DESC")
    fun observeBackupLog(): Flow<List<BackupLogEntity>>
}
