package com.nervus.settings

import androidx.compose.ui.unit.dp
import com.nervus.sdk.component.ComponentConfig
import com.nervus.sdk.component.InterfaceRequirement
import com.nervus.sdk.component.NervusApp
import com.nervus.sdk.ui.attachComposeDesktop
import com.nervus.sysui.PowerAction
import com.nervus.sysui.PowerControl
import io.github.nervusos.iface.pkgmanager.v1.ListRequest
import io.github.nervusos.iface.pkgmanager.v1.ListResult
import io.github.nervusos.iface.pkgmanager.v1.PackageInfo
import io.github.nervusos.iface.pkgmanager.v1.SetComponentEnabledRequest
import io.github.nervusos.iface.pkgmanager.v1.UninstallRequest
import java.util.logging.Logger
import kotlin.system.exitProcess

/**
 * 设置应用。
 *
 * 全部能力来自 `nervus.interface.pkg.manager` 的四个方法。**它自己不做任何裁决**
 * ——列出什么、能不能卸载、能不能停用，全由 nervud 决定，本应用只负责把结果
 * 显示出来、把用户的意图转成一次调用。
 *
 * 这不是偷懒：`pkgregistry` 里有一整套准入（保护名单、系统包不可卸载、签名
 * 血统连续性…），在 UI 里复刻一份判断只会产生第二个真相源，两边一旦不一致，
 * 用户看到的可点按钮点下去必然失败。
 */
class Settings(config: ComponentConfig) : NervusApp(config) {

    override val requiredInterfaces: List<InterfaceRequirement> = listOf(
        InterfaceRequirement(
            id = PkgManager.INTERFACE_ID,
            // 非必需：pkgmanagerd 可能还没起来（系统服务启动顺序随机）。
            // 设置界面仍应打开并解释原因，而不是整个组件启动失败
            isRequired = false,
        ),
        InterfaceRequirement(
            id = PowerControl.INTERFACE_ID,
            // 也非必需：这是内建接口，nervud 活着它就在，理论上不会解析失败。
            // 但「电源按钮解析不到」不该让整个设置打不开——用户还有别的事要做
            isRequired = false,
        ),
    )

    private val log = Logger.getLogger(Settings::class.java.name)

    fun listPackages(): List<PackageInfo> =
        ListResult.parseFrom(
            call(
                interfaceId = PkgManager.INTERFACE_ID,
                methodId = PkgManager.LIST,
                payload = ListRequest.getDefaultInstance().toByteArray(),
            )
        ).packagesList.sortedBy { it.packageId }

    /**
     * 卸载一个包。
     *
     * 系统镜像包会被 nervud 以 `ErrSystemPackageImmutable` 拒绝——**不在这里
     * 预先拦截**，让内核给出权威答复，UI 把它原样显示。理由同类注释。
     */
    fun uninstall(packageId: String) {
        log.info("uninstall $packageId")
        call(
            interfaceId = PkgManager.INTERFACE_ID,
            methodId = PkgManager.UNINSTALL,
            payload = UninstallRequest.newBuilder().setPackageId(packageId).build().toByteArray(),
        )
    }

    /**
     * 停用/启用一个组件。
     *
     * 保护名单里的组件（设置自己、权限确认、pkgmanagerd…）会被内核以
     * `ErrComponentProtected` 拒绝。UI 会把这类失败展示出来。
     */
    fun setComponentEnabled(packageId: String, componentId: String, enabled: Boolean) {
        log.info("setEnabled $packageId/$componentId -> $enabled")
        call(
            interfaceId = PkgManager.INTERFACE_ID,
            methodId = PkgManager.SET_COMPONENT_ENABLED,
            payload = SetComponentEnabledRequest.newBuilder()
                .setPackageId(packageId)
                .setComponentId(componentId)
                .setEnabled(enabled)
                .build()
                .toByteArray(),
        )
    }

    /**
     * 发起一次有序电源动作（重启 / 关机）。
     *
     * 「有序」是关键：内核经 systemd 走完整的 `shutdown.target`，正在跑的组件
     * 收得到 SIGTERM、有机会落盘、文件系统正常卸载。等价于在终端敲
     * `systemctl reboot`，而**不是** `reboot(2)` 硬重启（那条路是故障恢复用的，
     * 只给 platform-release 签的包，见内核 `perm.authority.reboot`）。
     *
     * 这个调用**正常情况下不会正常返回**：systemd 收到后立刻开始停机，控制面
     * 连接随之断开。因此调用方看到异常不代表没重启——UI 侧一律按「已发出」处理，
     * 不要因为抛异常就提示失败，那会让用户以为没生效而反复点。
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

/**
 * `nervus.interface.pkg.manager` 的方法 ID。
 *
 * 取值来自 `nervus-ipc` 的 `PackageManagerMethod` 枚举，**以 proto 为准**。
 * 写错的后果是一个准确的 NOT_FOUND，不会静默调到别的方法上。
 */
object PkgManager {
    const val INTERFACE_ID = "nervus.interface.pkg.manager"
    const val INSTALL = 1
    const val UNINSTALL = 2
    const val LIST = 3
    const val SET_COMPONENT_ENABLED = 4
}


fun main() {
    val log = Logger.getLogger("settings")
    val settings = Settings(ComponentConfig(componentId = "main"))

    try {
        settings.start()
    } catch (e: Exception) {
        log.severe("settings: cannot reach control plane: ${e.message}")
        exitProcess(1)
    }

    Runtime.getRuntime().addShutdownHook(Thread { settings.close() })

    settings.attachComposeDesktop(
        title = "设置",
        width = 1100.dp,
        height = 760.dp,
        onDisconnect = {
            log.severe("settings: control plane lost, exiting")
            exitProcess(1)
        },
    ) {
        SettingsScreen(settings)
    }
}
