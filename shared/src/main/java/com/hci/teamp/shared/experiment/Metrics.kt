package com.hci.teamp.shared.experiment

import kotlin.math.abs

/** 시행 중 관찰된 가상 음량 값 (START 이후 경과 ms) */
data class ValueSample(val elapsedMs: Long, val value: Int)

enum class TrialStatus { COMPLETED, TIMEOUT, INVALID, IN_PROGRESS }

/** 명세 10장 측정 지표 */
data class TrialMetrics(
    val finalValue: Int,
    val finalAbsError: Int,
    val reachedTarget: Boolean,
    val firstReachMs: Long?,
    val maxOvershoot: Int,
    val overshootCount: Int,
    val directionChanges: Int,
    val totalDistance: Int,
    val firstInputMs: Long?,
)

/**
 * 측정 계산 공통 함수. 폰·워치 모두 이 함수를 사용합니다.
 *
 * @param start 시작 음량
 * @param target 목표 음량
 * @param changes START 이후 값이 바뀐 순서대로의 기록 (시작값 자체는 포함하지 않음)
 * @param firstInputMs 첫 조작(INPUT_DOWN)까지 걸린 시간. 조작이 없으면 null
 */
object MetricsCalculator {
    fun compute(
        start: Int,
        target: Int,
        changes: List<ValueSample>,
        firstInputMs: Long?,
    ): TrialMetrics {
        val increasing = target > start
        val values = listOf(start) + changes.map { it.value }
        val finalValue = values.last()

        // 목표 초과 거리 (과제 방향 기준)
        fun overshootOf(v: Int): Int = if (increasing) v - target else target - v

        var maxOvershoot = 0
        var overshootCount = 0
        var inOvershoot = overshootOf(start) > 0
        for (v in values.drop(1)) {
            val o = overshootOf(v)
            if (o > 0) {
                if (!inOvershoot) overshootCount++
                inOvershoot = true
                if (o > maxOvershoot) maxOvershoot = o
            } else {
                inOvershoot = false
            }
        }

        val firstReach = if (start == target) 0L else changes.firstOrNull { it.value == target }?.elapsedMs
        val reached = start == target || values.any { it == target }

        var directionChanges = 0
        var lastDir = 0
        var distance = 0
        for (i in 1 until values.size) {
            val d = values[i] - values[i - 1]
            if (d == 0) continue
            distance += abs(d)
            val dir = if (d > 0) 1 else -1
            if (lastDir != 0 && dir != lastDir) directionChanges++
            lastDir = dir
        }

        return TrialMetrics(
            finalValue = finalValue,
            finalAbsError = abs(finalValue - target),
            reachedTarget = reached,
            firstReachMs = firstReach,
            maxOvershoot = maxOvershoot,
            overshootCount = overshootCount,
            directionChanges = directionChanges,
            totalDistance = distance,
            firstInputMs = firstInputMs,
        )
    }
}
