package com.nervus.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nervus.sdk.ui.NervusBackHandler
import com.nervus.sysui.NervusIcons
import com.nervus.sysui.PowerAction
import com.nervus.sysui.PowerConfirmDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 电源页：有序重启 / 关机。
 *
 * ## 为什么调用抛异常不显示为失败
 *
 * 内核收到请求后经 systemd 立刻开始停机，控制面连接随之断开 —— **成功的路径上
 * 大概率就是抛异常**。把它显示成「关机失败」会让用户以为没生效而反复点，
 * 而每一次点都是一次真实的关机请求。所以这里一律显示「已发出」，
 * 把可能的异常降级成一行诊断文字。
 */
@Composable
fun PowerPage(settings: Settings) {
    val scope = rememberCoroutineScope()

    // pending 非空 = 正在等用户确认某个动作
    var pending by remember { mutableStateOf<PowerAction?>(null) }
    // 已发出后不再回到可点击状态：机器正在关，界面上再放一个能点的按钮没有意义
    var issued by remember { mutableStateOf<PowerAction?>(null) }
    var diagnostic by remember { mutableStateOf<String?>(null) }

    NervusBackHandler(enabled = pending != null) {
        pending = null
    }

    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("电源", style = MaterialTheme.typography.headlineMedium)
        Text(
            "重启与关机都经 systemd 走完整的停机流程：正在运行的组件会收到终止信号，" +
                "文件系统会被正常卸载。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(8.dp))

        val current = issued
        if (current != null) {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            ) {
                Row(
                    Modifier.padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Icon(
                        imageVector = if (current == PowerAction.Reboot) {
                            NervusIcons.Restart
                        } else {
                            NervusIcons.Power
                        },
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(32.dp),
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "${current.label}请求已发出",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Text(
                            "系统正在停止各个组件，屏幕稍后会熄灭。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedButton(
                    onClick = { pending = PowerAction.Reboot },
                    modifier = Modifier.height(52.dp),
                ) {
                    Icon(
                        imageVector = NervusIcons.Restart,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(PowerAction.Reboot.label)
                }
                Button(
                    onClick = { pending = PowerAction.PowerOff },
                    modifier = Modifier.height(52.dp),
                    // error 配色：关机是破坏性动作，M3 用 error 容器表达
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Icon(
                        imageVector = NervusIcons.Power,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(PowerAction.PowerOff.label)
                }
            }
        }

        diagnostic?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    pending?.let { action ->
        PowerConfirmDialog(
            action = action,
            onDismiss = { pending = null },
            onConfirm = {
                pending = null
                issued = action
                scope.launch {
                    // 调用会阻塞到连接断开，必须离开 UI 线程，
                    // 否则界面在停机期间是卡死的
                    withContext(Dispatchers.IO) {
                        runCatching { settings.power(action) }.onFailure {
                            // 见类注释：这条大概率是「连接已断开」，也就是成功的样子。
                            // 只作为诊断信息展示，不改变上面的「已发出」结论
                            diagnostic = "控制面响应：${it.message ?: it::class.simpleName}"
                        }
                    }
                }
            },
        )
    }
}
