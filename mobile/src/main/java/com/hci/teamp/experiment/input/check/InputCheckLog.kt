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

    private val rows = mutableListOf<Row>()
    val size: Int get() = rows.size

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

    fun toCsv(): String = buildString {
        appendLine("seq,attempt_id,kind,elapsed_realtime_ms,wall_clock,previous,current,source,detail")
        for (r in rows) {
            appendLine(
                listOf(r.seq, r.attemptId, r.kind, r.elapsedRealtimeMs, r.wallClock, r.previous ?: "", r.current ?: "", r.source, r.detail)
                    .joinToString(",") { csv(it.toString()) },
            )
        }
    }

    /** Download/HCI_Experiment/ 에 새 파일로 저장 (덮어쓰기 없음). 반환: 저장 위치 */
    fun export(context: Context): String {
        val name = "phase1_phone_input_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.csv"
        val text = toCsv()
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
