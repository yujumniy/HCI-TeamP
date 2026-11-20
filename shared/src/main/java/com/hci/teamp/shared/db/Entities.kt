package com.hci.teamp.shared.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 참가자. ID 는 seq 로부터 생성(P001…), seq UNIQUE 로 중복 방지.
 * 조건 순서·과제 순서 '계산'은 4번 담당이며, 여기는 등록 시 받은 값을 고정 저장만 한다(7.2: 재생성 금지).
 */
@Entity(tableName = "participants", indices = [Index(value = ["seq"], unique = true)])
data class ParticipantEntity(
    @PrimaryKey val participantId: String,
    val seq: Int,
    val groupId: String,            // G1~G4
    val conditionOrder: String,     // 예: "C1,C2,C4,C3"
    val taskOrderJson: String,      // 예: {"C1":["T01",...],...}
    val createdAtWallMs: Long,
    val finishedAtWallMs: Long? = null,
)

/**
 * 시도 1건. recordJson 이 원본(AttemptRecord JSON)이며, 나머지 열은 조회·CSV 용 사본이다.
 * 원본은 덮어쓰지 않는다. 예외: 같은 기기에서 IN_PROGRESS → 종료 상태로 '한 번' 확정되는 경우.
 */
@Entity(
    tableName = "attempts",
    indices = [Index("participantId"), Index("logicalTaskId"), Index("syncState")]
)
data class AttemptEntity(
    @PrimaryKey val attemptId: String,
    val logicalTaskId: String,
    val participantId: String,
    val conditionId: String,
    val taskId: String,
    val attemptNo: Int,
    val phase: String,
    val orderIndex: Int,
    val deviceType: String,
    val deviceModel: String,
    val startVolume: Int,
    val targetVolume: Int,
    val finalVolume: Int?,
    val endStatus: String,
    val elapsedMs: Double?,
    val startWallMs: Long,
    val endWallMs: Long?,
    val invalidReason: String?,
    val maxOvershoot: Int?,
    val overshootCount: Int?,
    val reachedTarget: Boolean?,
    val firstReachMs: Double?,
    val finalAbsError: Int?,
    val directionChanges: Int?,
    val pathDistance: Int?,
    val firstInputMs: Double?,
    val recordJson: String,
    val contentHash: String,
    val sourceDevice: String,
    val syncState: String,
    val receivedAtWallMs: Long?,
    val lastSyncAttemptWallMs: Long? = null,
    val syncAttempts: Int = 0,
)

@Entity(
    tableName = "events",
    indices = [Index(value = ["attemptId", "seq"], unique = true)]
)
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val attemptId: String,
    val seq: Int,
    val type: String,
    val elapsedNs: Long,
    val wallMs: Long,
    val value: Int?,
    val rawJson: String,
)

/** 명세서 12.3 DATA_CONFLICT. 기존 기록은 건드리지 않고 들어온 내용을 그대로 보존한다. */
@Entity(tableName = "conflicts", indices = [Index("attemptId")])
data class ConflictEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val attemptId: String,
    val existingHash: String,
    val incomingHash: String,
    val incomingRecordJson: String,
    val source: String,
    val detectedAtWallMs: Long,
    val resolved: Boolean = false,
    val resolutionNote: String? = null,
)

/** 워치가 받은 시행 설정. 앱 재시작 후에도 4번 UI 가 이어서 읽을 수 있도록 저장. */
@Entity(tableName = "received_configs")
data class ReceivedConfigEntity(
    @PrimaryKey val attemptId: String,
    val configJson: String,
    val receivedAtWallMs: Long,
    val state: String,   // RECEIVED, STARTED, FINISHED
)

/** 연결·동기화 상태 이벤트 (명세서 11.1 마지막 항목). */
@Entity(tableName = "sync_log")
data class SyncLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val wallMs: Long,
    val elapsedRealtimeMs: Long,
    val type: String,
    val attemptId: String?,
    val detail: String?,
)

/** 명세서 15.2·14장: 백업 성공과 검증 성공을 별도 상태로 기록. */
@Entity(tableName = "backup_log", indices = [Index("participantId")])
data class BackupLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val participantId: String?,     // null = 전체 내보내기
    val kind: String,               // AUTO_BACKUP, MANUAL_PARTICIPANT, MANUAL_ALL
    val fileName: String?,
    val uri: String?,
    val backupSuccess: Boolean,
    val validationPassed: Boolean?,
    val error: String?,
    val wallMs: Long,
)

/**
 * 정상 수신으로 처리하지 않은 워치 업로드 (해시 불일치, 진행 중 기록 등).
 * attempts 테이블에는 저장하지 않고, 받은 내용을 그대로 보존해 관리자가 검토한다.
 * errorType: HASH_MISMATCH / IN_PROGRESS_RECEIVED / IN_PROGRESS_SUPERSEDED
 */
@Entity(tableName = "receive_errors", indices = [Index("attemptId")])
data class ReceiveErrorEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val attemptId: String,
    val errorType: String,
    val sentHash: String,
    val computedHash: String,
    val endStatus: String,
    val payloadJson: String,
    val source: String,
    val detectedAtWallMs: Long,
    val resolved: Boolean = false,
    val resolutionNote: String? = null,
)
