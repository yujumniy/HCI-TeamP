package com.hci.teamp.mobile.sync

import android.content.Context
import com.hci.teamp.shared.model.DeviceType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 스마트폰 앱 시작 시 1회 호출 (4번의 Application.onCreate 또는 첫 Activity 에서).
 *  1) 비정상 종료로 남은 스마트폰 시도(C1·C2)를 INVALID 로 확정 (13.3)
 *  2) 워치 연결 확인 + 미전송 기록 재전송 요청 (13.2)
 */
object PhoneDataBootstrap {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun onAppStart(context: Context, onRecovered: (invalidatedAttemptIds: List<String>) -> Unit = {}) {
        val client = PhoneSyncClient.get(context)
        scope.launch {
            val invalidated = client.repo.invalidateInterruptedAttempts(DeviceType.PHONE)
            onRecovered(invalidated)
            if (client.refreshReachability()) client.requestFlush()
        }
    }
}
