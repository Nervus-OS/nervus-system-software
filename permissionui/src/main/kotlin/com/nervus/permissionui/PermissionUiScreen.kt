package com.nervus.permissionui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nervus.sysui.NervusIcons
import com.nervus.sysui.iconForPackage
import com.nervus.sysui.rememberPolled
import io.github.nervusos.iface.permission.v1.GrantState
import io.github.nervusos.iface.permission.v1.PackageGrants
import io.github.nervusos.iface.permission.v1.PermissionGrant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 权限管理界面。
 *
 * 一个包一张卡，卡里一行一条 USER_CONSENT 权限。**列表内容完全由内核给出**
 * ——包括每条权限的中文名与说明：第三方包可以定义自己的权限，界面不可能
 * 预先知道它们的文案。
 */
@Composable
fun PermissionUiScreen(app: PermissionUi) {
    // 轮询而不是订阅：授予状态主要由用户在本界面自己改，外部变更（装包、卸载）
    // 是低频事件。PermissionAdmin 也没有声明任何事件
    val packages by rememberPolled<List<PackageGrants>>(emptyList(), intervalMs = 5000) {
        app.listGrants()
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Scaffold { inner ->
            val box = Modifier.fillMaxSize().padding(inner)
            when {
                // 三态分开。「读不出」与「没有」绝不能显示成同一句话：
                // 后者是"没人申请过敏感权限"，前者是"我不知道有没有"，
                // 把前者说成后者会让用户以为系统里没有任何权限需要过问
                packages.value.isEmpty() && packages.error != null ->
                    Message(box, "读不出权限列表", packages.error)

                packages.value.isEmpty() && packages.loading ->
                    Message(box, "正在读取…", null)

                packages.value.isEmpty() ->
                    Message(
                        box,
                        "没有应用申请敏感权限",
                        "应用申请摄像头、用户文件或运动控制后会出现在这里",
                    )

                else -> LazyColumn(
                    modifier = box,
                    contentPadding = PaddingValues(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(packages.value, key = { it.packageId }) { pkg ->
                        PackageCard(app, pkg)
                    }
                }
            }
        }
    }
}

/** 整页居中的一句话 + 可选副文案。加载中/读不出/空态共用。 */
@Composable
private fun Message(modifier: Modifier, title: String, detail: String?) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                NervusIcons.Apps,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.outline,
            )
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 16.dp),
            )
            if (!detail.isNullOrEmpty()) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun PackageCard(app: PermissionUi, pkg: PackageGrants) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                iconForPackage(pkg.packageId),
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(Modifier.padding(start = 12.dp)) {
                // label 可能为空（manifest 没写），回落到 package_id
                Text(
                    pkg.label.ifEmpty { pkg.packageId },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    pkg.packageId,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
        HorizontalDivider()
        pkg.permissionsList.forEach { grant ->
            PermissionRow(app, pkg.packageId, grant)
        }
    }
}

@Composable
private fun PermissionRow(app: PermissionUi, packageId: String, grant: PermissionGrant) {
    val scope = rememberCoroutineScope()
    // 本地覆盖层：开关拨动后立刻反映用户的意图，等内核回了权威状态再校正。
    // 不做这一层的话，开关要等一次 IPC 往返才动，手感像卡住了
    val pending = remember { mutableStateMapOf<String, Boolean>() }
    var failure by remember { mutableStateOf<String?>(null) }

    val authoritative = grant.state == GrantState.GRANT_STATE_GRANTED
    val checked = pending[grant.permissionId] ?: authoritative

    ListItem(
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        headlineContent = {
            // 内核给的是 LocalizedText{zh_cn, en}；中文缺失时回落到英文，
            // 再缺失才回落到权限 ID——那说明定义方没写文案，是它的 bug，
            // 但界面不该因此显示一片空白
            Text(
                grant.displayName.zhCn.ifEmpty {
                    grant.displayName.en.ifEmpty { grant.permissionId }
                }
            )
        },
        supportingContent = {
            val desc = grant.description.zhCn.ifEmpty { grant.description.en }
            Column {
                if (desc.isNotEmpty()) Text(desc)
                failure?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = { want ->
                    pending[grant.permissionId] = want
                    failure = null
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching { app.setGrantState(packageId, grant.permissionId, want) }
                        }
                        result.onSuccess { state ->
                            // 以内核回的现状为准，而不是刚才请求的那个值
                            pending[grant.permissionId] =
                                state == GrantState.GRANT_STATE_GRANTED
                        }.onFailure { e ->
                            // 失败就把开关拨回权威状态，并说明原因。留在用户
                            // 拨到的位置会让他以为改成功了
                            pending.remove(grant.permissionId)
                            failure = "修改失败：${e.message ?: "未知原因"}"
                        }
                    }
                },
            )
        },
    )
}
