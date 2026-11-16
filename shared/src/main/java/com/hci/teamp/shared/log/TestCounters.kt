package com.hci.teamp.shared.log

import androidx.compose.runtime.mutableStateMapOf

/** Phase 1 테스트별 성공·실패 횟수 (화면 표시용). 결과는 EventLog에도 TEST_RESULT로 남깁니다. */
class TestCounters(private val log: EventLog) {
    private val success = mutableStateMapOf<String, Int>()
    private val fail = mutableStateMapOf<String, Int>()

    fun success(test: String): Int = success[test] ?: 0
    fun fail(test: String): Int = fail[test] ?: 0

    fun record(test: String, ok: Boolean, note: String = "") {
        if (ok) success[test] = success(test) + 1 else fail[test] = fail(test) + 1
        log.log(
            EventType.TEST_RESULT,
            source = test,
            raw = "result=${if (ok) "PASS" else "FAIL"};pass=${success(test)};fail=${fail(test)};$note",
        )
    }

    /** "성공 12 / 실패 1 (13/20)" */
    fun summary(test: String, goal: Int = 20): String {
        val s = success(test)
        val f = fail(test)
        return "성공 $s · 실패 $f (${s + f}/$goal)"
    }
}
