package com.hci.teamp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/** 갤럭시 폰 화면: 워치로 메시지를 보내고, 워치에서 온 메시지를 보여줍니다. */
class MainActivity : ComponentActivity(), MessageClient.OnMessageReceivedListener {

    private var lastFromWatch by mutableStateOf("아직 받은 메시지 없음")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    PhoneScreen(lastFromWatch)
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
        if (event.path == Paths.TO_PHONE) {
            lastFromWatch = String(event.data)
        }
    }
}

@androidx.compose.runtime.Composable
private fun PhoneScreen(lastFromWatch: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var status by androidx.compose.runtime.remember { mutableStateOf("") }
    var count by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("HCI TeamP · 폰", style = MaterialTheme.typography.headlineSmall)
        Text("워치에서 온 메시지: $lastFromWatch")
        Button(onClick = {
            scope.launch {
                count++
                status = sendToAllNodes(context, Paths.TO_WATCH, "폰에서 보낸 메시지 #$count")
            }
        }) { Text("워치로 보내기") }
        if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)
    }
}

/** 연결된 모든 기기(워치)에 메시지를 보내고 결과를 문자열로 돌려줍니다. */
suspend fun sendToAllNodes(context: android.content.Context, path: String, text: String): String =
    try {
        val nodes = Wearable.getNodeClient(context).connectedNodes.await()
        if (nodes.isEmpty()) {
            "연결된 워치가 없습니다"
        } else {
            nodes.forEach { node ->
                Wearable.getMessageClient(context)
                    .sendMessage(node.id, path, text.toByteArray())
                    .await()
            }
            "${nodes.size}대에 전송 완료"
        }
    } catch (e: Exception) {
        "전송 실패: ${e.message}"
    }
