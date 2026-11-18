package com.hci.teamp.experiment.input.check

import android.content.Context
import android.util.Log
import com.hci.teamp.experiment.input.WatchRawInput
import com.hci.teamp.shared.experiment.InputEvent
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Phase 1 C3·C4 검증용 기록 (명세 3장 T2, 17장).
 * Logcat(태그 HCI_WEAR_INPUT)에 즉시 출력하고 CSV로 내보냅니다. 형식은 팀원 1의 폰 검증 CSV와 맞췄습니다.
 * 실험 본 데이터 저장(팀원 3 담당)과는 별개입니다.
 */
class WatchInputCheckLog {
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

    data class TrialRow(
        val attemptId: String,
        val condition: String,
        val checkItem: String,
        val taskId: String,
        val start: Int,
        val target: Int,
        val outcome: String,
        val durationMs: Long,
        val finalValue: Int,
        val valueChanges: Int,
        val rotaryEvents: Int,
        val gestures: Int,
        val trackTapBlocked: Int,
        val judged: String,
        val bezelConfig: String,
        val wallClock: Long = System.currentTimeMillis(),
    )

    private val rows = mutableListOf<Row>()
    private val trials = mutableListOf<TrialRow>()
    val size: Int get() = rows.size
    val trialCount: Int get() = trials.size

    fun value(e: InputEvent, source: String, accepted: Boolean) = add(
        e.attemptId, "VALUE_CHANGE", e.elapsedRealtimeMs, e.previous.percent, e.current.percent, source,
        if (accepted) "" else "rejected_by_trial_controller",
    )

    fun raw(r: WatchRawInput) = add(r.attemptId, r.kind.name, r.elapsedRealtimeMs, null, r.value, r.source, r.detail)

    fun note(attemptId: String, kind: String, elapsedMs: Long, value: Int?, source: String, detail: String) =
        add(attemptId, kind, elapsedMs, null, value, source, detail)

    fun trial(t: TrialRow) {
        trials += t
        Log.d(TAG, "TRIAL ${t.attemptId} ${t.checkItem} ${t.outcome} ${t.durationMs}ms final=${t.finalValue} judged=${t.judged}")
    }

    /** 가장 최근 시행의 판정(성공/실패)을 바꿉니다. */
    fun judgeLast(attemptId: String, judged: String) {
        val i = trials.indexOfLast { it.attemptId == attemptId }
        if (i >= 0) trials[i] = trials[i].copy(judged = judged)
    }

    private fun add(a: String, kind: String, t: Long, prev: Int?, cur: Int?, src: String, detail: String) {
        val row = Row(rows.size + 1, a, kind, t, System.currentTimeMillis(), prev, cur, src, detail)
        rows += row
        Log.d(TAG, "#${row.seq} $kind a=$a t=$t ${prev ?: ""}->${cur ?: ""} $src $detail")
    }

    fun trialsCsv(): String = buildString {
        appendLine(
            "attempt_id,condition,check_item,task_id,start,target,outcome,duration_ms,final_value,abs_error," +
                "value_changes,rotary_events,gestures,track_tap_blocked,judged,bezel_config,wall_clock",
        )
        for (t in trials) {
            appendLine(
                listOf(
                    t.attemptId, t.condition, t.checkItem, t.taskId, t.start, t.target, t.outcome, t.durationMs,
                    t.finalValue, kotlin.math.abs(t.finalValue - t.target), t.valueChanges, t.rotaryEvents, t.gestures,
                    t.trackTapBlocked, t.judged, t.bezelConfig, t.wallClock,
                ).joinToString(",") { csv(it.toString()) },
            )
        }
    }

    fun eventsCsv(): String = buildString {
        appendLine("seq,attempt_id,kind,elapsed_realtime_ms,wall_clock,previous,current,source,detail")
        for (r in rows) {
            appendLine(
                listOf(r.seq, r.attemptId, r.kind, r.elapsedRealtimeMs, r.wallClock, r.previous ?: "", r.current ?: "", r.source, r.detail)
                    .joinToString(",") { csv(it.toString()) },
            )
        }
    }

    /**
     * 워치 앱 저장소에 새 파일 2개를 저장합니다 (덮어쓰기 없음).
     * PC로 가져오기: adb pull /sdcard/Android/data/com.hci.teamp/files/HCI_Experiment/
     */
    fun export(context: Context): List<String> {
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "HCI_Experiment").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val files = listOf(
            File(dir, "phase1_wear_input_trials_$stamp.csv") to trialsCsv(),
            File(dir, "phase1_wear_input_events_$stamp.csv") to eventsCsv(),
        )
        return files.map { (f, text) ->
            check(!f.exists()) { "같은 이름의 파일이 있습니다: ${f.name}" }
            f.writeText(text)
            Log.d(TAG, "EXPORT ${f.absolutePath}")
            f.name
        }
    }

    companion object {
        const val TAG = "HCI_WEAR_INPUT"
        private fun csv(s: String) =
            if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }
}
