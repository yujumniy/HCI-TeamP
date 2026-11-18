package com.hci.teamp.shared.experiment

import kotlin.math.abs

data class TrialMetrics(
    val maximumOvershoot: Int,
    val overshootCount: Int,
    val finalAbsoluteError: Int,
    val firstTargetElapsedMs: Long?,
    val directionChanges: Int,
    val cumulativeDistance: Int,
    val firstInputElapsedMs: Long?,
) {
    val reachedTarget: Boolean get() = firstTargetElapsedMs != null
}

// Uses only observed VALUE_CHANGE events. A jump across target is not an observed hit.
fun TrialRecord.calculateMetrics(): TrialMetrics {
    val task = result.attempt.task
    val target = task.target.percent
    val taskDirection = target.compareTo(task.start.percent)
    var maximumOvershoot = 0
    var overshootCount = 0
    var wasOver = false
    var firstTarget: Long? = 0L.takeIf { task.start == task.target }
    var previousDirection = 0
    var directionChanges = 0
    var distance = 0
    for (event in events) {
        val difference = event.current.percent - event.previous.percent
        val direction = difference.compareTo(0)
        if (previousDirection != 0 && direction != previousDirection) directionChanges++
        previousDirection = direction
        distance += abs(difference)

        val overshoot = (event.current.percent - target) * taskDirection
        if (overshoot > 0 && !wasOver) overshootCount++
        wasOver = overshoot > 0
        maximumOvershoot = maxOf(maximumOvershoot, overshoot)
        if (firstTarget == null && event.current == task.target) {
            firstTarget = event.elapsedRealtimeMs - startedElapsedRealtimeMs
        }
    }
    return TrialMetrics(
        maximumOvershoot, overshootCount, abs(result.finalValue.percent - target),
        firstTarget, directionChanges, distance,
        events.firstOrNull()?.let { it.elapsedRealtimeMs - startedElapsedRealtimeMs },
    )
}
