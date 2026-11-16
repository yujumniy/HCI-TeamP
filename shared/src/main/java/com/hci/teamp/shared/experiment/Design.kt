package com.hci.teamp.shared.experiment

/** 명세 1.2 실험 조건 */
enum class Device { PHONE, WATCH }

enum class InputMethod { PERIPHERAL, DIRECT }

enum class Condition(val number: Int, val device: Device, val input: InputMethod, val label: String) {
    C1(1, Device.PHONE, InputMethod.PERIPHERAL, "스마트폰 · 물리 음량 버튼"),
    C2(2, Device.PHONE, InputMethod.DIRECT, "스마트폰 · 수평 슬라이더"),
    C3(3, Device.WATCH, InputMethod.PERIPHERAL, "워치 · 터치 베젤"),
    C4(4, Device.WATCH, InputMethod.DIRECT, "워치 · 수평 슬라이더"),
}

/** 과제 정의 (시작값 → 목표값) */
data class TaskDef(val id: String, val start: Int, val target: Int, val practice: Boolean) {
    val delta: Int get() = target - start
    val increasing: Boolean get() = target > start
}

/** 명세 6장 과제. 숫자·순서는 합의 없이 바꾸지 않습니다. */
object Tasks {
    val PRACTICE: List<TaskDef> = listOf(
        TaskDef("PR01", 25, 65, practice = true),
        TaskDef("PR02", 75, 35, practice = true),
    )

    val MAIN: List<TaskDef> = listOf(
        TaskDef("T01", 10, 80, practice = false),
        TaskDef("T02", 20, 75, practice = false),
        TaskDef("T03", 40, 55, practice = false),
        TaskDef("T04", 50, 70, practice = false),
        TaskDef("T05", 90, 20, practice = false),
        TaskDef("T06", 80, 25, practice = false),
        TaskDef("T07", 60, 45, practice = false),
        TaskDef("T08", 70, 50, practice = false),
    )

    val ALL: List<TaskDef> = PRACTICE + MAIN

    fun byId(id: String): TaskDef = ALL.first { it.id == id }

    /** 명세 7.2 기본 8개 과제 순서 (과제 번호) */
    val BASE_ORDER: List<Int> = listOf(1, 2, 8, 3, 7, 4, 6, 5)
}

/** 명세 7.1 조건 순서 그룹 */
enum class Group(val order: List<Condition>) {
    G1(listOf(Condition.C1, Condition.C2, Condition.C4, Condition.C3)),
    G2(listOf(Condition.C2, Condition.C3, Condition.C1, Condition.C4)),
    G3(listOf(Condition.C3, Condition.C4, Condition.C2, Condition.C1)),
    G4(listOf(Condition.C4, Condition.C1, Condition.C3, Condition.C2)),
}

object Counterbalance {
    /** 참가자 번호 p(1부터)에 따라 G1→G2→G3→G4 순환 배정 */
    fun groupFor(participant: Int): Group {
        require(participant >= 1) { "참가자 번호는 1 이상" }
        return Group.entries[(participant - 1) % 4]
    }

    /**
     * 명세 7.2 과제 순서.
     * offset = ((p - 1) + 2 × (c - 1)) mod 8, 각 과제 번호 t → ((t - 1 + offset) mod 8) + 1
     */
    fun taskOrder(participant: Int, condition: Condition): List<TaskDef> {
        require(participant >= 1) { "참가자 번호는 1 이상" }
        val offset = ((participant - 1) + 2 * (condition.number - 1)).mod(8)
        return Tasks.BASE_ORDER.map { t ->
            val shifted = ((t - 1 + offset).mod(8)) + 1
            Tasks.MAIN[shifted - 1]
        }
    }
}

/** 명세 12장 ID 규칙 */
object Ids {
    fun participant(p: Int): String = "P%03d".format(p)

    /** 예: P001-C3-T04 (연습은 P001-C3-PR01) */
    fun task(p: Int, condition: Condition, task: TaskDef): String =
        "${participant(p)}-${condition.name}-${task.id}"

    /** 예: P001-C3-T04-A01 */
    fun attempt(p: Int, condition: Condition, task: TaskDef, attempt: Int): String =
        "${task(p, condition, task)}-A%02d".format(attempt)
}
