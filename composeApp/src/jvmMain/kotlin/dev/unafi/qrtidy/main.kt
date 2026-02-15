package dev.unafi.qrtidy

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

/**
 * JVM (Desktop) 版のエントリポイント。
 * 現時点ではプレースホルダーUI。
 */
fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "QRTidy") {
        MaterialTheme {
            Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("QRTidy", fontSize = 28.sp)
                Spacer(modifier = Modifier.height(16.dp))
                Text("Desktop版は準備中です", fontSize = 16.sp)
            }
        }
    }
}