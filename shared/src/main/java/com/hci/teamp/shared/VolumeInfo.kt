package com.hci.teamp.shared

import android.media.AudioManager
import kotlin.math.roundToInt

/** 한 오디오 스트림의 현재 볼륨 상태 */
data class VolumeInfo(
    val stream: Int,
    val level: Int,
    val min: Int,
    val max: Int,
) {
    /** 전체 단계 수 (예: 0~15 → 15단계) */
    val steps: Int get() = (max - min).coerceAtLeast(0)

    /** 현재 단계 (0부터) */
    val step: Int get() = (level - min).coerceIn(0, steps)

    /** 0~100% */
    val percent: Int get() = if (steps == 0) 0 else (step * 100f / steps).roundToInt()

    val label: String get() = streamLabel(stream)

    /** "60% · 9/15단계" */
    val summary: String get() = "$percent% · $step/${steps}단계"

    fun encode(): ByteArray = "$stream,$level,$min,$max".toByteArray()

    companion object {
        fun decode(bytes: ByteArray): VolumeInfo? = runCatching {
            val (s, l, mn, mx) = String(bytes).split(",").map { it.trim().toInt() }
            VolumeInfo(s, l, mn, mx)
        }.getOrNull()
    }
}

/** 화면에 보여줄 스트림 목록 */
val WATCHED_STREAMS = listOf(
    AudioManager.STREAM_MUSIC,
    AudioManager.STREAM_RING,
    AudioManager.STREAM_NOTIFICATION,
    AudioManager.STREAM_ALARM,
    AudioManager.STREAM_VOICE_CALL,
)

fun streamLabel(stream: Int): String = when (stream) {
    AudioManager.STREAM_MUSIC -> "미디어"
    AudioManager.STREAM_RING -> "벨소리"
    AudioManager.STREAM_NOTIFICATION -> "알림"
    AudioManager.STREAM_ALARM -> "알람"
    AudioManager.STREAM_VOICE_CALL -> "통화"
    AudioManager.STREAM_SYSTEM -> "시스템"
    else -> "기타"
}
