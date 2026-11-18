package com.hci.teamp.experiment.input.check

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.hci.teamp.experiment.input.RawInput
import com.hci.teamp.shared.experiment.InputEvent
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Phase 1 C1·C2 검증용 기록. Logcat(태그 HCI_INPUT)에 즉시 출력하고 CSV로 내보냅니다.
 * 실험 본 데이터 저장(팀원 3 담당 ExperimentStore)과는 별개입니다.
 */
class InputCheckLog {
    data class Row(
        val seq: Int,
        val attemptId: String,
        val kind: String,
        val elapsedRealtimeMs: Long,
        val wallClock: Long,
        val previous: Int?,
        val current: Int?,
        val source: String,
        val detail: String,
    )

    /** 시행 1회 요약 (trials CSV 한 줄) */
    data class TrialRow(
        val attemptId: String,
        val condition: String,
        val taskId: String,
        val start: Int,
        val target: Int,
        val outcome: String,
        val durationMs: Long,
        val firstInputMs: Long?,
        val stats: TrialStats,
        val moves: Int,
        val touchesWithoutChange: Int,
        val trackTapBlocked: Int,
        val wallClock: Long = System.currentTimeMillis(),
    )

    private val rows = mutableListOf<Row>()
    private val trials = mutableListOf<TrialRow>()
    val size: Int get() = rows.size
    val trialCount: Int get() = trials.size

    fun trial(t: TrialRow) {
        trials += t
        Log.d(TAG, "TRIAL ${t.attemptId} ${t.outcome} ${t.durationMs}ms final=${t.stats.finalValue} " +
            "over=${t.stats.maxOvershoot}/${t.stats.overshootCount} moves=${t.moves} changes=${t.stats.valueChanges}")
    }

    fun value(e: InputEvent, source: String, accepted: Boolean) = add(
        e.attemptId, "VALUE_CHANGE", e.elapsedRealtimeMs, e.previous.percent, e.current.percent, source,
        if (accepted) "" else "rejected_by_trial_controller",
    )

    fun raw(r: RawInput) = add(r.attemptId, r.kind.name, r.elapsedRealtimeMs, null, r.value, r.source, r.detail)

    fun note(attemptId: String, kind: String, elapsedMs: Long, value: Int?, detail: String) =
        add(attemptId, kind, elapsedMs, null, value, "", detail)

    private fun add(a: String, kind: String, t: Long, prev: Int?, cur: Int?, src: String, detail: String) {
        val row = Row(rows.size + 1, a, kind, t, System.currentTimeMillis(), prev, cur, src, detail)
        rows += row
        Log.d(TAG, "#${row.seq} $kind a=$a t=$t ${prev ?: ""}->${cur ?: ""} $src $detail")
    }

    fun trialsCsv(): String = buildString {
        appendLine(
            "attempt_id,condition,task_id,start,target,outcome,duration_ms,first_input_ms,final_value,abs_error," +
                "reached_target,max_overshoot,overshoot_count,moves,value_changes,touches_without_change,track_tap_blocked,wall_clock",
        )
        for (t in trials) {
            appendLine(
                listOf(
                    t.attemptId, t.condition, t.taskId, t.start, t.target, t.outcome, t.durationMs, t.firstInputMs ?: "",
                    t.stats.finalValue, t.stats.absError, t.stats.reachedTarget, t.stats.maxOvershoot, t.stats.overshootCount,
                    t.moves, t.stats.valueChanges, t.touchesWithoutChange, t.trackTapBlocked, t.wallClock,
                ).joinToString(",") { csv(it.toString()) },
            )
        }
    }

    fun toCsv(): String = buildString {
        appendLine("seq,attempt_id,kind,elapsed_realtime_ms,wall_clock,previous,current,source,detail")
        for (r in rows) {
            appendLine(
                listOf(r.seq, r.attemptId, r.kind, r.elapsedRealtimeMs, r.wallClock, r.previous ?: "", r.current ?: "", r.source, r.detail)
                    .joinToString(",") { csv(it.toString()) },
            )
        }
    }

    /**
     * Download/HCI_Experiment/ 에 두 파일을 새로 저장합니다 (덮어쓰기 없음).
     * - phase1_phone_input_trials_날짜.csv : 시행 1회 = 1줄 요약
     * - phase1_phone_input_events_날짜.csv : 입력·값 변경·상태 이벤트 전체
     * 반환: 저장 위치 목록
     */
    fun export(context: Context): List<String> {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return listOf(
            save(context, "phase1_phone_input_trials_$stamp.csv", trialsCsv()),
            save(context, "phase1_phone_input_events_$stamp.csv", toCsv()),
        )
    }

    private fun save(context: Context, name: String, text: String): String {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/HCI_Experiment")
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("저장 위치를 만들 수 없습니다")
            context.contentResolver.openOutputStream(uri)!!.use { it.write(text.toByteArray()) }
            return "Download/HCI_Experiment/$name"
        }
        val f = File(context.getExternalFilesDir(null), name)
        f.writeText(text)
        return f.absolutePath
    }

    companion object {
        const val TAG = "HCI_INPUT"
        private fun csv(s: String) =
            if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }
}
