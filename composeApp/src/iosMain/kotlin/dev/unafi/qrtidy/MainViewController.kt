package dev.unafi.qrtidy

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * iOS版のメインViewController。
 * 現時点ではプレースホルダーUI。
 * ステップ2でカメラ/QRスキャン機能を実装予定。
 */
fun MainViewController() = androidx.compose.ui.window.ComposeUIViewController {
    MaterialTheme {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("QRTidy", fontSize = 28.sp)
            Spacer(modifier = Modifier.height(16.dp))
            Text("iOS版は準備中です", fontSize = 16.sp)
            Spacer(modifier = Modifier.height(8.dp))
            Text("ステップ2で実装予定", fontSize = 14.sp)
        }
    }
}