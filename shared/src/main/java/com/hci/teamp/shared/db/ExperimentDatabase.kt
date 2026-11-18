package com.hci.teamp.shared.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

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
    ],
    version = 1,
    exportSchema = true,
)
abstract class ExperimentDatabase : RoomDatabase() {
    abstract fun dao(): ExperimentDao

    companion object {
        private const val NAME = "hci_experiment.db"

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
                    .build()
                    .also { instance = it }
            }
    }
}
