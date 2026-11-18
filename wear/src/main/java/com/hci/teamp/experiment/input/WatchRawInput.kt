package com.hci.teamp.experiment.input

/**
 * 워치 원시 입력 기록 (검증용).
 * 공통 InputEvent는 값 변경만 담으므로, 누름/뗌·회전 원본값·차단된 탭 등은 여기로 따로 보냅니다.
 * 형식은 팀원 1의 폰 RawInput과 맞췄습니다.
 */
data class WatchRawInput(
    val attemptId: String,
    val elapsedRealtimeMs: Long,
    val kind: Kind,
    val source: String,
    val value: Int?,
    val detail: String,
) {
    enum class Kind {
        /** 슬라이더 손잡이를 잡음 */
        DOWN,

        /** 슬라이더 손잡이에서 손을 뗌 (제스처 취소 포함) */
        UP,

        /** 베젤 회전 이벤트 원본 (값이 안 바뀐 것 포함 전부) */
        ROTARY,

        /** 회전 이벤트 간격으로 추정한 새 베젤 동작 시작 (실제 손가락 접촉 신호가 아님) */
        GESTURE_START,

        /** 0/100 경계에서 더 돌림 */
        BOUNDARY,

        /** 시행 중이 아닐 때 들어온 입력 */
        IGNORED,

        /** C4: 손잡이 밖 트랙을 눌러 차단됨 */
        TRACK_TAP_BLOCKED,
    }
}
