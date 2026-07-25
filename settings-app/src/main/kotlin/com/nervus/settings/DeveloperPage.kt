package com.nervus.settings

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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nervus.sysui.rememberPolled
import io.github.nervusos.iface.pkgmanager.v1.PackageInfo

/**
 * 开发者选项。
 *
 * ## 为什么这里【只有诊断，没有开关】
 *
 * 内核的开发者开关（`allow_unverified_signature` / `allow_downgrade` /
 * `skip_oem_countersign`）存在 `/var/lib/nervus/registry/_devmode.json`，
 * 那个目录对所有组件是 `InaccessiblePaths` —— 设置应用读不到也写不了，
 * **这是有意的**：能改装包准入的开关不该由一个 GUI 应用握着。
 *
 * 要改它们得用 `nervusctl`（走 root-only 管理通道）。所以这一页做成"把内核
 * 此刻的真实状态摊开给你看"，而不是摆几个点了没反应的开关 —— 后者比没有
 * 更糟，它会让人以为自己关掉了某个东西。
 *
 * 等内核开出对应的管理接口后，再在这里加真开关。
 */
@Composable
fun DeveloperPage(settings: Settings) {
    val packages by rememberPolled<List<PackageInfo>>(emptyList(), intervalMs = 5000) {
        settings.listPackages()
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text("开发者选项", style = MaterialTheme.typography.headlineSmall)

        // ---- 包统计：一眼看出信任分级有没有生效 ----
        val bySource = packages.value.groupingBy { it.source }.eachCount()
        val byTrust = packages.value.groupingBy { it.trust }.eachCount()

        Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("已装包", style = MaterialTheme.typography.titleSmall)
                Kv("总数", packages.value.size.toString())
                bySource.forEach { (k, v) -> Kv("来源 $k", v.toString()) }
                byTrust.forEach { (k, v) -> Kv("trust $k", v.toString()) }
            }
        }

        // trust 全是 ordinary 说明信任根没配好。这是生产镜像上最容易漏、
        // 后果最大的一个配置错误——整机的信任分级全部失效，而系统照常运行
        if (packages.value.isNotEmpty() && byTrust.keys.all { it == "ordinary" }) {
            Warning(
                "所有包的 trust 都是 ordinary",
                "说明平台签名没验过：要么是开发构建（nervud 没内嵌平台根），" +
                    "要么是 /usr/share/nervus/trust 的 bundle 配置错了。" +
                    "生产镜像上出现这个，整机的信任分级是失效的。",
            )
        }

        Warning(
            "权限执法当前是关闭的",
            "内核的 permission.V1GrantAll = true：manifest 申请什么就授予什么，" +
                "不看 trust 门槛，运行期也不做用户确认。下面每个包列出的权限都是" +
                "「申请到即得到」的结果，不代表执法恢复后仍然拿得到。",
        )

        // ---- 逐包的完整权限投影 ----
        Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("权限投影（内核裁决结果）", style = MaterialTheme.typography.titleSmall)
                if (packages.value.isEmpty()) {
                    Text("（读不到包列表）", style = MaterialTheme.typography.bodySmall)
                }
                packages.value.forEach { info ->
                    Column {
                        Text(
                            info.packageId,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            if (info.grantedPermissionsCount == 0) "（无）"
                            else info.grantedPermissionsList.joinToString("  "),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (info.disabledComponentsCount > 0) {
                            Text(
                                "已停用组件: ${info.disabledComponentsList.joinToString(", ")}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }

        // ---- 内核开关：只说明位置，不假装能改 ----
        Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("内核开发者开关", style = MaterialTheme.typography.titleSmall)
                Text(
                    "存在 /var/lib/nervus/registry/_devmode.json。该目录对所有组件" +
                        "不可访问（沙箱 InaccessiblePaths），设置应用读不到也改不了。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Kv("allow_unverified_signature", "见 nervusctl")
                Kv("allow_downgrade", "见 nervusctl")
                Kv("skip_oem_countersign", "见 nervusctl")
            }
        }
    }
}

@Composable
private fun Kv(k: String, v: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(k, style = MaterialTheme.typography.bodySmall)
        Text(
            v,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Warning(title: String, detail: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}
