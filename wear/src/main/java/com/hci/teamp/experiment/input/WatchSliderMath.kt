package com.hci.teamp.experiment.input

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * C4 슬라이더 좌표 ↔ 값 계산 (순수 로직, 단위 테스트 대상).
 * 팀원 1의 폰 SliderMath와 같은 규칙입니다. 공통 모듈로 옮길지는 팀 합의 후 결정합니다.
 */
object WatchSliderMath {
    fun normalize(percent: Int): Int = (percent.coerceIn(0, 100) / 5f).roundToInt() * 5

    /** 손잡이 중심 x */
    fun thumbX(percent: Int, left: Float, right: Float): Float = left + (right - left) * (percent / 100f)

    /** 트랙 위 x → 5단위 값 */
    fun valueAt(x: Float, left: Float, right: Float): Int {
        val frac = ((x - left) / (right - left)).coerceIn(0f, 1f)
        return normalize((frac * 100).roundToInt())
    }

    /**
     * 누른 위치가 손잡이 터치 영역 안인지.
     * 워치는 손가락이 화면에 비해 커서 가로·세로 모두 확인합니다.
     */
    fun hitsThumb(downX: Float, downY: Float, thumbX: Float, centerY: Float, touchSize: Float): Boolean =
        abs(downX - thumbX) <= touchSize / 2 && abs(downY - centerY) <= touchSize / 2
}
