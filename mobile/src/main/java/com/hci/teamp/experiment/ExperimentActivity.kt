package com.hci.teamp.experiment

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.hci.teamp.shared.experiment.ParticipantId

class ExperimentActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        val participant = EditText(this).apply {
            hint = "참가자 ID (P001)"
            isSingleLine = true
        }
        val status = TextView(this).apply {
            text = "실험 준비 화면 · 과제 설정 대기"
        }
        val schedule = ExperimentSchedule { _, _ -> error("Task order is not configured") }
        layout.addView(participant)
        layout.addView(Button(this).apply {
            text = "조건 순서 확인"
            setOnClickListener {
                val id = participant.text.toString().trim()
                val number = id.drop(1).toIntOrNull()
                if (number == null || runCatching { ParticipantId(id) }.isFailure) {
                    participant.error = "P001 형식의 참가자 ID를 입력하세요"
                    return@setOnClickListener
                }
                status.text = schedule.conditionsFor(number).joinToString(" → ") +
                    "\n실험 준비 화면 · 과제 설정 대기"
            }
        })
        layout.addView(status)
        setContentView(layout)
    }
}
