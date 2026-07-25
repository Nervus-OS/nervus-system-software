package com.nervus.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nervus.sdk.ui.NervusBackHandler
import com.nervus.sysui.AppIdentity
import com.nervus.sysui.NervusIcons
import com.nervus.sysui.iconForPackage
import com.nervus.sysui.rememberPolled
import io.github.nervusos.iface.pkgmanager.v1.PackageInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Page(val label: String, val icon: ImageVector) {
    Packages("已装软件", NervusIcons.Apps),
    Power("电源", NervusIcons.Power),
    About("关于", NervusIcons.Info),
    Developer("开发者", NervusIcons.DeveloperMode),
}

@Composable
fun SettingsScreen(settings: Settings) {
    var page by remember { mutableStateOf(Page.Packages) }
    val pageHistory = remember { mutableStateListOf<Page>() }
    // 解锁状态提升到这里而不是留在关于页：否则切一次页面就丢了，
    // 用户得重新点六次
    var developerUnlocked by remember { mutableStateOf(false) }

    fun navigateTo(target: Page) {
        if (target == page) return
        pageHistory += page
        page = target
    }

    NervusBackHandler(enabled = pageHistory.isNotEmpty()) {
        page = pageHistory.removeAt(pageHistory.lastIndex)
    }

    Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        NavigationRail(
            containerColor = MaterialTheme.colorScheme.surface,
            header = {
                Text(
                    "设置",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(vertical = 20.dp),
                )
            },
        ) {
            // 开发者入口在解锁前【不出现】——出现但置灰等于直接告诉用户有这个
            // 东西，那就不是彩蛋了
            Page.entries.filter { it != Page.Developer || developerUnlocked }.forEach { p ->
                NavigationRailItem(
                    selected = page == p,
                    onClick = { navigateTo(p) },
                    icon = {
                        Icon(
                            imageVector = p.icon,
                            contentDescription = p.label,
                        )
                    },
                    label = { Text(p.label) },
                    alwaysShowLabel = true,
                )
            }
        }
        Box(Modifier.fillMaxSize()) {
            when (page) {
                Page.Packages -> PackagesPage(settings)
                Page.Power -> PowerPage(settings)
                Page.About -> AboutPage(
                    developerUnlocked = developerUnlocked,
                    onUnlock = {
                        developerUnlocked = true
                        navigateTo(Page.Developer)
                    },
                )
                Page.Developer -> DeveloperPage(settings)
            }
        }
    }
}

@Composable
private fun PackagesPage(settings: Settings) {
    // 5 秒轮询。停用/卸载之后不手动刷新，等下一轮——这样界面显示的永远是
    // 内核的实际状态，而不是"我以为我改成功了"的乐观更新。差别在失败时：
    // 乐观更新会让一个被拒绝的停用在界面上看起来成功了
    val packages by rememberPolled<List<PackageInfo>>(emptyList(), intervalMs = 5000) {
        settings.listPackages()
    }

    var selected by remember { mutableStateOf<String?>(null) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var confirmUninstall by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    NervusBackHandler(
        enabled = confirmUninstall != null || actionError != null || selected != null,
    ) {
        when {
            confirmUninstall != null -> confirmUninstall = null
            actionError != null -> actionError = null
            selected != null -> selected = null
        }
    }

    fun run(block: () -> Unit) {
        scope.launch {
            actionError = null
            try {
                withContext(Dispatchers.IO) { block() }
            } catch (e: Exception) {
                // 内核的拒绝原因原样展示：系统包不可卸载、组件受保护…
                // UI 不复述也不改写，因为权威判断在那一侧
                actionError = e.message ?: "操作失败"
            }
        }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { pad ->
        Row(Modifier.fillMaxSize().padding(pad)) {
            Column(Modifier.width(360.dp).fillMaxHeight()) {
                Text(
                    "已装软件",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(20.dp),
                    color = MaterialTheme.colorScheme.onBackground,
                )
                when {
                    packages.value.isEmpty() && packages.error != null ->
                        Message("列不出软件", packages.error)
                    packages.value.isEmpty() && packages.loading ->
                        Message("读取中…", null)
                    packages.value.isEmpty() ->
                        Message("没有已装软件", null)
                    else -> LazyColumn {
                        items(packages.value, key = { it.packageId }) { info ->
                            ListItem(
                                leadingContent = {
                                    PackageGlyph(
                                        packageId = info.packageId,
                                        selected = selected == info.packageId,
                                    )
                                },
                                headlineContent = { Text(AppIdentity.displayName(info.packageId)) },
                                supportingContent = {
                                    Text(
                                        "${info.packageId} · ${info.version}",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                },
                                trailingContent = { SourceChip(info.source) },
                                colors = ListItemDefaults.colors(
                                    containerColor = if (selected == info.packageId)
                                        MaterialTheme.colorScheme.surfaceVariant
                                    else MaterialTheme.colorScheme.background
                                ),
                                modifier = Modifier.clickableRow { selected = info.packageId },
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }

            Surface(
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxSize(),
            ) {
                val info = packages.value.firstOrNull { it.packageId == selected }
                if (info == null) {
                    Message("选择左侧的一项查看详情", null)
                } else {
                    PackageDetail(
                        info = info,
                        error = actionError,
                        onToggleComponent = { comp, enabled ->
                            run { settings.setComponentEnabled(info.packageId, comp, enabled) }
                        },
                        onUninstall = { confirmUninstall = info.packageId },
                    )
                }
            }
        }
    }

    confirmUninstall?.let { pkg ->
        AlertDialog(
            onDismissRequest = { confirmUninstall = null },
            title = { Text("卸载 ${AppIdentity.displayName(pkg)}？") },
            text = { Text("$pkg 及其数据将被移除。此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmUninstall = null
                    selected = null
                    run { settings.uninstall(pkg) }
                }) { Text("卸载") }
            },
            dismissButton = {
                TextButton(onClick = { confirmUninstall = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun PackageDetail(
    info: PackageInfo,
    error: String?,
    onToggleComponent: (String, Boolean) -> Unit,
    onUninstall: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            PackageGlyph(packageId = info.packageId, selected = true, size = 48)
            Column {
                Text(
                    AppIdentity.displayName(info.packageId),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    info.packageId,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SourceChip(info.source)
            AssistChip(onClick = {}, label = { Text("trust: ${info.trust}") })
            AssistChip(onClick = {}, label = { Text("v${info.version} (${info.versionCode})") })
        }

        if (info.grantedPermissionsCount > 0) {
            Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(16.dp)) {
                    Text("已授予权限", style = MaterialTheme.typography.titleSmall)
                    info.grantedPermissionsList.forEach {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        // 组件开关。
        //
        // PackageInfo 只给出【已停用】的组件 ID，没有完整组件列表——所以这里
        // 只能把已停用的列出来供重新启用。要展示全部组件并逐个开关，需要
        // PackageInfo 投影 components[]（与 label/icon 是同一处缺口）
        if (info.disabledComponentsCount > 0) {
            Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(16.dp)) {
                    Text("已停用的组件", style = MaterialTheme.typography.titleSmall)
                    info.disabledComponentsList.forEach { comp ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(comp, style = MaterialTheme.typography.bodyMedium)
                            Switch(
                                checked = false,
                                onCheckedChange = { onToggleComponent(comp, true) },
                            )
                        }
                    }
                }
            }
        }

        error?.let {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }

        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomStart) {
            // 系统包的卸载按钮不置灰：让内核给出权威答复（ErrSystemPackageImmutable），
            // UI 原样展示。在这里预判等于制造第二个真相源
            TextButton(onClick = onUninstall) { Text("卸载") }
        }
    }
}

@Composable
private fun PackageGlyph(
    packageId: String,
    selected: Boolean,
    size: Int = 40,
) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(RoundedCornerShape((size / 3).dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = iconForPackage(packageId),
            contentDescription = null,
            tint = if (selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size((size * 0.56f).dp),
        )
    }
}

@Composable
private fun SourceChip(source: String) {
    val isSystem = source == "system-image"
    AssistChip(
        onClick = {},
        label = { Text(if (isSystem) "系统" else "已安装") },
        colors = if (isSystem) {
            AssistChipDefaults.assistChipColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                labelColor = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        } else AssistChipDefaults.assistChipColors(),
    )
}

@Composable
private fun Message(title: String, detail: String?) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            detail?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

/** ListItem 没有 onClick 参数，用 Modifier 补上 */
private fun Modifier.clickableRow(onClick: () -> Unit): Modifier = this.clickable(onClick = onClick)
