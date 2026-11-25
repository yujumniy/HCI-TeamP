package com.hci.teamp.shared.experiment

import com.hci.teamp.shared.model.ConditionId
import com.hci.teamp.shared.model.Ids

object FixedRoster {
    const val DATASET = "FIXED18_20261125"
    data class Slot(val number: Int, val id: String, val pilot: Boolean, val group: String, val conditions: List<ConditionId>)
    val slots: List<Slot> = (1..18).map { number ->
        val pilot = number > 16
        val orderNumber = if (pilot) number - 16 else number
        Slot(number, Ids.participantId(number), pilot,
            (if (pilot) "PILOT_" else "") + "G${(orderNumber - 1) % 4 + 1}",
            V1Protocol.conditionsFor(orderNumber).map { ConditionId.valueOf(it.name) })
    }
    fun pilot(group: String): Boolean = group.startsWith("PILOT_")
    fun mode(group: String): String = when {
        pilot(group) -> "PILOT"
        group == DemoPlan.GROUP -> "DEMO"
        else -> "STUDY"
    }
}
