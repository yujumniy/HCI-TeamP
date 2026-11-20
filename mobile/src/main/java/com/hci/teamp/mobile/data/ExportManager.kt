package com.hci.teamp.mobile.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.hci.teamp.mobile.sync.PhoneSyncClient
import com.hci.teamp.shared.db.BackupLogEntity
import com.hci.teamp.shared.db.ExperimentRepository
import com.hci.teamp.shared.export.CsvExport
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 명세서 15장 자동 백업 / 수동 내보내기.
 *  - MediaStore(Android 10+ 공식 API)로 Documents/HCI_Experiment/... 에 저장 → 저장소 권한 불필요
 *  - 기존 파일 덮어쓰기 금지: 같은 이름이 있으면 _2, _3 … 을 붙인다
 *  - 실패 시 원본 DB 는 그대로, 실패 기록을 backup_log 에 남기고 재시도 가능
 *  - 백업 성공과 검증 성공은 별도 열로 기록
 */
class ExportManager(context: Context) {

    private val app = context.applicationContext
    private val repo = ExperimentRepository.get(app)
    private val checker = IntegrityChecker(repo)

    data class ExportResult(
        val success: Boolean,
        val fileName: String?,
        val uri: Uri?,
        val validationPassed: Boolean?,
        val error: String?,
    )

    /**
     * 참가자 종료 시 4번이 호출 (15.2). 검증 → 검증 결과와 무관하게 백업 진행.
     * watchPendingCount 를 넘기지 않으면 워치에 직접 질의한다.
     */
    suspend fun finishParticipantAndBackup(participantId: String): ExportResult {
        repo.dao.markParticipantFinished(participantId, System.currentTimeMillis())
        return exportParticipant(participantId, kind = "AUTO_BACKUP")
    }

    suspend fun exportParticipant(participantId: String, kind: String = "MANUAL_PARTICIPANT"): ExportResult {
        val pending = PhoneSyncClient.get(app).requestWatchStatus()?.pendingCount
        val report = checker.check(participantId, pending)
        val dao = repo.dao
        val ps = listOfNotNull(dao.participant(participantId))
        val files = listOf(
            "trials.csv" to CsvExport.trialsCsv(dao.attemptsOf(participantId)),
            "events.csv" to CsvExport.eventsCsv(dao.eventsOfParticipant(participantId)),
            "participants.csv" to CsvExport.participantsCsv(ps),
            "conflicts.csv" to CsvExport.conflictsCsv(dao.allConflicts().filter { it.attemptId.startsWith("$participantId-") }),
            "receive_errors.csv" to CsvExport.receiveErrorsCsv(dao.allReceiveErrors().filter { it.attemptId.startsWith("$participantId-") }),
            "integrity_report.csv" to IntegrityChecker.toCsv(listOf(report)),
            "sync_log.csv" to CsvExport.syncLogCsv(dao.allSyncLog()),
        )
        val stamp = stamp()
        val (folder, name) = if (kind == "AUTO_BACKUP")
            BACKUP_DIR to "HCI_${participantId}_backup_$stamp.zip"
        else
            EXPORT_DIR to "HCI_${participantId}_$stamp.zip"
        return write(folder, name, files, participantId, kind, report.passed)
    }

    suspend fun exportAll(): ExportResult {
        val dao = repo.dao
        val pending = PhoneSyncClient.get(app).requestWatchStatus()?.pendingCount
        val participants = dao.allParticipants()
        val reports = participants.map { checker.check(it.participantId, pending) }
        val files = listOf(
            "trials.csv" to CsvExport.trialsCsv(dao.allAttempts()),
            "events.csv" to CsvExport.eventsCsv(dao.allEvents()),
            "participants.csv" to CsvExport.participantsCsv(participants),
            "conflicts.csv" to CsvExport.conflictsCsv(dao.allConflicts()),
            "receive_errors.csv" to CsvExport.receiveErrorsCsv(dao.allReceiveErrors()),
            "integrity_report.csv" to IntegrityChecker.toCsv(reports),
            "sync_log.csv" to CsvExport.syncLogCsv(dao.allSyncLog()),
        )
        return write(EXPORT_DIR, "HCI_ALL_${stamp()}.zip", files, null, "MANUAL_ALL", reports.all { it.passed })
    }

    private suspend fun write(
        relativeDir: String,
        baseName: String,
        files: List<Pair<String, String>>,
        participantId: String?,
        kind: String,
        validationPassed: Boolean,
    ): ExportResult {
        val resolver = app.contentResolver
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val name = uniqueName(relativeDir, baseName)
        var uri: Uri? = null
        val result = try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDir)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            uri = resolver.insert(collection, values) ?: error("MediaStore insert returned null")
            // "w" 모드: 방금 만든 새 항목에만 쓴다(기존 파일 아님)
            resolver.openOutputStream(uri, "w")!!.use { CsvExport.writeZip(it, files) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            ExportResult(true, name, uri, validationPassed, null)
        } catch (t: Throwable) {
            // 실패한 '미완성 ZIP 항목'만 정리한다. DB 원본은 건드리지 않는다.
            uri?.let { runCatching { resolver.delete(it, null, null) } }
            ExportResult(false, name, null, validationPassed, t.toString())
        }
        repo.dao.insertBackupLog(
            BackupLogEntity(
                participantId = participantId,
                kind = kind,
                fileName = result.fileName,
                uri = result.uri?.toString(),
                backupSuccess = result.success,
                validationPassed = validationPassed,
                error = result.error,
                wallMs = System.currentTimeMillis(),
            )
        )
        repo.log("EXPORT_$kind", null, "$name success=${result.success} validation=$validationPassed ${result.error ?: ""}")
        return result
    }

    /** 앱이 만든 파일 중 같은 이름이 있으면 접미사를 붙인다. (MediaStore 도 충돌 시 자동 개명하지만 이름을 예측 가능하게 유지) */
    private fun uniqueName(relativeDir: String, baseName: String): String {
        val stem = baseName.removeSuffix(".zip")
        var candidate = baseName
        var n = 2
        while (exists(relativeDir, candidate)) {
            candidate = "${stem}_$n.zip"; n++
        }
        return candidate
    }

    private fun exists(relativeDir: String, name: String): Boolean {
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        app.contentResolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
            arrayOf("$relativeDir/", name),
            null,
        )?.use { return it.count > 0 }
        return false
    }

    private fun stamp(): String = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())

    companion object {
        val BACKUP_DIR = "${Environment.DIRECTORY_DOCUMENTS}/HCI_Experiment/Backups"
        val EXPORT_DIR = "${Environment.DIRECTORY_DOCUMENTS}/HCI_Experiment/Exports"
    }
}
