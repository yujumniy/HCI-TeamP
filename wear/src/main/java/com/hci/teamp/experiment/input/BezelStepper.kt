package com.hci.teamp.experiment.input

import kotlin.math.sign

/**
 * C3 터치 베젤 회전 입력 → 음량 단계(±1 = ±5) 변환 규칙 (명세 4.4).
 *
 * Android 의존성이 없는 순수 로직이라 단위 테스트로 검증합니다.
 * 감도·방향·재접촉 기준은 실제 Watch4 검증 후 확정하므로(명세 19장) 모두 설정값으로 둡니다.
 */
data class BezelConfig(
    /** PER_EVENT: 회전 이벤트 1개 = 1단계. ACCUMULATE: 회전량(px)을 모아 pixelsPerStep마다 1단계 */
    val mode: Mode = Mode.PER_EVENT,
    /** ACCUMULATE 모드에서 1단계에 필요한 회전량 */
    val pixelsPerStep: Float = 48f,
    /** 원시 회전값이 양수(+)일 때 시계 방향이면 true. 실기기에서 확인 후 확정 */
    val clockwiseIsPositive: Boolean = true,
    /** 이전 회전 이벤트와의 간격이 이보다 길면 손가락을 떼었다가 다시 댄 것(새 동작)으로 봅니다 */
    val gestureGapMs: Long = 300L,
    /** 값이 한 단계 바뀔 때마다 진동 (OFF / TICK 약 / CLICK 강). 사용 여부는 팀 합의 후 확정 */
    val haptic: Haptic = Haptic.CLICK,
) {
    enum class Mode { PER_EVENT, ACCUMULATE }

    enum class Haptic { OFF, TICK, CLICK }

    init {
        require(pixelsPerStep > 0f && gestureGapMs > 0L)
    }

    fun describe(): String =
        "mode=$mode;px_per_step=$pixelsPerStep;cw_positive=$clockwiseIsPositive;gap_ms=$gestureGapMs;haptic=$haptic"
}

class BezelStepper(config: BezelConfig = BezelConfig()) {

    /**
     * @param steps 이번 이벤트로 바뀌는 단계 수 (+ 증가 = 시계 방향, − 감소)
     * @param newGesture 이전 이벤트와 간격이 gestureGapMs를 넘어 새 동작으로 판단됨 (재접촉 추정)
     * @param direction 이번 이벤트의 방향 (+1 시계, −1 반시계, 0 회전 없음)
     * @param accumulated 단계로 바뀌지 않고 남은 회전량 (ACCUMULATE 모드)
     * @param gapMs 이전 회전 이벤트와의 간격 (첫 이벤트면 null)
     */
    data class Result(
        val steps: Int,
        val newGesture: Boolean,
        val direction: Int,
        val accumulated: Float,
        val gapMs: Long?,
    )

    var config: BezelConfig = config
        set(value) {
            field = value
            reset()
        }

    private var accumulated = 0f
    private var lastAt: Long? = null
    private var lastDirection = 0

    /** 시행 시작·종료 시 호출: 이전 시행의 남은 회전량이 다음 시행으로 넘어가지 않게 합니다. */
    fun reset() {
        accumulated = 0f
        lastAt = null
        lastDirection = 0
    }

    fun feed(rawPixels: Float, nowMs: Long): Result {
        val gap = lastAt?.let { nowMs - it }
        val newGesture = gap == null || gap > config.gestureGapMs
        lastAt = nowMs

        val toward = if (config.clockwiseIsPositive) rawPixels else -rawPixels
        val direction = toward.sign.toInt()
        if (direction == 0) return Result(0, newGesture, 0, accumulated, gap)

        // 재접촉 또는 방향 전환: 이전에 남은 회전량을 버려서 값이 갑자기 튀지 않게 합니다.
        if (newGesture || (lastDirection != 0 && direction != lastDirection)) accumulated = 0f
        lastDirection = direction

        val steps = when (config.mode) {
            BezelConfig.Mode.PER_EVENT -> direction
            BezelConfig.Mode.ACCUMULATE -> {
                accumulated += toward
                // 0 쪽으로 버림: 모자란 회전량은 다음 이벤트로 넘깁니다.
                val n = (accumulated / config.pixelsPerStep).toInt()
                accumulated -= n * config.pixelsPerStep
                n
            }
        }
        return Result(steps, newGesture, direction, accumulated, gap)
    }

    companion object {
        /** 단계 변화를 0..100, 5단위 값으로 적용 */
        fun apply(percent: Int, steps: Int): Int = (percent + steps * 5).coerceIn(0, 100)
    }
}
