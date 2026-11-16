package com.hci.teamp.shared.log

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CsvExport {
    fun stamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    /**
     * Download/HCI_Experiment/ 에 저장 (Android 10+ MediaStore, 권한 불필요).
     * 그보다 낮은 버전은 앱 전용 외부 폴더에 저장. 기존 파일을 덮어쓰지 않습니다.
     * 반환: 사람이 읽을 수 있는 저장 위치
     */
    fun saveToDownloads(context: Context, fileName: String, text: String): String {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/HCI_Experiment")
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("저장 위치를 만들 수 없습니다")
            context.contentResolver.openOutputStream(uri)!!.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            return "Download/HCI_Experiment/$fileName"
        }
        val dir = File(context.getExternalFilesDir(null), "HCI_Experiment").apply { mkdirs() }
        val f = File(dir, fileName)
        f.writeText(text)
        return f.absolutePath
    }

    /** 앱 내부 저장소(files/)에 저장. 워치용. adb로 꺼낼 수 있습니다. */
    fun saveToAppFiles(context: Context, fileName: String, text: String): File {
        val f = File(context.filesDir, fileName)
        f.writeText(text)
        return f
    }
}
