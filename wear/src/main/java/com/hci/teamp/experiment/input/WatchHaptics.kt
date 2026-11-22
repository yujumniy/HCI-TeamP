package com.hci.teamp.experiment.input

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * C3 베젤 한 단계(±5)마다 짧은 진동.
 *
 * - 실제 갤럭시 워치 터치 베젤은 한 칸마다 진동으로 단계를 알려주므로, 그 사용감을 맞추기 위한 기능입니다.
 * - 값이 실제로 바뀔 때만, 매번 같은 진동을 냅니다. 목표 도달 시 특별한 진동은 없습니다 (명세 5.1).
 * - 기기마다 진동 세기가 달라서 TICK(약)·CLICK(강) 중 파일럿에서 고릅니다. 사용 여부는 팀 합의로 확정합니다.
 */
class WatchHaptics(context: Context) {

    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }

    val available: Boolean get() = vibrator?.hasVibrator() == true

    /** @return 진동을 요청했으면 true (기기에 진동 장치가 없거나 OFF면 false) */
    fun play(effect: BezelConfig.Haptic): Boolean {
        val v = vibrator ?: return false
        if (effect == BezelConfig.Haptic.OFF || !v.hasVibrator()) return false
        val id = when (effect) {
            BezelConfig.Haptic.TICK -> VibrationEffect.EFFECT_TICK
            else -> VibrationEffect.EFFECT_CLICK
        }
        v.vibrate(VibrationEffect.createPredefined(id))
        return true
    }
}
