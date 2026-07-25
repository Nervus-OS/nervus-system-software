package com.nervus.sysui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * `nervus.interface.power` —— 整机有序重启 / 关机。
 *
 * ## 这是一个内建接口
 *
 * 由 nervud 自己实现，真正执行的是 Authority Gate。关机只有内核有权限，
 * 不可能由外部 Provider 提供，所以走内建 endpoint：调用方用完全标准的
 * Resolve + Request 访问，不需要知道对面是内核还是 Provider。
 *
 * ## 常量没有 proto 兜底
 *
 * pkgmanager 那组方法 ID 能对着生成的 `PackageManagerMethod` 枚举核，本接口
 * 还没有对应的 `.proto`。唯一定义在内核侧的 `nervud/internal/power/builtin.go`，
 * 本对象是它的手工镜像 —— 放在 ui-common 而不是各应用里，就是为了让这份镜像
 * 只有一处。改内核必须同步改这里，否则症状是「点了没反应」：调用被路由到一个
 * 不存在的方法，回 NOT_FOUND。
 *
 * v2 补上 `power_control.proto` 之后，两侧都应改成从生成的枚举取值。
 *
 * ## 调用它需要 perm.authority.power
 *
 * MinTrust=Platform，所以只有平台角色签名的包能用。用它的包必须在 manifest 的
 * `permissions` 里声明，否则 Resolve 直接 PERMISSION_DENIED。
 */
object PowerControl {
    const val INTERFACE_ID = "nervus.interface.power"
    const val REBOOT = 1
    const val POWER_OFF = 2
}

/** 电源动作。label 同时用作按钮文案与确认框标题。 */
enum class PowerAction(val label: String, val methodId: Int, val confirmText: String) {
    Reboot(
        label = "重启",
        methodId = PowerControl.REBOOT,
        confirmText = "机器人将停止所有正在运行的组件并重新启动。进行中的任务不会被保存。",
    ),
    PowerOff(
        label = "关机",
        methodId = PowerControl.POWER_OFF,
        confirmText = "机器人将停止所有正在运行的组件并断电。之后需要手动按电源键才能再次开机。",
    ),
}

/**
 * 电源动作的二次确认框。
 *
 * 【为什么一定要有】：这是系统里唯一不可撤销、且代价是整机不可用的操作。
 * 误触一次就得走到设备旁边按电源键。M3 的 [AlertDialog] 是这类破坏性确认的
 * 标准形态。
 *
 * 桌面和设置各自有电源入口，但确认语义必须一致 —— 所以放在这里共用，
 * 而不是两边各写一份措辞不同的对话框。
 */
@Composable
fun PowerConfirmDialog(
    action: PowerAction,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("确认${action.label}？") },
        text = { Text(action.confirmText) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(action.label) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
