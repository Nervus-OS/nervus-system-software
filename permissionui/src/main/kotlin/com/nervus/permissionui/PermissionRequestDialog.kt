package com.nervus.permissionui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nervus.sysui.NervusIcons
import io.github.nervusos.iface.permission.v1.PermissionGrant
import io.github.nervusos.ipc.v1.RiskClass

/**
 * 权限申请弹窗。
 *
 * # 安全设计：三件事必须成立
 *
 * **一、申请方身份来自内核**。[PendingRequest.callerPackageId] 是 nervud 按连接
 * 凭据认出来的，不是请求里的自述。显示它而不是显示应用自报的名字——否则一个包
 * 能把自己的申请伪装成另一个包的。
 *
 * **二、理由是不受信文本**。[PendingRequest.rationale] 由应用给出，因此明确标注
 * 「以下说明由应用提供」并放在一个视觉上与系统文案区分的容器里。不这么做的话，
 * 一句「系统要求您授予摄像头权限」就能让用户以为这是系统的声明。
 *
 * **三、默认不勾选，一切非「同意」的出口都等于拒绝**。关掉弹窗、按 Esc、超时，
 * 全部按拒绝处理。预先勾上等于替用户先同意了一半——那正是这个弹窗要防的事。
 *
 * # 权限文案全部来自内核
 *
 * 名称、说明、风险等级都在 [PermissionGrant] 里（内核从 Catalog 的权限定义取）。
 * 界面一个字都不写死：第三方包可以定义自己的权限，UI 不可能预先知道它们的文案。
 */
@Composable
fun PermissionRequestDialog(app: PermissionUi, request: PendingRequest) {
    // 默认全不勾。fail closed：用户必须主动为每一条点头
    val checked = remember(request) { mutableStateMapOf<String, Boolean>() }

    // 关掉 / 按 Esc / 点外面 都走这里：等于拒绝全部
    val deny = { app.resolvePendingRequest(emptySet()) }

    AlertDialog(
        onDismissRequest = deny,
        icon = {
            Icon(
                imageVector = NervusIcons.Shield,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
            )
        },
        title = {
            // 标题里出现的是内核认定的申请方，label 缺失才回落到包 ID
            Text("${request.callerLabel.ifEmpty { request.callerPackageId }} 请求权限")
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // 包 ID 始终单独显示一行：label 是 manifest 里的自述字符串，
                // 两个包可以取一样的 label，而包 ID 是唯一的身份
                Text(
                    request.callerPackageId,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )

                if (request.rationale.isNotEmpty()) {
                    RationaleBlock(request.rationale)
                }

                Text(
                    "勾选你愿意授予的权限：",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )

                request.items.forEach { grant ->
                    PermissionRequestRow(
                        grant = grant,
                        checked = checked[grant.permissionId] == true,
                        onCheckedChange = { checked[grant.permissionId] = it },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                // 只把勾上的那些交回去。一条都没勾等同于拒绝——不需要特例，
                // 空集合走的就是拒绝那条路
                app.resolvePendingRequest(
                    request.items
                        .map { it.permissionId }
                        .filter { checked[it] == true }
                        .toSet()
                )
            }) {
                Text("同意所选")
            }
        },
        dismissButton = {
            TextButton(onClick = deny) { Text("拒绝") }
        },
    )
}

/**
 * 应用自称的理由。
 *
 * 放在一个带底色的容器里并冠以「以下说明由应用提供」，是为了让它**看起来不像
 * 系统在说话**。这一条是防伪造的界面部分：文本内容完全由申请方控制。
 */
@Composable
private fun RationaleBlock(rationale: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "以下说明由应用提供",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
            Text(rationale, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** 一条待勾选的权限。名称、说明、风险等级都来自内核。 */
@Composable
private fun PermissionRequestRow(
    grant: PermissionGrant,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // 中文缺失回落英文，再缺失才回落权限 ID——那说明定义方没写文案，
            // 是它的 bug，但界面不该因此显示一片空白
            Text(
                grant.displayName.zhCn.ifEmpty {
                    grant.displayName.en.ifEmpty { grant.permissionId }
                },
                style = MaterialTheme.typography.bodyLarge,
            )
            val desc = grant.description.zhCn.ifEmpty { grant.description.en }
            if (desc.isNotEmpty()) {
                Text(
                    desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // 高风险的那两档要显式警示。**内核给的风险等级，不是界面猜的**：
            // 一条会让机械臂动起来的权限，与一条读取文件的权限，在同一个列表里
            // 长得一样的话，用户会用同一种随意态度对待它们
            riskWarning(grant.riskClass)?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

/**
 * 高风险等级的警示文案；普通等级返回 null（不加噪音）。
 *
 * 只对 PHYSICAL_CONTROL 与 CRITICAL_SAFETY 出警示：那两档的后果作用在物理世界，
 * 与「读到了不该读的文件」不是同一量级——机器动起来可能伤人。
 */
private fun riskWarning(riskClass: RiskClass): String? = when (riskClass) {
    RiskClass.RISK_CLASS_PHYSICAL_CONTROL -> "⚠ 这条权限允许应用控制机器的运动"
    RiskClass.RISK_CLASS_CRITICAL_SAFETY -> "⚠ 这条权限涉及安全关键功能"
    else -> null
}
