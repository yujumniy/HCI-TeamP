package com.hci.teamp.mobile.sync

import android.util.Log
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.hci.teamp.shared.sync.SyncPaths
import kotlinx.coroutines.runBlocking

/**
 * 워치에서 오는 모든 메시지의 단일 수신 지점.
 * onMessageReceived 는 백그라운드 스레드에서 호출되므로 runBlocking 으로 DB 커밋까지 끝낸 뒤 반환한다
 * (반환 후 서비스가 종료돼도 기록이 남도록).
 */
class PhoneSyncService : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        val client = PhoneSyncClient.get(this)
        try {
            runBlocking { client.onMessage(event.path, event.data, event.sourceNodeId) }
        } catch (t: Throwable) {
            Log.e(PhoneSyncClient.TAG, "message handling failed: ${event.path}", t)
            runBlocking { client.repo.log("MESSAGE_HANDLING_FAILED", null, "${event.path}: $t") }
        }
    }

    override fun onCapabilityChanged(info: CapabilityInfo) {
        if (info.name != SyncPaths.CAP_WATCH) return
        val reachable = info.nodes.isNotEmpty()
        runBlocking { PhoneSyncClient.get(this@PhoneSyncService).setReachable(reachable) }
    }
}
