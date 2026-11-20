package com.hci.teamp.shared.sync

import com.hci.teamp.shared.model.AttemptRecord
import com.hci.teamp.shared.model.SaveOutcome
import com.hci.teamp.shared.model.TrialConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * 스마트폰 ↔ 워치 메시지 규격 v1 (Wearable MessageClient 경로).
 * 규격을 바꿀 때는 VERSION 을 올리고 팀 전체에 공유한다(명세서 18장).
 */
object SyncPaths {
    const val VERSION = 1
    private const val P = "/hci/v1"

    // phone → watch
    const val CONFIG = "$P/config"            // TrialConfig
    const val STATUS_REQ = "$P/status_req"    // StatusRequest
    const val FLUSH_REQ = "$P/flush_req"      // (빈 payload) 미전송 기록 재전송 요청
    const val PING = "$P/ping"                // Ping
    const val RESULT_ACK = "$P/result_ack"    // ResultAck
    const val ABORT = "$P/abort"              // AbortRequest (관리자 긴급 중단)

    // watch → phone
    const val CONFIG_ACK = "$P/config_ack"    // ConfigAck
    const val STATUS = "$P/status"            // WatchStatus
    const val RESULT = "$P/result"            // gzip(AttemptUpload)
    const val PONG = "$P/pong"                // Pong

    // Capability 이름 (각 앱 res/values/wear.xml 에 선언)
    const val CAP_PHONE = "hci_volume_phone"
    const val CAP_WATCH = "hci_volume_watch"

    /** MessageClient 1회 payload 안전 상한(공식 한도 약 100KB 보다 여유를 둠). */
    const val MAX_PAYLOAD_BYTES = 90 * 1024
}

@Serializable
enum class ConfigAckStatus { ACCEPTED, DUPLICATE, REJECTED_PENDING_SYNC, REJECTED_IN_PROGRESS, REJECTED_CONFLICT, REJECTED_INVALID }

@Serializable
data class ConfigAck(val attemptId: String, val status: ConfigAckStatus, val reason: String? = null)

@Serializable
data class StatusRequest(val requestId: String)

@Serializable
data class WatchStatus(
    val requestId: String,
    val pendingCount: Int,
    val conflictCount: Int,
    val tooLargeCount: Int,
    val inProgressAttemptId: String?,
    val lastSyncedAttemptId: String?,
    /** 스마트폰이 거부한(해시 불일치 등) 기록 수. 구버전과 호환되도록 기본값 0 */
    val rejectedCount: Int = 0,
)

/** 워치 → 스마트폰 업로드. contentHash 로 동일 ID·동일 내용 여부를 판정한다. */
@Serializable
data class AttemptUpload(val record: AttemptRecord, val contentHash: String)

@Serializable
data class ResultAck(
    val attemptId: String,
    val outcome: SaveOutcome,
    /** 스마트폰이 실제로 계산한 해시. 워치는 자신의 해시와 일치할 때만 SYNCED 처리. */
    val receivedHash: String,
    /** true 일 때만 스마트폰 DB 트랜잭션 커밋이 끝난 상태 (명세서 13.2 영구 저장 확인) */
    val persisted: Boolean,
)

/** 관리자 긴급 중단(8.3). 워치의 4번 UI 가 수신해 AttemptRecorder.invalidate() 를 호출한다. */
@Serializable
data class AbortRequest(val attemptId: String, val reason: String)

@Serializable
data class Ping(val id: Int, val payload: String)

@Serializable
data class Pong(val id: Int, val payloadSha256: String, val payloadLength: Int)

object SyncCodec {
    val json: Json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        explicitNulls = true
    }

    inline fun <reified T> encode(value: T): ByteArray = json.encodeToString(value).toByteArray(Charsets.UTF_8)
    inline fun <reified T> decode(bytes: ByteArray): T = json.decodeFromString(bytes.toString(Charsets.UTF_8))

    fun encodeUpload(upload: AttemptUpload): ByteArray = gzip(encode(upload))
    fun decodeUpload(bytes: ByteArray): AttemptUpload = decode(gunzip(bytes))

    fun gzip(bytes: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(bytes) }
        return bos.toByteArray()
    }

    fun gunzip(bytes: ByteArray): ByteArray =
        GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

/**
 * 내용 해시. 동일 시도 ID 의 '동일 내용' 판정(명세서 12.3)에 사용.
 * raw 맵은 키 정렬 후 직렬화하여 기기·순서와 무관하게 같은 값이 나오도록 한다.
 */
object ContentHash {
    fun of(record: AttemptRecord): String {
        val canonical = record.copy(
            events = record.events
                .sortedBy { it.seq }
                .map { it.copy(raw = it.raw.toSortedMap()) }
        )
        return SyncCodec.sha256Hex(SyncCodec.encode(canonical))
    }
}
