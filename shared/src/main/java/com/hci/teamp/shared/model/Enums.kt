package com.hci.teamp.shared.model

import kotlinx.serialization.Serializable

/** 명세서 1.2 조건. 번호(c)는 과제 순서 공식(7.2)에서 사용. */
@Serializable
enum class ConditionId(val number: Int, val device: DeviceType) {
    C1(1, DeviceType.PHONE),   // 스마트폰 물리 음량 버튼
    C2(2, DeviceType.PHONE),   // 스마트폰 수평 슬라이더
    C3(3, DeviceType.WATCH),   // 워치 터치 베젤
    C4(4, DeviceType.WATCH);   // 워치 수평 슬라이더

    companion object {
        fun fromNumber(n: Int): ConditionId = entries.first { it.number == n }
    }
}

@Serializable
enum class DeviceType { PHONE, WATCH }

@Serializable
enum class TrialPhase { PRACTICE, MAIN }

/** 명세서 8.2 시행 종료 상태 */
@Serializable
enum class EndStatus { IN_PROGRESS, COMPLETED, TIMEOUT, INVALID }

/** 명세서 11.1 이벤트 유형 (연결·동기화 상태 이벤트는 sync_log 테이블에 별도 기록) */
@Serializable
enum class EventType { START, INPUT_DOWN, INPUT_UP, VALUE_CHANGE, COMPLETE, TIMEOUT, INVALID }

/**
 * 시도 레코드의 동기화 상태.
 * - LOCAL_PHONE : 스마트폰에서 측정되어 스마트폰에 저장된 기록 (전송 불필요)
 * - PENDING     : 워치에 저장되었고 아직 스마트폰 영구 저장 확인(ACK) 전
 * - SYNCED      : 스마트폰이 영구 저장을 확인함 (워치 원본은 그래도 삭제하지 않음)
 * - CONFLICT    : 스마트폰에 동일 ID·다른 내용이 이미 있음 → 관리자 검토
 * - RECEIVED    : 스마트폰이 워치로부터 받아 저장한 기록
 * - TOO_LARGE   : 메시지 크기 제한 초과로 전송 불가 → 관리자 확인 필요 (자동 삭제·축소 금지)
 */
@Serializable
enum class SyncState { LOCAL_PHONE, PENDING, SYNCED, CONFLICT, RECEIVED, TOO_LARGE }

/** 명세서 12.3 저장 결과 */
@Serializable
enum class SaveOutcome { SAVED, DUPLICATE, DATA_CONFLICT }
