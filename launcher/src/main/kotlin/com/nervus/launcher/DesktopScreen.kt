package com.nervus.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nervus.sdk.ui.NervusBackHandler
import com.nervus.sysui.AppIdentity
import com.nervus.sysui.NervusIcons
import com.nervus.sysui.PowerAction
import com.nervus.sysui.PowerConfirmDialog
import com.nervus.sysui.iconForPackage
import com.nervus.sysui.rememberPolled
import io.github.nervusos.iface.pkgmanager.v1.PackageInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@Composable
fun DesktopScreen(desktop: Desktop) {
    // 每 3 秒重拉一次应用列表。不能用订阅——nervud 尚未实现 Subscribe，
    // 发过去会被直接关连接（见 rememberPolled 的说明）
    val apps by rememberPolled<List<PackageInfo>>(initial = emptyList(), intervalMs = 3000) {
        desktop.listApps()
    }

    var launching by remember { mutableStateOf<String?>(null) }
    var launchError by remember { mutableStateOf<String?>(null) }
    // 电源确认框。发出后不复位——机器正在关，界面状态已经无关紧要
    var pendingPower by remember { mutableStateOf<PowerAction?>(null) }
    val scope = rememberCoroutineScope()

    NervusBackHandler(enabled = pendingPower != null || launchError != null) {
        when {
            pendingPower != null -> pendingPower = null
            launchError != null -> launchError = null
        }
    }

    Scaffold(
        topBar = {
            StatusBar(
                busy = apps.loading || launching != null,
                onPower = { pendingPower = it },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                apps.value.isEmpty() && apps.error != null ->
                    CenteredMessage(
                        title = "暂时列不出应用",
                        detail = apps.error!!,
                    )

                apps.value.isEmpty() && apps.loading ->
                    CenteredMessage(title = "正在读取已装应用…", detail = null)

                apps.value.isEmpty() ->
                    CenteredMessage(
                        title = "还没有可启动的应用",
                        detail = "用 nervusctl install 装一个，或检查 pkgmanagerd 是否在运行",
                    )

                else -> AppGrid(
                    apps = apps.value,
                    launchingPackageId = launching,
                    onLaunch = { pkg ->
                        launching = pkg
                        launchError = null
                        scope.launch {
                            try {
                                // Resolve 会阻塞到目标组件启动并注册完成，
                                // 必须离开 UI 线程
                                withContext(Dispatchers.IO) {
                                    desktop.launch(pkg, AppIdentity.launchComponentOf(pkg))
                                }
                            } catch (e: Exception) {
                                launchError = "${AppIdentity.displayName(pkg)}：${e.message}"
                            } finally {
                                launching = null
                            }
                        }
                    },
                )
            }

            launchError?.let { msg ->
                ErrorBanner(msg, Modifier.align(Alignment.BottomCenter))
            }
        }
    }

    pendingPower?.let { action ->
        PowerConfirmDialog(
            action = action,
            onDismiss = { pendingPower = null },
            onConfirm = {
                pendingPower = null
                scope.launch {
                    // 阻塞到连接断开，必须离开 UI 线程
                    withContext(Dispatchers.IO) {
                        // 失败大概率就是「连接已断开」，也就是成功的样子。
                        // 桌面上没有合适的位置解释这件事，静默即可——
                        // 真没关成的话，用户看到桌面还在，会再点一次
                        runCatching { desktop.power(action) }
                    }
                }
            },
        )
    }
}

@Composable
private fun StatusBar(busy: Boolean, onPower: (PowerAction) -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(
                            imageVector = NervusIcons.Apps,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(8.dp).size(22.dp),
                        )
                    }
                    Text(
                        "Nervus",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Clock()
                    IconButton(onClick = { onPower(PowerAction.Reboot) }) {
                        Icon(
                            imageVector = NervusIcons.Restart,
                            contentDescription = PowerAction.Reboot.label,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { onPower(PowerAction.PowerOff) }) {
                        Icon(
                            imageVector = NervusIcons.Power,
                            contentDescription = PowerAction.PowerOff.label,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            // 进度条只占 2dp 且常驻布局位置：用可见性而不是插入/移除组件来表达忙碌，
            // 否则每次刷新整个网格会上下跳 2 像素
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = if (busy) MaterialTheme.colorScheme.primary else Color.Transparent,
                trackColor = Color.Transparent,
            )
        }
    }
}

@Composable
private fun Clock() {
    // 每秒走一次。用 rememberPolled 而不是自己写 LaunchedEffect，
    // 顺带得到"取值失败不清空"的语义（虽然读时钟不会失败）
    val now by rememberPolled(initial = LocalTime.now(), intervalMs = 1000) { LocalTime.now() }
    Text(
        now.value.format(TIME_FORMAT),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

@Composable
private fun AppGrid(
    apps: List<PackageInfo>,
    launchingPackageId: String?,
    onLaunch: (String) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 150.dp),
        contentPadding = PaddingValues(28.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(apps, key = { it.packageId }) { info ->
            AppTile(
                info = info,
                launching = info.packageId == launchingPackageId,
                onClick = { onLaunch(info.packageId) },
            )
        }
    }
}

@Composable
private fun AppTile(info: PackageInfo, launching: Boolean, onClick: () -> Unit) {
    val name = AppIdentity.displayName(info.packageId)
    Card(
        onClick = onClick,
        enabled = !launching,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 1.dp,
            hoveredElevation = 4.dp,
            pressedElevation = 2.dp,
        ),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.height(160.dp),
    ) {
        Column(
            Modifier.fillMaxSize().padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            AppIcon(packageId = info.packageId, dimmed = launching)
            Text(
                name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp),
            )
            // 系统包与第三方包在界面上要能区分：装错来源是排查问题时最先要确认的事
            if (info.source == SYSTEM_IMAGE_SOURCE) {
                Text(
                    "系统",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private const val SYSTEM_IMAGE_SOURCE = "system-image"

/**
 * 应用图标。
 *
 * 内置应用使用稳定的系统图标，其他应用显示通用应用图标。`PackageInfo` 目前
 * 没有 icon 字段，而 manifest 的 icon 又在包内（沙箱下桌面读不到别的包的
 * 目录）；以后 pkgmanager 投影真实图标后，这里可以直接替换映射。
 *
 * 颜色由 package_id 稳定派生：同一个应用每次开机颜色一致，用户能靠颜色认它。
 */
@Composable
private fun AppIcon(packageId: String, dimmed: Boolean) {
    val palette = MaterialTheme.colorScheme
    val colors = listOf(
        palette.primaryContainer,
        palette.secondaryContainer,
        palette.tertiaryContainer,
    )
    val onColors = listOf(
        palette.onPrimaryContainer,
        palette.onSecondaryContainer,
        palette.onTertiaryContainer,
    )
    // hashCode 可能为负，Math.floorMod 保证落在 [0, size)
    val idx = Math.floorMod(packageId.hashCode(), colors.size)

    Box(
        Modifier.size(64.dp).clip(RoundedCornerShape(20.dp)).background(colors[idx]),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = iconForPackage(packageId),
            contentDescription = null,
            tint = if (dimmed) onColors[idx].copy(alpha = 0.4f) else onColors[idx],
            modifier = Modifier.size(34.dp),
        )
    }
}

@Composable
private fun CenteredMessage(title: String, detail: String?) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = NervusIcons.Apps,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(44.dp),
            )
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            detail?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
            }
        }
    }
}

@Composable
private fun ErrorBanner(message: String, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = modifier.fillMaxWidth().padding(16.dp),
        shape = MaterialTheme.shapes.medium,
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(16.dp),
        )
    }
}
