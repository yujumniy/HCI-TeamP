package com.hci.teamp.mobile.debug

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.hci.teamp.mobile.data.ExportManager
import com.hci.teamp.mobile.sync.PhoneSyncClient
import com.hci.teamp.shared.model.SyncState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Phase 1 T3(기기 간 통신) 검증 전용 화면 — 참가자용 화면 아님.
 * 연결 상태 / 송수신 성공·실패 / 왕복 지연 / 데이터 일치 / 워치 대기열을 표시한다.
 * 상세 결과는 Logcat(태그 HCI-PhoneSync) 과 sync_log.csv(내보내기)에 남는다.
 *
 * 재연결 검증(20회): 워치 블루투스 끄기 → 켜기 반복 후 "워치 상태" 로 pending=0 확인,
 *                     sync_log.csv 의 WATCH_DISCONNECTED/CONNECTED 와 RESULT_ACK 기록으로 판정.
 */
class CommTestActivity : Activity() {

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private lateinit var status: TextView
    private lateinit var log: TextView
    private var ok = 0
    private var fail = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val client = PhoneSyncClient.get(this)
        status = TextView(this).apply { textSize = 16f }
        log = TextView(this).apply { textSize = 12f }

        fun button(label: String, action: suspend () -> Unit) = Button(this).apply {
            text = label
            setOnClickListener { scope.launch { action() } }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
            addView(status)
            addView(button("연결 확인") { refresh() })
            addView(button("Ping 20회 (1KB/60KB 교대, 해시 비교)") {
                val report = withContext(Dispatchers.IO) {
                    client.runPingTest(20) { s ->
                        runOnUiThread {
                            if (s.ok) ok++ else fail++
                            append("#${s.id} ${if (s.ok) "OK" else "FAIL"} rtt=${s.rttMs?.let { "%.1f".format(it) }}ms ${s.bytes}B ${s.error ?: ""}")
                            refreshCounts()
                        }
                    }
                }
                append("== 성공 ${report.success}/20, 평균 ${report.meanRttMs?.let { "%.1f".format(it) }}ms, 최대 ${report.maxRttMs?.let { "%.1f".format(it) }}ms")
            })
            addView(button("워치 상태 / 동기화 게이트") {
                val gate = withContext(Dispatchers.IO) { client.checkReadyForNextWatchTrial() }
                append("gate ready=${gate.ready} ${gate.reasons} status=${gate.watchStatus}")
            })
            addView(button("워치 미전송 재전송 요청") {
                append("flush sent=${withContext(Dispatchers.IO) { client.requestFlush() }}")
            })
            addView(button("전체 CSV ZIP 내보내기") {
                val r = withContext(Dispatchers.IO) { ExportManager(this@CommTestActivity).exportAll() }
                append("export success=${r.success} file=${r.fileName} validation=${r.validationPassed} ${r.error ?: ""}")
            })
            addView(log)
        }
        setContentView(ScrollView(this).apply { addView(root) })
        scope.launch { refresh() }
        scope.launch { client.watchResults.collect { append("RESULT ${it.attemptId} ${it.outcome} ${it.endStatus}") } }
    }

    private suspend fun refresh() {
        val client = PhoneSyncClient.get(this)
        withContext(Dispatchers.IO) { client.refreshReachability() }
        refreshCounts()
    }

    private fun refreshCounts() {
        val client = PhoneSyncClient.get(this)
        scope.launch {
            val received = withContext(Dispatchers.IO) { client.repo.dao.countBySyncState(SyncState.RECEIVED.name) }
            val conflicts = withContext(Dispatchers.IO) { client.repo.dao.unresolvedConflicts().size }
            status.text = "워치 연결: ${client.watchReachable.value}\n" +
                "Ping 성공 $ok / 실패 $fail\n" +
                "수신 저장된 워치 시도: $received / 미해결 충돌: $conflicts"
        }
    }

    private fun append(line: String) {
        log.text = "$line\n${log.text}"
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
