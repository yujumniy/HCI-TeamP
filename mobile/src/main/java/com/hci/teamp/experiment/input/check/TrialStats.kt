package com.hci.teamp.experiment.input.check

import com.hci.teamp.shared.experiment.InputEvent
import kotlin.math.abs

/**
 * 시행 요약 지표 (명세 10장).
 * 이벤트 목록(실제 관찰된 값 변경)만으로 계산하므로 CSV에서 언제든 다시 계산할 수 있습니다.
 *
 * 공통 측정 함수는 명세상 두 기기가 같은 것을 써야 하므로, 팀 합의 후 shared로 옮길 예정입니다.
 */
data class TrialStats(
    val finalValue: Int,
    val absError: Int,
    val reachedTarget: Boolean,
    /** 명세 10.3 과제 방향 기준 목표를 넘어선 최대 거리 (안 넘었으면 0) */
    val maxOvershoot: Int,
    /** 명세 10.4 초과 영역에 새로 들어간 횟수 */
    val overshootCount: Int,
    /** 5단위 값 변경 횟수 (칸 단위) */
    val valueChanges: Int,
) {
    companion object {
        fun fromEvents(start: Int, target: Int, events: List<InputEvent>): TrialStats =
            compute(start, target, events.map { it.current.percent })

        /** values: START 이후 관찰된 값들 (시작값 제외) */
        fun compute(start: Int, target: Int, values: List<Int>): TrialStats {
            val increasing = target > start
            fun over(v: Int) = if (increasing) v - target else target - v

            var maxOvershoot = 0
            var count = 0
            var inside = over(start) > 0
            for (v in values) {
                val o = over(v)
                if (o > 0) {
                    if (!inside) count++
                    inside = true
                    if (o > maxOvershoot) maxOvershoot = o
                } else {
                    inside = false
                }
            }
            val final = values.lastOrNull() ?: start
            return TrialStats(
                finalValue = final,
                absError = abs(final - target),
                reachedTarget = start == target || values.contains(target),
                maxOvershoot = maxOvershoot,
                overshootCount = count,
                valueChanges = values.size,
            )
        }
    }
}

/**
 * 조작 횟수: 한 번 누르거나 잡은 뒤(DOWN) 뗄 때까지(UP) 값이 한 번이라도 바뀌었으면 1회.
 * - C1: 버튼 한 번 누름 (길게 눌러 여러 칸 움직여도 1회)
 * - C2: 손잡이를 잡고 끌었다 놓기 1회 (여러 칸 움직여도 1회)
 * 값이 안 바뀐 누름/잡기(경계값에서 누름, 잡기만 하고 놓음)는 따로 셉니다.
 */
class MoveCounter {
    var moves = 0
        private set
    var touchesWithoutChange = 0
        private set
    private var open = false
    private var changed = false

    fun reset() { moves = 0; touchesWithoutChange = 0; open = false; changed = false }

    fun down() {
        if (open) up() // UP을 못 받은 채 다시 눌린 경우 앞 조작을 닫음
        open = true
        changed = false
    }

    fun valueChanged() { if (open) changed = true }

    fun up() {
        if (!open) return
        if (changed) moves++ else touchesWithoutChange++
        open = false
    }

    /** 시행이 끝날 때 아직 누르고 있던 조작을 마무리 */
    fun close() = up()
}
