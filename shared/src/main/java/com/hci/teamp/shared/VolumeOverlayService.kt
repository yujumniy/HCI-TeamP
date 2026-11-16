package com.hci.teamp.shared

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * 앱을 닫아도 계속 볼륨 변화를 감지해서 오버레이를 띄우는 포그라운드 서비스.
 * - 이 기기의 볼륨이 바뀌면: 오버레이 표시 + 상대 기기로 전송
 * - 상대 기기에서 볼륨 메시지가 오면: "폰"/"워치" 이름을 붙여 오버레이 표시
 */
class VolumeOverlayService : Service(), MessageClient.OnMessageReceivedListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var overlay: VolumeOverlay
    private lateinit var monitor: VolumeMonitor

    override fun onCreate() {
        super.onCreate()
        startInForeground()
        overlay = VolumeOverlay(this)
        monitor = VolumeMonitor(this) { info ->
            overlay.show(localName(this), info)
            sendToPeers(info)
        }
        monitor.start()
        Wearable.getMessageClient(this).addListener(this)
        _running.value = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        Wearable.getMessageClient(this).removeListener(this)
        monitor.stop()
        overlay.hide()
        scope.cancel()
        _running.value = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != Paths.VOLUME) return
        val info = VolumeInfo.decode(event.data) ?: return
        overlay.show(peerName(this), info)
    }

    private fun sendToPeers(info: VolumeInfo) {
        scope.launch {
            runCatching {
                val nodes = Wearable.getNodeClient(this@VolumeOverlayService).connectedNodes.await()
                val client = Wearable.getMessageClient(this@VolumeOverlayService)
                nodes.forEach { client.sendMessage(it.id, Paths.VOLUME, info.encode()).await() }
            }
        }
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "볼륨 표시", NotificationManager.IMPORTANCE_MIN),
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setContentTitle("볼륨 표시 켜짐")
            .setContentText("볼륨을 바꾸면 화면에 %와 단계가 표시됩니다")
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    companion object {
        private const val CHANNEL_ID = "volume_overlay"
        private const val NOTIFICATION_ID = 1

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, VolumeOverlayService::class.java),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, VolumeOverlayService::class.java))
        }
    }
}

fun isWatch(context: Context): Boolean =
    context.packageManager.hasSystemFeature(PackageManager.FEATURE_WATCH)

/** 이 기기 이름 */
fun localName(context: Context): String = if (isWatch(context)) "워치" else "폰"

/** 상대 기기 이름 */
fun peerName(context: Context): String = if (isWatch(context)) "폰" else "워치"
