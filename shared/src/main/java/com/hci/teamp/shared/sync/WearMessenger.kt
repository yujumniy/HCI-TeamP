package com.hci.teamp.shared.sync

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await

/**
 * Wearable Data Layer MessageClient 래퍼 (양쪽 기기 공용).
 * 상대 기기는 Capability 로 찾는다 → 같은 applicationId·같은 서명 키로 빌드되어야 통신된다.
 */
class WearMessenger(context: Context, private val peerCapability: String) {

    private val app = context.applicationContext
    private val messageClient = Wearable.getMessageClient(app)
    private val capabilityClient = Wearable.getCapabilityClient(app)

    sealed class SendResult {
        data class Sent(val nodeId: String, val bytes: Int) : SendResult()
        data object NoPeer : SendResult()
        data class TooLarge(val bytes: Int) : SendResult()
        data class Failed(val error: Throwable) : SendResult()
    }

    suspend fun reachablePeer(): Node? = try {
        val info = capabilityClient.getCapability(peerCapability, CapabilityClient.FILTER_REACHABLE).await()
        info.nodes.firstOrNull { it.isNearby } ?: info.nodes.firstOrNull()
    } catch (t: Throwable) {
        Log.w(TAG, "capability lookup failed", t)
        null
    }

    suspend fun send(path: String, payload: ByteArray, nodeId: String? = null): SendResult {
        if (payload.size > SyncPaths.MAX_PAYLOAD_BYTES) return SendResult.TooLarge(payload.size)
        val target = nodeId ?: reachablePeer()?.id ?: return SendResult.NoPeer
        return try {
            messageClient.sendMessage(target, path, payload).await()
            SendResult.Sent(target, payload.size)
        } catch (t: Throwable) {
            Log.w(TAG, "send $path failed", t)
            SendResult.Failed(t)
        }
    }

    companion object { const val TAG = "HCI-Sync" }
}
