package com.hci.teamp.shared

/** 폰과 워치가 주고받는 Data Layer 메시지 경로 */
object Paths {
    /** 볼륨이 바뀌었을 때 상대 기기로 보내는 메시지 (내용: VolumeInfo.encode()) */
    const val VOLUME = "/hci/volume"
}
