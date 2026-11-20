package com.hci.teamp.shared.export

import com.hci.teamp.shared.db.AttemptEntity
import com.hci.teamp.shared.db.ConflictEntity
import com.hci.teamp.shared.db.EventEntity
import com.hci.teamp.shared.db.ParticipantEntity
import com.hci.teamp.shared.db.SyncLogEntity
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 명세서 11.2 CSV. UTF-8 BOM 포함(엑셀 한글 깨짐 방지), RFC 4180 따옴표 규칙.
 * 값은 DB 원본 그대로 내보내며 변환·보정하지 않는다(시각 열만 ISO 문자열 추가).
 */
object CsvExport {

    private val ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX").withZone(ZoneId.of("Asia/Seoul"))

    fun iso(wallMs: Long?): String = wallMs?.let { ISO.format(Instant.ofEpochMilli(it)) } ?: ""

    fun esc(v: Any?): String {
        val s = when (v) {
            null -> ""
            is Double -> String.format(Locale.US, "%.3f", v)
            else -> v.toString()
        }
        return if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }

    private fun row(vararg v: Any?): String = v.joinToString(",") { esc(it) } + "\r\n"

    fun trialsCsv(attempts: List<AttemptEntity>): String = buildString {
        append(
            row(
                "attempt_id", "logical_task_id", "participant_id", "condition_id", "task_id", "attempt_no",
                "phase", "order_index", "device_type", "device_model", "start_volume", "target_volume",
                "final_volume", "end_status", "completion_time_ms", "elapsed_ms", "max_overshoot",
                "overshoot_count", "reached_target", "first_reach_ms", "final_abs_error", "direction_changes",
                "path_distance", "first_input_ms", "invalid_reason", "included_in_main_analysis",
                "start_wall_time", "end_wall_time", "source_device", "sync_state", "content_hash",
            )
        )
        for (a in attempts) {
            val completion = if (a.endStatus == "COMPLETED") a.elapsedMs else null
            val included = a.phase == "MAIN" && (a.endStatus == "COMPLETED" || a.endStatus == "TIMEOUT")
            append(
                row(
                    a.attemptId, a.logicalTaskId, a.participantId, a.conditionId, a.taskId, a.attemptNo,
                    a.phase, a.orderIndex, a.deviceType, a.deviceModel, a.startVolume, a.targetVolume,
                    a.finalVolume, a.endStatus, completion, a.elapsedMs, a.maxOvershoot,
                    a.overshootCount, a.reachedTarget, a.firstReachMs, a.finalAbsError, a.directionChanges,
                    a.pathDistance, a.firstInputMs, a.invalidReason, included,
                    iso(a.startWallMs), iso(a.endWallMs), a.sourceDevice, a.syncState, a.contentHash,
                )
            )
        }
    }

    fun eventsCsv(events: List<EventEntity>): String = buildString {
        append(row("attempt_id", "event_seq", "event_type", "elapsed_ms", "elapsed_ns", "value", "wall_time", "raw_json"))
        for (e in events) {
            append(row(e.attemptId, e.seq, e.type, e.elapsedNs / 1_000_000.0, e.elapsedNs, e.value, iso(e.wallMs), e.rawJson))
        }
    }

    fun participantsCsv(ps: List<ParticipantEntity>): String = buildString {
        append(row("participant_id", "seq", "group_id", "condition_order", "task_order_json", "created_at", "finished_at"))
        for (p in ps) append(row(p.participantId, p.seq, p.groupId, p.conditionOrder, p.taskOrderJson, iso(p.createdAtWallMs), iso(p.finishedAtWallMs)))
    }

    fun conflictsCsv(cs: List<ConflictEntity>): String = buildString {
        append(row("conflict_id", "attempt_id", "existing_hash", "incoming_hash", "source", "detected_at", "resolved", "resolution_note", "incoming_record_json"))
        for (c in cs) append(row(c.id, c.attemptId, c.existingHash, c.incomingHash, c.source, iso(c.detectedAtWallMs), c.resolved, c.resolutionNote, c.incomingRecordJson))
    }

    fun syncLogCsv(ls: List<SyncLogEntity>): String = buildString {
        append(row("id", "wall_time", "elapsed_realtime_ms", "type", "attempt_id", "detail"))
        for (l in ls) append(row(l.id, iso(l.wallMs), l.elapsedRealtimeMs, l.type, l.attemptId, l.detail))
    }

    /** 파일명 → 내용. 순서대로 ZIP 에 기록. */
    fun writeZip(out: OutputStream, files: List<Pair<String, String>>) {
        ZipOutputStream(out).use { zip ->
            for ((name, content) in files) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }
}
