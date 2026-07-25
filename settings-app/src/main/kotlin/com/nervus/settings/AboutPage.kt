package com.nervus.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 关于页。连点版本号 [TAPS_TO_UNLOCK] 次解锁开发者选项 —— 沿用 Android 的
 * 交互约定，用户不需要被教。
 */
@Composable
fun AboutPage(
    developerUnlocked: Boolean,
    onUnlock: () -> Unit,
) {
    // 连点计数只活在本次可见期间：离开关于页再回来就重新数。
    // 解锁状态本身由调用方持有（提升到 SettingsScreen），否则切一次页就丢了
    var taps by remember { mutableStateOf(0) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text("Nervus OS", style = MaterialTheme.typography.headlineMedium)

        Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                InfoRow("平台 API Level", SystemInfo.API_LEVEL.toString())
                InfoRow("控制面", SystemInfo.CONTROL_SOCKET)

                // 版本号是解锁入口。整行可点，而不只是数字——手指点不准一个
                // 小数字，而这个交互的全部意义就在于"能被偶然发现"
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = !developerUnlocked) {
                        taps++
                        if (taps >= TAPS_TO_UNLOCK) {
                            taps = 0
                            onUnlock()
                        }
                    },
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("系统版本", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        SystemInfo.VERSION,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                }

                // 只在"快到了"时才提示。一上来就说"再点 7 次"会把彩蛋变成说明书；
                // 完全不提示则会让用户点了三下以为坏了
                val remaining = TAPS_TO_UNLOCK - taps
                if (!developerUnlocked && taps in HINT_FROM until TAPS_TO_UNLOCK) {
                    Text(
                        "再点 $remaining 次进入开发者选项",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (developerUnlocked) {
                    Text(
                        "开发者选项已开启",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        Text(
            "机器人操作系统。内核 nervud 负责安全、身份、权限与组件生命周期；" +
                "应用与服务经控制面 IPC 通信。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private const val TAPS_TO_UNLOCK = 6
private const val HINT_FROM = 3

/**
 * 系统标识。
 *
 * **这些值目前是编译期常量。** 内核没有把版本沿 IPC 投影出来的通道——
 * `PackageInfo` 里只有各个包的版本，没有系统自身的。要显示真值需要一个
 * 系统信息接口（或给 pkgmanager 加一个 method），属于 v2。
 *
 * `API_LEVEL` 与内核 `pkgregistry.CurrentAPILevel` 必须一致：manifest 的
 * `min_nervus_api` 高于它就装不上，这里显示错了会让人对着一个假数字排查。
 */
object SystemInfo {
    const val API_LEVEL = 1
    const val VERSION = "0.1.0"
    const val CONTROL_SOCKET = "/run/nervus/nervud.sock"
}
