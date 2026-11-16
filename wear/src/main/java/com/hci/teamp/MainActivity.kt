package com.hci.teamp

import android.content.ActivityNotFoundException
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.hci.teamp.shared.Paths
import com.hci.teamp.shared.VolumeInfo
import com.hci.teamp.shared.VolumeMonitor
import com.hci.teamp.shared.VolumeOverlayService

/** 갤럭시 워치: 볼륨(%·단계) 확인, 오버레이 켜기/끄기, 폰 볼륨 확인 */
class MainActivity : ComponentActivity(), MessageClient.OnMessageReceivedListener {

    private val volumes = mutableStateListOf<VolumeInfo>()
    private var phoneVolume by mutableStateOf<VolumeInfo?>(null)
    private var notice by mutableStateOf<String?>(null)

    private lateinit var monitor: VolumeMonitor

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        monitor = VolumeMonitor(this) { refresh() }
        setContent {
            MaterialTheme {
                val running by VolumeOverlayService.running.collectAsState()
                WatchScreen(
                    volumes = volumes,
                    phoneVolume = phoneVolume,
                    overlayOn = running,
                    notice = notice,
                    onToggleOverlay = ::toggleOverlay,
                    onAdjust = { stream, dir -> monitor.adjust(stream, dir) },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (Settings.canDrawOverlays(this)) notice = null
        monitor.start()
        refresh()
        Wearable.getMessageClient(this).addListener(this)
    }

    override fun onPause() {
        super.onPause()
        monitor.stop()
        Wearable.getMessageClient(this).removeListener(this)
    }

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path == Paths.VOLUME) phoneVolume = VolumeInfo.decode(event.data)
    }

    private fun refresh() {
        volumes.clear()
        // 워치는 화면이 작아서 미디어·벨소리·알람만 보여줍니다.
        volumes.addAll(
            monitor.readAll().filter {
                it.stream in listOf(AudioManager.STREAM_MUSIC, AudioManager.STREAM_RING, AudioManager.STREAM_ALARM)
            },
        )
    }

    private fun toggleOverlay() {
        if (VolumeOverlayService.running.value) {
            VolumeOverlayService.stop(this)
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            try {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                )
            } catch (_: ActivityNotFoundException) {
                // 일부 Wear OS에는 이 권한 화면이 없습니다 → adb로 허용 (README 참고)
                notice = "권한 필요:\nadb shell appops set $packageName SYSTEM_ALERT_WINDOW allow"
            }
            return
        }
        VolumeOverlayService.start(this)
    }
}

@Composable
private fun WatchScreen(
    volumes: List<VolumeInfo>,
    phoneVolume: VolumeInfo?,
    overlayOn: Boolean,
    notice: String?,
    onToggleOverlay: () -> Unit,
    onAdjust: (stream: Int, direction: Int) -> Unit,
) {
    val listState = rememberScalingLazyListState()
    Scaffold(
        timeText = { TimeText() },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) },
    ) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item {
                Chip(
                    onClick = onToggleOverlay,
                    label = { Text(if (overlayOn) "볼륨 표시 끄기" else "볼륨 표시 켜기") },
                    colors = if (overlayOn) ChipDefaults.secondaryChipColors() else ChipDefaults.primaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            notice?.let {
                item { Text(it, style = MaterialTheme.typography.caption3, textAlign = TextAlign.Center) }
            }
            item {
                Text(
                    phoneVolume?.let { "폰 ${it.label} ${it.summary}" } ?: "폰 볼륨 대기 중",
                    style = MaterialTheme.typography.caption2,
                    textAlign = TextAlign.Center,
                )
            }
            items(volumes, key = { it.stream }) { info ->
                WatchVolumeItem(info, onAdjust)
            }
        }
    }
}

@Composable
private fun WatchVolumeItem(info: VolumeInfo, onAdjust: (Int, Int) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("${info.label} ${info.percent}%", style = MaterialTheme.typography.title3)
        VolumeBar(info.percent / 100f)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = { onAdjust(info.stream, AudioManager.ADJUST_LOWER) },
                modifier = Modifier.size(ButtonDefaults.ExtraSmallButtonSize),
                colors = ButtonDefaults.secondaryButtonColors(),
            ) { Text("−") }
            Text(
                "${info.step}/${info.steps}단계",
                style = MaterialTheme.typography.caption1,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            Button(
                onClick = { onAdjust(info.stream, AudioManager.ADJUST_RAISE) },
                modifier = Modifier.size(ButtonDefaults.ExtraSmallButtonSize),
                colors = ButtonDefaults.secondaryButtonColors(),
            ) { Text("+") }
        }
    }
}

@Composable
private fun VolumeBar(fraction: Float) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colors.onSurface.copy(alpha = 0.2f)),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .background(MaterialTheme.colors.primary),
        )
    }
}
