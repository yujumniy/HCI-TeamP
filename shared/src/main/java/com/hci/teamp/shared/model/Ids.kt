package com.hci.teamp.shared.model

/**
 * 명세서 12장 ID 규칙.
 *
 *  참가자    : P001
 *  논리 과제 : P001-C3-T04      (연습: P001-C3-PR01)
 *  시도      : P001-C3-T04-A01  (재시도 시 A02, A03 …)
 *
 * 연습 과제 ID(PR01, PR02)와 본 과제 ID(T01~T08)는 접두어가 달라 충돌하지 않는다.
 * 과제 숫자·순서는 이 파일에서 정의하지 않는다(4번 담당). 여기서는 형식만 다룬다.
 */
object Ids {
    private val PARTICIPANT = Regex("^P(\\d{3,})$")
    private val TASK = Regex("^(T0[1-8]|PR0[1-2])$")
    private val ATTEMPT = Regex("^(P\\d{3,})-(C[1-4])-(T0[1-8]|PR0[1-2])-A(\\d{2,})$")

    fun participantId(seq: Int): String {
        require(seq >= 1) { "participant seq must be >= 1" }
        return "P%03d".format(seq)
    }

    fun participantSeq(participantId: String): Int =
        PARTICIPANT.matchEntire(participantId)?.groupValues?.get(1)?.toInt()
            ?: throw IllegalArgumentException("invalid participant id: $participantId")

    fun logicalTaskId(participantId: String, condition: ConditionId, taskId: String): String {
        require(PARTICIPANT.matches(participantId)) { "invalid participant id: $participantId" }
        require(TASK.matches(taskId)) { "invalid task id: $taskId" }
        return "$participantId-${condition.name}-$taskId"
    }

    fun attemptId(participantId: String, condition: ConditionId, taskId: String, attemptNo: Int): String {
        require(attemptNo >= 1) { "attemptNo must be >= 1" }
        return "${logicalTaskId(participantId, condition, taskId)}-A%02d".format(attemptNo)
    }

    data class ParsedAttempt(
        val participantId: String,
        val condition: ConditionId,
        val taskId: String,
        val attemptNo: Int,
    ) {
        val phase: TrialPhase get() = if (taskId.startsWith("PR")) TrialPhase.PRACTICE else TrialPhase.MAIN
        val logicalTaskId: String get() = "$participantId-${condition.name}-$taskId"
    }

    fun parseAttemptId(attemptId: String): ParsedAttempt {
        val m = ATTEMPT.matchEntire(attemptId)
            ?: throw IllegalArgumentException("invalid attempt id: $attemptId")
        val (p, c, t, a) = m.destructured
        return ParsedAttempt(p, ConditionId.valueOf(c), t, a.toInt())
    }

    fun isValidAttemptId(attemptId: String): Boolean = ATTEMPT.matches(attemptId)
}
