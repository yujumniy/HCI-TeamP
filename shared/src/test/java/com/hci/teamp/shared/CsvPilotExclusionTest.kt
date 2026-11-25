package com.hci.teamp.shared

import com.hci.teamp.shared.db.toEntity
import com.hci.teamp.shared.export.CsvExport
import com.hci.teamp.shared.metrics.TrialMetrics
import com.hci.teamp.shared.model.*
import org.junit.Assert.*
import org.junit.Test

class CsvPilotExclusionTest {
    @Test fun pilotCompletedMainTrialsAreExcludedWithoutDeletingData() {
        val cfg = TrialConfig("P017-C2-T01-A01", "P017", ConditionId.C2, "T01", 1, TrialPhase.MAIN, 10, 80, 1)
        val events = listOf(
            TrialEvent(cfg.attemptId, 0, EventType.START, 0, 1000, 10),
            TrialEvent(cfg.attemptId, 1, EventType.VALUE_CHANGE, 1_000_000, 1001, 80),
            TrialEvent(cfg.attemptId, 2, EventType.COMPLETE, 2_000_000, 1002, 80))
        val record = AttemptRecord(TrialResult(cfg, DeviceType.PHONE, "test", EndStatus.COMPLETED,
            80, 2.0, 1000, 1002, metrics = TrialMetrics.compute(10,80,events)), events)
        val row = record.toEntity(SyncState.RECEIVED, "test", null)
        val csv = CsvExport.trialsCsv(listOf(row), setOf("P017")).trim().lines()
        assertEquals(2, csv.size)
        val columns = csv[0].split(',').map { it.trim() }
        val values = csv[1].split(',').map { it.trim() }
        assertEquals("P017", values[columns.indexOf("participant_id")])
        assertEquals("false", values[columns.indexOf("included_in_main_analysis")])
        assertEquals("true", CsvExport.trialsCsv(listOf(row)).trim().lines()[1].split(',')[columns.indexOf("included_in_main_analysis")])
    }
}
