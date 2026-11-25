package com.hci.teamp.shared.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 기기별 로컬 영구 저장소(명세서 15.1). 스마트폰·워치가 같은 스키마를 쓴다.
 * fallbackToDestructiveMigration 은 사용하지 않는다 — 스키마 변경 시 반드시 Migration 을 작성할 것.
 */
@Database(
    entities = [
        ParticipantEntity::class,
        AttemptEntity::class,
        EventEntity::class,
        ConflictEntity::class,
        ReceivedConfigEntity::class,
        SyncLogEntity::class,
        BackupLogEntity::class,
        ReceiveErrorEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class ExperimentDatabase : RoomDatabase() {
    abstract fun dao(): ExperimentDao

    companion object {
        // Old hci_experiment.db is kept untouched as a legacy archive.
        private const val NAME = "hci_fixed18_20261125.db"

        /**
         * v1 → v2
         *  - receive_errors 테이블 추가 (해시 불일치·진행 중 기록 보존)
         *  - v1 에서 PENDING 으로 저장된 워치의 진행 중 시도를 NOT_READY 로 변경 (전송 대상에서 제외)
         * 기존 기록은 삭제·수정하지 않는다.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `receive_errors` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`attemptId` TEXT NOT NULL, `errorType` TEXT NOT NULL, " +
                        "`sentHash` TEXT NOT NULL, `computedHash` TEXT NOT NULL, " +
                        "`endStatus` TEXT NOT NULL, `payloadJson` TEXT NOT NULL, " +
                        "`source` TEXT NOT NULL, `detectedAtWallMs` INTEGER NOT NULL, " +
                        "`resolved` INTEGER NOT NULL, `resolutionNote` TEXT)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_receive_errors_attemptId` ON `receive_errors` (`attemptId`)")
                db.execSQL("UPDATE attempts SET syncState = 'NOT_READY' WHERE syncState = 'PENDING' AND endStatus = 'IN_PROGRESS'")
            }
        }

        @Volatile private var instance: ExperimentDatabase? = null

        fun get(context: Context): ExperimentDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ExperimentDatabase::class.java,
                    NAME,
                )
                    // WAL(기본값) 유지: 커밋 후 프로세스가 종료돼도 기록이 남는다.
                    .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }
    }
}
