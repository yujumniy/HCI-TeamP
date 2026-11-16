package com.hci.teamp.shared.experiment

/**
 * 명세 4.1 공통 음량 규칙.
 * 실제 시스템 음량이 아닌, 앱 내부의 가상 음량만 다룹니다.
 */
object VolumeRules {
    const val MIN = 0
    const val MAX = 100
    const val STEP = 5

    /** 0~100 범위로 제한하고 5단위로 맞춥니다. */
    fun normalize(value: Int): Int {
        val clamped = value.coerceIn(MIN, MAX)
        return Math.round(clamped / STEP.toFloat()) * STEP
    }

    /** direction: +1(증가) 또는 -1(감소). 경계값에서는 그대로 머뭅니다. */
    fun step(value: Int, direction: Int): Int = normalize(value + direction.coerceIn(-1, 1) * STEP)

    fun isValid(value: Int): Boolean = value in MIN..MAX && value % STEP == 0
}
