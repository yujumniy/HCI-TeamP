package com.hci.teamp.shared.log

import android.os.SystemClock
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 명세 11.1 이벤트 유형 (+ Phase 1 검증용) */
enum class EventType {
    START, INPUT_DOWN, INPUT_UP, VALUE_CHANGE, COMPLETE, TIMEOUT, INVALID,
    TRACK_TAP_BLOCKED, TEST_RESULT, SYSTEM_VOLUME_CHECK, NOTE,
}

/**
 * 하나의 이벤트. 원시 입력 정보는 별도 필드로 보존합니다.
 * elapsedMs: 기록 기준 시점(START 또는 로그 시작)부터의 단조 증가 시계(ms)
 */
data class LoggedEvent(
    val seq: Int,
    val attemptId: String,
    val type: EventType,
    val elapsedMs: Long,
    val uptimeMs: Long,
    val wallClock: Long,
    val value: Int? = null,
    val source: String = "",
    val raw: String = "",
)

/**
 * 이벤트 로거. Logcat(태그 HCI_EVENT)에 즉시 기록하고, 메모리에 보관해 CSV로 내보냅니다.
 * 이벤트를 임의로 생성하거나 보정하지 않습니다. 호출된 그대로만 기록합니다.
 */
class EventLog(private val device: String) {
    private val events = mutableListOf<LoggedEvent>()
    private var seq = 0
    private var baseUptime = SystemClock.elapsedRealtime()

    val size: Int get() = synchronized(this) { events.size }

    /** START 시점처럼 기준 시각을 다시 잡습니다. 반환값은 기준 uptime */
    fun resetClock(): Long = SystemClock.elapsedRealtime().also { baseUptime = it }

    fun elapsedNow(): Long = SystemClock.elapsedRealtime() - baseUptime

    fun log(
        type: EventType,
        attemptId: String = "",
        value: Int? = null,
        source: String = "",
        raw: String = "",
    ): LoggedEvent = synchronized(this) {
        val now = SystemClock.elapsedRealtime()
        val e = LoggedEvent(
            seq = ++seq,
            attemptId = attemptId,
            type = type,
            elapsedMs = now - baseUptime,
            uptimeMs = now,
            wallClock = System.currentTimeMillis(),
            value = value,
            source = source,
            raw = raw,
        )
        events += e
        Log.d(TAG, "[$device] #${e.seq} ${e.type} t=${e.elapsedMs} v=${value ?: ""} src=$source $raw")
        e
    }

    fun snapshot(): List<LoggedEvent> = synchronized(this) { events.toList() }

    fun recent(n: Int): List<LoggedEvent> = synchronized(this) { events.takeLast(n) }

    fun toCsv(): String = buildString {
        appendLine("device,seq,attempt_id,type,elapsed_ms,uptime_ms,wall_clock,wall_clock_iso,value,source,raw")
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)
        for (e in snapshot()) {
            appendLine(
                listOf(
                    device, e.seq, e.attemptId, e.type, e.elapsedMs, e.uptimeMs, e.wallClock,
                    iso.format(Date(e.wallClock)), e.value ?: "", e.source, e.raw,
                ).joinToString(",") { csv(it.toString()) },
            )
        }
    }

    companion object {
        const val TAG = "HCI_EVENT"

        fun csv(s: String): String =
            if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }
}
