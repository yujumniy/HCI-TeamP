package com.hci.teamp.shared.metrics

import com.hci.teamp.shared.model.EventType
import com.hci.teamp.shared.model.MetricsResult
import com.hci.teamp.shared.model.TrialEvent

/**
 * 명세서 10장 측정 지표 공통 계산 함수. 스마트폰·워치·분석 단계 모두 이 함수 하나만 사용한다.
 *
 * 입력은 기록된 이벤트 그대로이며, 관찰되지 않은 중간값을 생성하지 않는다(11.1).
 * 예) 5 → 20 으로 한 번에 바뀐 경우 이동 거리는 15로 계산하지만 10, 15 의 시각은 만들지 않는다.
 */
object TrialMetrics {

    fun compute(startVolume: Int, targetVolume: Int, events: List<TrialEvent>): MetricsResult {
        val ordered = events.sortedBy { it.seq }
        val changes = ordered.filter { it.type == EventType.VALUE_CHANGE && it.value != null }

        // 값 경로: 시작값 + 관찰된 VALUE_CHANGE 값
        val path = ArrayList<Int>(changes.size + 1).apply {
            add(startVolume)
            changes.forEach { add(it.value!!) }
        }
        val direction = Integer.signum(targetVolume - startVolume) // +1 증가 과제, -1 감소 과제, 0 정의 안 됨

        fun overshootOf(v: Int): Int = when (direction) {
            1 -> maxOf(0, v - targetVolume)
            -1 -> maxOf(0, targetVolume - v)
            else -> 0
        }

        // 10.3 Maximum Overshoot
        val maxOvershoot = path.maxOf { overshootOf(it) }

        // 10.4 Overshoot Count: 초과 영역 '새 진입' 횟수
        var overshootCount = 0
        var inRegion = overshootOf(path[0]) > 0
        for (i in 1 until path.size) {
            val now = overshootOf(path[i]) > 0
            if (now && !inRegion) overshootCount++
            inRegion = now
        }

        // 목표 최초 도달: 목표값이 실제로 관찰된 첫 VALUE_CHANGE (시작값이 목표인 경우는 과제상 없음)
        val firstReach = changes.firstOrNull { it.value == targetVolume }
        val reachedTarget = firstReach != null || startVolume == targetVolume

        // 방향 전환 횟수, 누적 이동 거리
        var directionChanges = 0
        var pathDistance = 0
        var lastSign = 0
        for (i in 1 until path.size) {
            val d = path[i] - path[i - 1]
            pathDistance += kotlin.math.abs(d)
            val s = Integer.signum(d)
            if (s != 0) {
                if (lastSign != 0 && s != lastSign) directionChanges++
                lastSign = s
            }
        }

        // 첫 조작 시각: 첫 INPUT_DOWN 또는 VALUE_CHANGE 중 빠른 것
        val firstInput = ordered.firstOrNull {
            it.type == EventType.INPUT_DOWN || it.type == EventType.VALUE_CHANGE
        }

        val finalValue = path.last()

        return MetricsResult(
            maxOvershoot = maxOvershoot,
            overshootCount = overshootCount,
            reachedTarget = reachedTarget,
            firstReachMs = firstReach?.elapsedNs?.let { nsToMs(it) },
            finalAbsError = kotlin.math.abs(finalValue - targetVolume),
            directionChanges = directionChanges,
            pathDistance = pathDistance,
            firstInputMs = firstInput?.elapsedNs?.let { nsToMs(it) },
        )
    }

    fun nsToMs(ns: Long): Double = ns / 1_000_000.0
}
