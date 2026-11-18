package com.hci.teamp.mobile.data

import com.hci.teamp.shared.db.ExperimentRepository
import com.hci.teamp.shared.model.ConditionId
import com.hci.teamp.shared.model.EndStatus
import com.hci.teamp.shared.model.EventType

/**
 * 명세서 14장 참가자 종료 시 데이터 무결성 검사.
 * 원본을 수정·삭제하지 않고 문제 목록만 만든다. 결과는 integrity_report.csv 로 백업에 포함된다.
 */
class IntegrityChecker(private val repo: ExperimentRepository) {

    data class Issue(
        val check: Int,           // 명세서 14장 항목 번호 1~9
        val severity: String,     // ERROR / WARNING
        val participantId: String,
        val conditionId: String?,
        val taskId: String?,
        val attemptId: String?,
        val message: String,
    )

    data class Report(val participantId: String, val issues: List<Issue>) {
        val passed: Boolean get() = issues.none { it.severity == "ERROR" }
        val needsReview: Boolean get() = issues.isNotEmpty()
    }

    /**
     * @param watchPendingCount PhoneSyncClient.requestWatchStatus() 의 pendingCount. 확인 불가 시 null(→ 경고).
     */
    suspend fun check(participantId: String, watchPendingCount: Int?): Report {
        val dao = repo.dao
        val issues = mutableListOf<Issue>()
        val attempts = dao.attemptsOf(participantId)
        val taskOrders = repo.taskOrdersOf(participantId)

        fun add(check: Int, sev: String, msg: String, cond: String? = null, task: String? = null, att: String? = null) {
            issues += Issue(check, sev, participantId, cond, task, att, msg)
        }

        // 1. 본 실험 32개 과제 종료 결과 존재
        if (taskOrders == null) {
            add(1, "ERROR", "참가자 과제 순서 정보 없음")
        } else {
            for (c in ConditionId.entries) {
                val tasks = taskOrders[c].orEmpty()
                if (tasks.size != 8) add(1, "ERROR", "${c.name} 과제 수 ${tasks.size} (8 필요)", c.name)
                for (t in tasks) {
                    val ok = attempts.any {
                        it.conditionId == c.name && it.taskId == t && it.phase == "MAIN" &&
                            (it.endStatus == EndStatus.COMPLETED.name || it.endStatus == EndStatus.TIMEOUT.name)
                    }
                    if (!ok) add(1, "ERROR", "종료 결과(COMPLETED/TIMEOUT) 없음", c.name, t)
                }
            }
        }

        // 2. 시행 ID ↔ 이벤트 연결
        dao.orphanEventAttemptIds().forEach { add(2, "ERROR", "시도 레코드 없는 이벤트", att = it) }

        for (a in attempts) {
            val ev = dao.eventsOf(a.attemptId)
            val interrupted = a.invalidReason == "APP_TERMINATED_DURING_TRIAL"
            fun a2(check: Int, sev: String, msg: String) = add(check, sev, msg, a.conditionId, a.taskId, a.attemptId)

            if (ev.isEmpty()) a2(2, "ERROR", "이벤트 없음")

            // 3. 이벤트 순서 번호 유일·연속
            val seqs = ev.map { it.seq }
            if (seqs.toSet().size != seqs.size) a2(3, "ERROR", "이벤트 순서 번호 중복")
            if (seqs != seqs.indices.toList()) a2(3, "WARNING", "이벤트 순서 번호가 0부터 연속되지 않음")

            // 4. 시간 순서·경과 시간 일관성
            if (ev.isNotEmpty()) {
                if (ev.first().type != EventType.START.name || ev.first().elapsedNs != 0L) a2(4, "ERROR", "첫 이벤트가 START(0ns)가 아님")
                if (ev.zipWithNext().any { (x, y) -> y.elapsedNs < x.elapsedNs }) a2(4, "ERROR", "이벤트 시각 역행")
                val endTypes = setOf(EventType.COMPLETE.name, EventType.TIMEOUT.name, EventType.INVALID.name)
                val last = ev.last()
                if (!interrupted) {
                    if (last.type !in endTypes) a2(4, "ERROR", "마지막 이벤트가 종료 이벤트가 아님")
                    val expected = when (a.endStatus) {
                        EndStatus.COMPLETED.name -> EventType.COMPLETE.name
                        EndStatus.TIMEOUT.name -> EventType.TIMEOUT.name
                        EndStatus.INVALID.name -> EventType.INVALID.name
                        else -> null
                    }
                    if (expected != null && last.type != expected) a2(4, "ERROR", "종료 상태(${a.endStatus})와 종료 이벤트(${last.type}) 불일치")
                    val elapsed = a.elapsedMs
                    if (elapsed != null && kotlin.math.abs(elapsed - last.elapsedNs / 1e6) > 0.01) a2(4, "ERROR", "elapsed_ms 와 종료 이벤트 시각 불일치")
                }
                if (a.endStatus == EndStatus.TIMEOUT.name) {
                    val ms = a.elapsedMs ?: 0.0
                    if (ms < 29_000 || ms > 32_000) a2(4, "WARNING", "TIMEOUT 경과 시간 ${"%.0f".format(ms)}ms (30초 기준 확인)")
                }
            }

            // 5. 음량 범위·5단위
            fun okVol(v: Int?) = v == null || (v in 0..100 && v % 5 == 0)
            if (!okVol(a.startVolume) || !okVol(a.targetVolume) || !okVol(a.finalVolume)) a2(5, "ERROR", "시작/목표/최종 음량 규칙 위반")
            ev.filter { it.type == EventType.VALUE_CHANGE.name }.forEach {
                if (!okVol(it.value)) a2(5, "ERROR", "VALUE_CHANGE 값 규칙 위반: ${it.value} (seq ${it.seq})")
            }

            // 6. 최종 음량 = 마지막 상태
            if (!interrupted && a.endStatus != EndStatus.IN_PROGRESS.name) {
                val lastValue = ev.lastOrNull { it.type == EventType.VALUE_CHANGE.name }?.value ?: a.startVolume
                if (a.finalVolume != lastValue) a2(6, "ERROR", "최종 음량 ${a.finalVolume} ≠ 마지막 값 $lastValue")
            }

            if (a.endStatus == EndStatus.IN_PROGRESS.name) a2(1, "ERROR", "IN_PROGRESS 상태로 남은 시도")
        }

        // 7. 동일 시도 중복 저장 — PK·UNIQUE 로 차단되지만 논리 과제 단위 중복 완료를 경고
        attempts.filter { it.phase == "MAIN" && it.endStatus != EndStatus.INVALID.name }
            .groupBy { it.logicalTaskId }
            .filter { it.value.size > 1 }
            .forEach { (lt, list) -> add(7, "WARNING", "논리 과제 $lt 에 유효 시도 ${list.size}건: ${list.joinToString { it.attemptId }}") }

        // 8. 워치 미전송
        when {
            watchPendingCount == null -> add(8, "WARNING", "워치 미전송 여부 확인 불가(워치 응답 없음)")
            watchPendingCount > 0 -> add(8, "ERROR", "워치 미전송 기록 ${watchPendingCount}건")
        }

        // 9. 미해결 DATA_CONFLICT
        dao.unresolvedConflicts().filter { it.attemptId.startsWith("$participantId-") }
            .forEach { add(9, "ERROR", "미해결 DATA_CONFLICT (incoming ${it.incomingHash.take(12)})", att = it.attemptId) }

        repo.log("INTEGRITY_CHECK", null, "$participantId issues=${issues.size} passed=${issues.none { it.severity == "ERROR" }}")
        return Report(participantId, issues)
    }

    companion object {
        fun toCsv(reports: List<Report>): String = buildString {
            append("participant_id,check_no,severity,condition_id,task_id,attempt_id,message\r\n")
            for (r in reports) for (i in r.issues) {
                append(listOf(i.participantId, i.check, i.severity, i.conditionId, i.taskId, i.attemptId, i.message)
                    .joinToString(",") { com.hci.teamp.shared.export.CsvExport.esc(it) })
                append("\r\n")
            }
        }
    }
}
