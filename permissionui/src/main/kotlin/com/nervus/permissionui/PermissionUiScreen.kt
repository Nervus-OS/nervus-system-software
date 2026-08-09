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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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

    // OpenManager 带来的「只看这一个包」。空串 = 总览。
    //
    // 写它的是 IPC dispatch 线程，读它的是这里（UI 线程），所以中间隔着一个
    // StateFlow 而不是 Compose 状态——见 PermissionUi.managerFilter 的说明
    val filter by app.managerFilter.collectAsState()

    // 正在等用户回答的权限申请。同样隔着 StateFlow——写它的是工作线程。
    //
    // 弹窗【叠在管理界面之上】而不是另开一个窗口：Nervus 是单前台窗口环境，
    // 第二个窗口未必浮得上来，而一个浮不上来的授权弹窗会让申请静默超时。
    val pending by app.pendingRequest.collectAsState()

    // 筛选在【显示层】做而不是让内核只返回一个包：ListGrants 没有按包过滤的
    // 参数，而且总览与单包看的是同一份数据。多发一次调用只会让两个视图有机会
    // 显示不一致的状态
    val shown = if (filter.isEmpty()) {
        packages.value
    } else {
        packages.value.filter { it.packageId == filter }
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

                // 筛选之后为空是【另一件事】：数据读到了，只是那个包没有可授予
                // 权限（或压根没装）。说成「没有应用申请敏感权限」会让从设置跳
                // 过来的用户以为全系统都没有权限要管
                shown.isEmpty() ->
                    Message(
                        box,
                        "这个应用没有可管理的权限",
                        filter,
                    )

                else -> LazyColumn(
                    modifier = box,
                    contentPadding = PaddingValues(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    // 从设置跳进来时给一条回到总览的出口。没有它，用户被锁在
                    // 单包视图里，而这个界面没有别的导航
                    if (filter.isNotEmpty()) {
                        item {
                            TextButton(onClick = { app.clearManagerFilter() }) {
                                Text("← 查看全部应用的权限")
                            }
                        }
                    }
                    items(shown, key = { it.packageId }) { pkg ->
                        PackageCard(app, pkg)
                    }
                }
            }
        }
    }

    // 申请弹窗叠在管理界面之上。放在 Surface 之外、composable 末尾：AlertDialog
    // 自己是一层 overlay，嵌在 Scaffold 的内容里会被那份 padding 和滚动状态影响。
    //
    // 它不受 filter 影响 —— 一次权限申请必须无条件问到用户，不能因为界面此刻
    // 正收窄在某个别的包上就不显示
    pending?.let { PermissionRequestDialog(app, it) }
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
                    // 用户在动这个界面 —— 一次并发的权限申请结束后不能把它收走。
                    // 必须在这里也置位，不能只靠 OpenManager：从桌面直接打开本
                    // 应用的那条路上没有任何 IPC 调用经过我们
                    app.markWindowWanted()
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
