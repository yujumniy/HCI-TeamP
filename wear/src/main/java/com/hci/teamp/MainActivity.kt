package com.hci.teamp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/** 갤럭시 워치 화면: 폰에서 온 메시지를 보여주고, 폰으로 메시지를 보냅니다. */
class MainActivity : ComponentActivity(), MessageClient.OnMessageReceivedListener {

    private var lastFromPhone by mutableStateOf("대기 중")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Scaffold(timeText = { TimeText() }) {
                    WatchScreen(lastFromPhone)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Wearable.getMessageClient(this).addListener(this)
    }

    override fun onPause() {
        super.onPause()
        Wearable.getMessageClient(this).removeListener(this)
    }

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path == Paths.TO_WATCH) {
            lastFromPhone = String(event.data)
        }
    }
}

@Composable
private fun WatchScreen(lastFromPhone: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("") }
    var count by remember { mutableIntStateOf(0) }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(lastFromPhone, textAlign = TextAlign.Center)
        Chip(
            onClick = {
                scope.launch {
                    count++
                    status = try {
                        val nodes = Wearable.getNodeClient(context).connectedNodes.await()
                        nodes.forEach {
                            Wearable.getMessageClient(context)
                                .sendMessage(it.id, Paths.TO_PHONE, "워치 탭 #$count".toByteArray())
                                .await()
                        }
                        if (nodes.isEmpty()) "폰 연결 없음" else "전송됨"
                    } catch (e: Exception) {
                        "실패"
                    }
                }
            },
            label = { Text("폰으로 보내기") },
        )
        if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.caption2)
    }
}
