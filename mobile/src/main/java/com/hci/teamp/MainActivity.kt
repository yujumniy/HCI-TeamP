package com.hci.teamp

import android.Manifest
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.hci.teamp.shared.Paths
import com.hci.teamp.shared.VolumeInfo
import com.hci.teamp.shared.VolumeMonitor
import com.hci.teamp.shared.VolumeOverlayService

/** 갤럭시 폰: 스트림별 볼륨(%·단계) 확인, 오버레이 켜기/끄기, 워치 볼륨 확인 */
class MainActivity : ComponentActivity(), MessageClient.OnMessageReceivedListener {

    private val volumes = mutableStateListOf<VolumeInfo>()
    private var watchVolume by mutableStateOf<VolumeInfo?>(null)
    private var canOverlay by mutableStateOf(false)

    private lateinit var monitor: VolumeMonitor

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        monitor = VolumeMonitor(this) { refresh() }
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    val running by VolumeOverlayService.running.collectAsState()
                    PhoneScreen(
                        volumes = volumes,
                        watchVolume = watchVolume,
                        canOverlay = canOverlay,
                        overlayOn = running,
                        onToggleOverlay = ::toggleOverlay,
                        onAdjust = { stream, dir -> monitor.adjust(stream, dir) },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        canOverlay = Settings.canDrawOverlays(this)
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
        if (event.path == Paths.VOLUME) watchVolume = VolumeInfo.decode(event.data)
    }

    private fun refresh() {
        volumes.clear()
        volumes.addAll(monitor.readAll())
    }

    private fun toggleOverlay(on: Boolean) {
        if (!on) {
            VolumeOverlayService.stop(this)
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            // "다른 앱 위에 표시" 권한 화면으로 이동
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
            )
            return
        }
        VolumeOverlayService.start(this)
    }
}

@Composable
private fun PhoneScreen(
    volumes: List<VolumeInfo>,
    watchVolume: VolumeInfo?,
    canOverlay: Boolean,
    overlayOn: Boolean,
    onToggleOverlay: (Boolean) -> Unit,
    onAdjust: (stream: Int, direction: Int) -> Unit,
) {
    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { onToggleOverlay(true) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                "볼륨 표시기",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(top = 32.dp),
            )
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("화면에 볼륨 표시", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (canOverlay) "볼륨을 바꾸면 다른 앱 위에 %와 단계가 뜹니다"
                            else "켜면 '다른 앱 위에 표시' 권한 화면으로 이동합니다",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = overlayOn,
                        onCheckedChange = { on ->
                            if (on && Build.VERSION.SDK_INT >= 33) {
                                notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                onToggleOverlay(on)
                            }
                        },
                    )
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("워치 볼륨", style = MaterialTheme.typography.titleMedium)
                    Text(
                        watchVolume?.let { "${it.label} ${it.summary}" }
                            ?: "워치에서 볼륨을 바꾸면 여기에 표시됩니다",
                    )
                }
            }
        }

        items(volumes, key = { it.stream }) { info ->
            VolumeCard(info, onAdjust)
        }
    }
}

@Composable
private fun VolumeCard(info: VolumeInfo, onAdjust: (Int, Int) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(info.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text("${info.percent}%", style = MaterialTheme.typography.headlineSmall)
            }
            LinearProgressIndicator(
                progress = { info.percent / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${info.step} / ${info.steps}단계", modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { onAdjust(info.stream, AudioManager.ADJUST_LOWER) }) { Text("−") }
                OutlinedButton(
                    onClick = { onAdjust(info.stream, AudioManager.ADJUST_RAISE) },
                    modifier = Modifier.padding(start = 8.dp),
                ) { Text("+") }
            }
        }
    }
}
