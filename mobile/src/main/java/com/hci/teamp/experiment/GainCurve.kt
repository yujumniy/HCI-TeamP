package com.hci.teamp.experiment

import kotlin.math.pow

/**
 * 가상 음량(0~100) → 앱 플레이어 게인(0.0~1.0).
 * 명세 4.1: 음량값과 데시벨의 직접 대응을 가정하지 않음. 9.3: 앱 내부 오디오 음량만 변경.
 *
 * 사람 귀는 크기를 로그로 느끼기 때문에 선형 게인에서는 50 이상 구간의 차이가 잘 안 들립니다.
 * 파일럿에서 아래 곡선을 비교해 하나로 확정합니다 (명세 19장). 확정 전 기본값은 기존과 같은 LINEAR.
 */
enum class GainCurve(val label: String, val description: String) {
    LINEAR("선형", "게인 = 음량/100 (기존 방식)"),
    SQUARE("제곱", "게인 = (음량/100)² · 낮은 음량 구간 차이가 커짐"),
    DB40("로그 40dB", "100에서 0dB, 5에서 약 -38dB · 단계마다 비슷한 크기 차이로 들리도록"),
    ;

    fun gain(volume: Int): Float {
        val v = volume.coerceIn(0, 100)
        if (v == 0) return 0f
        val x = v / 100f
        return when (this) {
            LINEAR -> x
            SQUARE -> x * x
            DB40 -> 10f.pow((x - 1f) * 40f / 20f)
        }
    }

    companion object {
        fun fromName(name: String?): GainCurve = entries.firstOrNull { it.name == name } ?: LINEAR
    }
}
