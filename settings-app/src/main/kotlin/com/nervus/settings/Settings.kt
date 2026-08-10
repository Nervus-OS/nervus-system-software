package com.nervus.settings

import androidx.compose.ui.unit.dp
import com.nervus.sdk.component.ComponentConfig
import com.nervus.sdk.component.InterfaceRequirement
import com.nervus.sdk.component.NervusApp
import com.nervus.sdk.ui.NervusWindow
import com.nervus.sdk.ui.attachComposeDesktop
import com.nervus.sysui.PowerAction
import com.nervus.sysui.PowerControl
import io.github.nervusos.iface.permission.v1.OpenManagerRequest
import io.github.nervusos.iface.pkgmanager.v1.*
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
        InterfaceRequirement(
            id = PermissionUiInterface.INTERFACE_ID,
            // 非必需：permissionui 是 on-demand 的界面组件，设置能不能打开与它
            // 在不在跑无关
            isRequired = false,
            // 【必须 false】。permissionui 的 launch_mode 是 on-demand，而
            // Resolve 就是拉起它的动作——在启动时解析等于设置一开机就把权限
            // 管理界面的进程拉起来，用户会看到一个自己没点过的窗口浮出来。
            //
            // 推迟到 openPermissionManager() 里首次调用时才解析：那时用户正是
            // 点了「打开权限管理」，把它拉起来才是他要的
            resolveEagerly = false,
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
     * 打开权限管理界面（`nervus.permissionui`）。
     *
     * # 为什么是拉起另一个应用而不是本应用的一个页面
     *
     * 改 USER_CONSENT 权限的运行期授予状态需要 `perm.permission.admin`，
     * 而那条权限要求 **platform-release** 签名角色。本应用签 platform-systemapp，
     * 够 Platform 信任但拿不到它——这是设计意图不是配置疏忽：「能给任意应用开
     * 摄像头和运动控制的能力」不该和一个功能繁多、迭代频繁的包共享同一条签名链
     * （Android 把 PermissionController 做成独立 APK 是同一个理由）。
     *
     * 所以设置里的入口只负责【跳转】，授予本身发生在 permissionui 里。
     *
     * # 为什么是一次接口调用而不是 LaunchComponent
     *
     * 早先这里走 `LaunchComponent`（envelope 80），因为 permissionui 还不导出
     * 任何接口。那条路能把它叫起来，但**传不了任何参数**——用户在设置里点某个
     * 应用的「管理权限」，跳过去看到的是全部应用的总览，还得自己再找一遍。
     *
     * 现在 permissionui 导出 `permission.ui`，于是这里改成一次普通调用并带上
     * `package_id`，界面直接收窄到那个包。附带的好处是不再需要
     * `perm.system.launch`——那条权限不限制目标组件，持有它就能拉起任意组件，
     * 而本应用只想打开权限界面这一个。能力收窄到刚好够用。
     *
     * 组件由内核在 Resolve 时按 `launch_mode = on-demand` 自动拉起，本应用
     * 不需要知道它有没有在跑；窗口激活也由 permissionui 自己做（它知道自己的
     * 窗口标题）。
     *
     * @param packageId 只看这一个包的权限；空串（缺省）= 打开总览。
     *
     * 会阻塞（内核要等目标进程起来），必须在后台线程调用。
     */
    fun openPermissionManager(packageId: String = "") {
        log.info("openManager packageId='${packageId.ifEmpty { "(all)" }}'")
        call(
            interfaceId = PermissionUiInterface.INTERFACE_ID,
            methodId = PermissionUiInterface.OPEN_MANAGER,
            payload = OpenManagerRequest.newBuilder()
                .setPackageId(packageId)
                .build()
                .toByteArray(),
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

/**
 * `nervus.interface.permission.ui` 的方法 ID。
 *
 * 取值以 proto 的 `PermissionUiMethod` 枚举为准。本应用只用 `OPEN_MANAGER`
 * ——`CONFIRM_INSTALL` 需要 `perm.pkg.install`，而且那是安装程序的事。
 */
object PermissionUiInterface {
    const val INTERFACE_ID = "nervus.interface.permission.ui"
    const val OPEN_MANAGER = 2
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

    // 窗口句柄：让返回键隐藏窗口而不是杀掉进程（见下面 onUnhandledBack）。
    val windowHandle = NervusWindow()

    settings.attachComposeDesktop(
        title = "设置",
        width = 1100.dp,
        height = 760.dp,
        // 返回键隐藏窗口而不是结束进程。
        //
        // 【不能用 X11WindowControl.hideActiveWindow】：那是 xdotool 的最小化，
        // 在这个环境里会让 Compose 触发 onCloseRequest —— 于是按一下返回键整个
        // 进程就退了。用户下次打开要重付一次 JVM + Compose 冷启动，而他以为
        // 自己只是返回上一层。
        //
        // 返回 true 表示这次返回本应用处理了，不再往上冒泡。
        onUnhandledBack = {
            windowHandle.hide()
            true
        },
        windowHandle = windowHandle,
        onDisconnect = {
            log.severe("settings: control plane lost, exiting")
            exitProcess(1)
        },
    ) {
        SettingsScreen(settings)
    }
}
