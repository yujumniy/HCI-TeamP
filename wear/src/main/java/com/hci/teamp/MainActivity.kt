package com.hci.teamp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text

/**
 * 워치 앱 자리 표시.
 * C3(터치 베젤)·C4(슬라이더)는 워치 담당 팀원이 feature/wear-input 브랜치에서 구현합니다.
 * 공통 시행 화면은 shared 모듈의 TrialPanel(round = true)을 사용하면 됩니다.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text("HCI 워치 앱\nC3·C4 구현 예정", textAlign = TextAlign.Center)
                }
            }
        }
    }
}
