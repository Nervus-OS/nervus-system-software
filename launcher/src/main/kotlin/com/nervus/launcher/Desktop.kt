package com.nervus.launcher

import com.nervus.sdk.component.ComponentConfig
import com.nervus.sdk.component.InterfaceRequirement
import com.nervus.sdk.component.NervusApp
import androidx.compose.ui.unit.dp
import com.nervus.sdk.ui.attachComposeDesktop
import com.nervus.sysui.AppIdentity
import com.nervus.sysui.PowerAction
import com.nervus.sysui.PowerControl
import io.github.nervusos.iface.pkgmanager.v1.PackageInfo
import java.util.logging.Logger
import kotlin.system.exitProcess

/**
 * 桌面（启动器本体）。
 *
 * - 消费 `nervus.interface.pkg.manager` 列出已装应用；
 * - 用 `LaunchComponent`（Envelope body 80）点开它们。
 *
 * 它自己**不导出任何接口**：内核加了 LaunchComponent 之后，"能被启动"不再
 * 需要"有一个可被 Resolve 的接口"，会话服务直接请求内核拉起
 * `nervus.launcher/desktop` 即可。
 */
class Desktop(config: ComponentConfig) : NervusApp(config) {

    override val requiredInterfaces: List<InterfaceRequirement> = listOf(
        InterfaceRequirement(
            id = PkgManagerMethods.INTERFACE_ID,
            // 非必需：pkgmanagerd 可能还没起来（系统服务启动顺序是随机的，
            // Go map 迭代顺序被运行时刻意随机化）。列不出应用时桌面仍应显示，
            // 给出一句能看懂的提示，而不是整个组件启动失败连窗口都没有
            isRequired = false,
        ),
        InterfaceRequirement(
            id = PowerControl.INTERFACE_ID,
            // 内建接口，nervud 活着它就在。仍设非必需：电源按钮不可用
            // 不该让整个桌面起不来，用户至少还得看得见已装应用
            isRequired = false,
        ),
    )

    private val log = Logger.getLogger(Desktop::class.java.name)

    /** 拉取可显示在桌面上的应用。后台线程调用（见 rememberPolled） */
    fun listApps(): List<PackageInfo> =
        try {
            decodeList(
                call(
                    interfaceId = PkgManagerMethods.INTERFACE_ID,
                    methodId = PkgManagerMethods.LIST,
                    payload = listRequestPayload(),
                )
            ).filterNot { AppIdentity.isHeadless(it.packageId) }
                .sortedBy { AppIdentity.displayName(it.packageId) }
        } catch (e: IllegalStateException) {
            // pkgmanager 没解析到（服务没起来）。这不是错误状态，是"还没准备好"
            throw PkgManagerUnavailable(e.message ?: "package manager not resolved")
        }

    /**
     * 启动一个应用。
     *
     * 走 Envelope 的 LaunchComponent(80) —— 内核为此新增的专用消息。在它之前
     * 只能靠"对目标导出的某个接口发 ResolveEndpoint"来间接触发启动，那要求
     * 每个应用都导出一个自己并不需要的占位接口，审计里也分不清"解析接口"
     * 和"启动应用"。
     *
     * 会阻塞（内核要等目标进程起来），必须在后台线程调用。
     */
    fun launch(packageId: String, componentId: String) {
        log.info("launching $packageId/$componentId")
        val alreadyRunning = launchComponent(packageId, componentId)
        if (alreadyRunning) {
            log.info("$packageId/$componentId was already running")
        }
    }

    /**
     * 发起一次有序电源动作。
     *
     * 桌面上也放这个入口，是因为「关机」不该要求用户先想起来它在设置里 ——
     * 那是一台机器人最基本的操作。语义与设置里那个完全相同（同一个内建接口、
     * 同一份确认文案，见 [PowerControl]）。
     *
     * **正常情况下不会正常返回**：systemd 收到后立刻开始停机，控制面连接随之
     * 断开。调用方看到异常不代表没关机，别把它显示成失败。
     */
    fun power(action: PowerAction) {
        log.info("power action requested: ${action.label}")
        call(
            interfaceId = PowerControl.INTERFACE_ID,
            methodId = action.methodId,
            payload = ByteArray(0),
        )
    }
}

/** pkgmanager 尚不可用。与"调用失败"分开，UI 对两者的措辞不同 */
class PkgManagerUnavailable(message: String) : Exception(message)

fun main() {
    val log = Logger.getLogger("desktop")
    val desktop = Desktop(ComponentConfig(componentId = "desktop"))

    try {
        desktop.start()
    } catch (e: Exception) {
        log.severe("desktop: cannot reach control plane: ${e.message}")
        exitProcess(1)
    }

    Runtime.getRuntime().addShutdownHook(Thread { desktop.close() })

    desktop.attachComposeDesktop(
        title = "Nervus",
        width = 1280.dp,
        height = 800.dp,
        onDisconnect = {
            // 控制面断了就退出，让 supervisor 重启。留一个连不上内核的空窗口
            // 比没有窗口更糟：它看起来正常，实际什么都点不动
            log.severe("desktop: control plane lost, exiting for restart")
            exitProcess(1)
        },
    ) {
        DesktopScreen(desktop)
    }
}
