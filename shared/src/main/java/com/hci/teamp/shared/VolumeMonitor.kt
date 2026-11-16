package com.hci.teamp.shared

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * 볼륨 변화를 감지합니다.
 * - 볼륨 키, 갤럭시 볼륨 패널, 워치 베젤/퀵패널 등 어떤 방법으로 바꿔도
 *   시스템 설정이 바뀌므로 ContentObserver + VOLUME_CHANGED 브로드캐스트로 잡아냅니다.
 */
class VolumeMonitor(
    context: Context,
    private val onChange: (VolumeInfo) -> Unit,
) {
    private val app = context.applicationContext
    private val audio = app.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val lastLevels = mutableMapOf<Int, Int>()
    private var started = false

    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) = checkAll()
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = checkAll()
    }

    fun read(stream: Int): VolumeInfo = VolumeInfo(
        stream = stream,
        level = audio.getStreamVolume(stream),
        min = if (Build.VERSION.SDK_INT >= 28) audio.getStreamMinVolume(stream) else 0,
        max = audio.getStreamMaxVolume(stream),
    )

    fun readAll(): List<VolumeInfo> = WATCHED_STREAMS.map(::read)

    fun start() {
        if (started) return
        started = true
        readAll().forEach { lastLevels[it.stream] = it.level }
        app.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, observer)
        ContextCompat.registerReceiver(
            app, receiver, IntentFilter(VOLUME_CHANGED_ACTION), ContextCompat.RECEIVER_EXPORTED,
        )
    }

    fun stop() {
        if (!started) return
        started = false
        app.contentResolver.unregisterContentObserver(observer)
        app.unregisterReceiver(receiver)
    }

    /** direction: AudioManager.ADJUST_RAISE / ADJUST_LOWER */
    fun adjust(stream: Int, direction: Int) {
        try {
            audio.adjustStreamVolume(stream, direction, 0)
        } catch (_: SecurityException) {
            // 방해 금지 모드에서는 벨소리/알림 볼륨을 앱이 바꿀 수 없습니다.
        }
        checkAll()
    }

    private fun checkAll() {
        for (info in readAll()) {
            if (lastLevels[info.stream] != info.level) {
                lastLevels[info.stream] = info.level
                onChange(info)
            }
        }
    }

    private companion object {
        const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
    }
}
