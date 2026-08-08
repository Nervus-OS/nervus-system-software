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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import com.nervus.sysui.NervusIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 权限页：跳转到权限管理界面。
 *
 * ## 为什么是跳转而不是在这里直接管
 *
 * 改一条权限的运行期授予状态需要 `perm.permission.admin`，而那条权限要求
 * **platform-release** 签名。设置应用签的是 platform-systemapp——够 Platform
 * 信任，但拿不到这一条。
 *
 * 这不是配置疏忽。「能给任意应用开摄像头和运动控制的权限」一旦落到设置手里，
 * 就等于把它和一个功能繁多、迭代频繁的包绑在同一条签名链上。Android 把
 * PermissionController 做成独立 APK 是同一个理由。
 *
 * 所以这里只做一件事：把用户送到 `nervus.permissionui`。
 *
 * ## 为什么入口仍然放在设置里
 *
 * 权限管理在用户心里就属于「设置」。不放的话，用户唯一能找到它的地方是桌面上
 * 一个图标——而多数人不会想到去那里找「撤销某个应用的摄像头权限」。
 */
@Composable
fun PermissionsPage(settings: Settings) {
    val scope = rememberCoroutineScope()
    var launching by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("权限", style = MaterialTheme.typography.headlineMedium)
        Text(
            "查看每个应用申请了哪些敏感权限，并逐条决定给不给。撤销立即生效，" +
                "不需要重启应用。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(8.dp))

        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Row(
                Modifier.padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Icon(
                    imageVector = NervusIcons.Shield,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(32.dp),
                )
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "权限管理由独立的系统组件承载",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "授予与撤销敏感权限的能力不在设置应用手里——它由一个单独签名的" +
                            "组件独占，这样设置本身被攻破也改不了任何应用的权限。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Button(
            onClick = {
                error = null
                launching = true
                scope.launch {
                    // 内核要等目标进程起来并报到，会阻塞若干秒，必须离开 UI 线程
                    withContext(Dispatchers.IO) {
                        runCatching { settings.openPermissionManager() }
                            .onFailure { error = it.message ?: it::class.simpleName }
                    }
                    launching = false
                }
            },
            enabled = !launching,
            modifier = Modifier.height(52.dp),
        ) {
            Icon(
                imageVector = NervusIcons.Shield,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(if (launching) "正在打开…" else "打开权限管理")
        }

        // 失败要显示出来。拉起一个组件可能因为它被停用、或者本应用没有
        // perm.system.launch 而失败——静默的话用户只会看到「点了没反应」
        error?.let {
            Text(
                "打不开权限管理：$it",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
