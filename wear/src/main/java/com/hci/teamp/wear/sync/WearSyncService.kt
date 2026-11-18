package com.hci.teamp.wear.sync

import android.util.Log
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.hci.teamp.shared.sync.SyncPaths
import kotlinx.coroutines.runBlocking

/** 스마트폰에서 오는 메시지의 단일 수신 지점. */
class WearSyncService : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        val mgr = WearSyncManager.get(this)
        try {
            runBlocking { mgr.onMessage(event.path, event.data, event.sourceNodeId) }
        } catch (t: Throwable) {
            Log.e(WearSyncManager.TAG, "message handling failed: ${event.path}", t)
            runBlocking { mgr.repo.log("MESSAGE_HANDLING_FAILED", null, "${event.path}: $t") }
        }
    }

    override fun onCapabilityChanged(info: CapabilityInfo) {
        if (info.name != SyncPaths.CAP_PHONE) return
        WearSyncManager.get(this).onPhoneReachabilityChanged(info.nodes.isNotEmpty())
    }
}
