package com.hci.teamp.shared.model

/** Versioned input resolution; old records without START metadata remain five-unit records. */
object InputResolution {
    const val VERSION = "SLIDER_1_BUTTON_BEZEL_5_V1"
    const val LEGACY = "LEGACY_5"
    fun step(condition: ConditionId): Int = if (condition == ConditionId.C2 || condition == ConditionId.C4) 1 else 5
    fun valid(value: Int?, step: Int): Boolean = value == null || (value in 0..100 && value % step == 0)
    fun version(record: AttemptRecord): String = record.events.firstOrNull { it.type == EventType.START }
        ?.raw?.get("input_policy") ?: LEGACY
    fun recordedStep(record: AttemptRecord): Int = if (version(record) == VERSION) step(record.result.config.condition) else 5
}
