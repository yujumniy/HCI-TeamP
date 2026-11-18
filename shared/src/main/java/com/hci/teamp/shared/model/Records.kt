package com.hci.teamp.shared.model

import kotlinx.serialization.Serializable

/**
 * 시행 설정. 4번(실험 진행)이 생성하고, 3번(통신)이 워치로 전달한다.
 * 스마트폰 조건(C1·C2)은 전송 없이 같은 객체를 그대로 AttemptRecorder 에 넘긴다.
 */
@Serializable
data class TrialConfig(
    val attemptId: String,
    val participantId: String,
    val condition: ConditionId,
    val taskId: String,          // T01~T08 또는 PR01, PR02
    val attemptNo: Int,
    val phase: TrialPhase,
    val startVolume: Int,
    val targetVolume: Int,
    /** 조건 내 과제 위치(1~8, 연습은 1~2). 분석용 메타데이터. */
    val orderIndex: Int,
    val timeoutMs: Long = 30_000L,
) {
    init {
        val parsed = Ids.parseAttemptId(attemptId)
        require(parsed.participantId == participantId) { "participantId mismatch" }
        require(parsed.condition == condition) { "condition mismatch" }
        require(parsed.taskId == taskId) { "taskId mismatch" }
        require(parsed.attemptNo == attemptNo) { "attemptNo mismatch" }
        require(parsed.phase == phase) { "phase mismatch" }
    }

    val logicalTaskId: String get() = Ids.logicalTaskId(participantId, condition, taskId)
    val device: DeviceType get() = condition.device
}

/**
 * 시행 중 발생한 이벤트 1건.
 * elapsedNs : 시행 기기의 단조 시계 기준, START 시점으로부터 경과 나노초 (통신 지연 미포함)
 * wallMs    : 참고용 벽시계 (기기 간 비교·분석에는 사용하지 않음)
 * raw       : 원시 입력 정보(keyCode, repeatCount, 회전량, x좌표 등). 값은 문자열로 보존.
 */
@Serializable
data class TrialEvent(
    val attemptId: String,
    val seq: Int,
    val type: EventType,
    val elapsedNs: Long,
    val wallMs: Long,
    val value: Int? = null,
    val raw: Map<String, String> = emptyMap(),
)

/** 공통 측정 함수(TrialMetrics)의 결과. 이벤트 로그로부터 언제든 재계산 가능. */
@Serializable
data class MetricsResult(
    val maxOvershoot: Int,
    val overshootCount: Int,
    val reachedTarget: Boolean,
    val firstReachMs: Double?,
    val finalAbsError: Int,
    val directionChanges: Int,
    val pathDistance: Int,
    val firstInputMs: Double?,
)

/** 시도 1건의 결과 요약. */
@Serializable
data class TrialResult(
    val config: TrialConfig,
    val deviceType: DeviceType,
    val deviceModel: String,
    val endStatus: EndStatus,
    val finalVolume: Int?,
    /** START → 종료 이벤트까지 경과(ms). COMPLETED 가 아닌 경우에도 기록하되 완료 시간으로 쓰지 않는다. */
    val elapsedMs: Double?,
    val startWallMs: Long,
    val endWallMs: Long?,
    val invalidReason: String? = null,
    val metrics: MetricsResult? = null,
) {
    /** 명세서 10.2: 완료 시간은 COMPLETED 일 때만 정의된다. */
    val completionTimeMs: Double? get() = if (endStatus == EndStatus.COMPLETED) elapsedMs else null
}

/** 저장·전송 단위. 결과와 이벤트는 항상 함께 다닌다(명세서 2장 원칙 2). */
@Serializable
data class AttemptRecord(
    val result: TrialResult,
    val events: List<TrialEvent>,
) {
    val attemptId: String get() = result.config.attemptId
}
