package com.hci.teamp.shared.util

import android.util.Log

/**
 * Logcat 래퍼. JVM 단위 테스트(android.jar 스텁)에서는 Log 호출이 예외를 던지므로 표준 출력으로 대신 기록한다.
 * 실제 기기에서는 android.util.Log 와 동일하게 동작한다.
 */
object HciLog {
    fun i(tag: String, msg: String) = write("I", tag, msg, null) { Log.i(tag, msg) }
    fun w(tag: String, msg: String, t: Throwable? = null) = write("W", tag, msg, t) { if (t == null) Log.w(tag, msg) else Log.w(tag, msg, t) }
    fun e(tag: String, msg: String, t: Throwable? = null) = write("E", tag, msg, t) { if (t == null) Log.e(tag, msg) else Log.e(tag, msg, t) }

    private inline fun write(level: String, tag: String, msg: String, t: Throwable?, block: () -> Int) {
        try {
            block()
        } catch (_: RuntimeException) {
            println("$level/$tag: $msg" + (t?.let { " | $it" } ?: ""))
        }
    }
}
