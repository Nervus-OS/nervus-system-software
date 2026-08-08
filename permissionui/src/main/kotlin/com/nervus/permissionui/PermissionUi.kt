package com.nervus.permissionui

import androidx.compose.ui.unit.dp
import com.nervus.sdk.component.ComponentConfig
import com.nervus.sdk.component.InterfaceRequirement
import com.nervus.sdk.component.NervusApp
import com.nervus.sdk.ui.attachComposeDesktop
import com.nervus.sysui.X11WindowControl
import io.github.nervusos.iface.permission.v1.GrantList
import io.github.nervusos.iface.permission.v1.GrantState
import io.github.nervusos.iface.permission.v1.ListGrantsRequest
import io.github.nervusos.iface.permission.v1.PackageGrants
import io.github.nervusos.iface.permission.v1.SetGrantStateRequest
import io.github.nervusos.iface.permission.v1.SetGrantStateResponse
import java.util.logging.Logger
import kotlin.system.exitProcess

/**
 * `nervus.interface.permission.admin` 的方法 ID。
 *
 * 取值来自 `nervus-ipc/proto/nervus/interface/permission/v1/permission.proto`
 * 的 `PermissionAdminMethod` 枚举，**以 proto 为准**——那里的
 * `method_meta.method_id` 与枚举值编号必须一致（registry 抽取时会校验，
 * 不一致直接 fail closed）。
 *
 * 手写常量而不是从生成的枚举取，与 launcher 的 PkgManagerMethods 同一理由：
 * 生成的枚举读起来绕，而两边一旦不一致，调用会得到一个准确的 NOT_FOUND，
 * 不会静默走错方法。
 */
object PermissionAdmin {
    const val INTERFACE_ID = "nervus.interface.permission.admin"

    const val LIST_GRANTS = 1
    const val SET_GRANT_STATE = 2
}

/**
 * 权限管理应用。
 *
 * 它是系统里【唯一】能改 USER_CONSENT 权限运行期授予状态的界面。
 *
 * # 为什么它是独立的一个包
 *
 * `perm.permission.admin` 要求 platform-release 签名，而设置、桌面、文件管理器
 * 都签 platform-systemapp，拿不到。这不是配置疏忽：「能给任意应用开摄像头和
 * 运动控制的能力」不该和一个功能繁多、迭代频繁的包共享同一条签名链。
 * Android 把 PermissionController 做成独立 APK 是同一个理由。
 *
 * # 它自己不做任何裁决
 *
 * 列出哪些权限、能不能改、改完是什么状态，全部由 nervud 决定。本应用只把结果
 * 显示出来，把用户的意图转成一次调用。理由同 settings-app：在 UI 里复刻一份
 * 判断只会产生第二个真相源，两边一旦不一致，用户看到的可点开关点下去必然失败。
 *
 * 特别地，**内核只返回 USER_CONSENT 那一档**——其它授予模式没有运行期状态可言，
 * 所以这里永远不会出现拨不动的开关，不需要在 UI 侧过滤。
 */
class PermissionUi(config: ComponentConfig) : NervusApp(config) {

    override val requiredInterfaces: List<InterfaceRequirement> = listOf(
        InterfaceRequirement(
            id = PermissionAdmin.INTERFACE_ID,
            // 必需：这是内建接口，nervud 活着它就在。解析不到说明内核版本对不上
            // （本包声明 min_nervus_api 2），此时打开一个空白的权限页比启动
            // 失败更糟——用户会以为「没有任何应用申请过敏感权限」
            isRequired = true,
        ),
    )

    private val log = Logger.getLogger(PermissionUi::class.java.name)

    /** 列出各包的可授予权限与当前状态。内核已按包与权限双重字典序排好。 */
    fun listGrants(): List<PackageGrants> =
        GrantList.parseFrom(
            call(
                interfaceId = PermissionAdmin.INTERFACE_ID,
                methodId = PermissionAdmin.LIST_GRANTS,
                payload = ListGrantsRequest.getDefaultInstance().toByteArray(),
            )
        ).packagesList

    /**
     * 改一条权限的授予状态，返回**生效后的**状态。
     *
     * 用返回值而不是把请求里那个值直接写进 UI：授予是否真的落地由内核决定
     * （包可能刚被卸载、权限可能刚被降权），界面该显示的是现在的事实。
     *
     * 撤销用 [GrantState.GRANT_STATE_DENIED] 而不是 NOT_REQUESTED——后者是
     * 「回到从没问过」，那不是用户能做的决定，内核会拒。
     */
    fun setGrantState(packageId: String, permissionId: String, granted: Boolean): GrantState {
        val target =
            if (granted) GrantState.GRANT_STATE_GRANTED else GrantState.GRANT_STATE_DENIED
        log.info("setGrantState $packageId/$permissionId -> $target")
        return SetGrantStateResponse.parseFrom(
            call(
                interfaceId = PermissionAdmin.INTERFACE_ID,
                methodId = PermissionAdmin.SET_GRANT_STATE,
                payload = SetGrantStateRequest.newBuilder()
                    .setPackageId(packageId)
                    .setPermissionId(permissionId)
                    .setState(target)
                    .build()
                    .toByteArray(),
            )
        ).state
    }
}

fun main() {
    val log = Logger.getLogger("permissionui")
    // componentId 必须是 "main"：与 nspkg 里 components.app("main") 一致，
    // 内核按 (package_id, component_id) 认身份
    val app = PermissionUi(ComponentConfig(componentId = "main"))

    try {
        app.start()
    } catch (e: Exception) {
        log.severe("permissionui: cannot reach control plane: ${e.message}")
        exitProcess(1)
    }

    Runtime.getRuntime().addShutdownHook(Thread { app.close() })

    app.attachComposeDesktop(
        title = "权限",
        width = 1100.dp,
        height = 760.dp,
        onUnhandledBack = X11WindowControl::hideActiveWindow,
        onDisconnect = {
            log.severe("permissionui: control plane lost, exiting")
            exitProcess(1)
        },
    ) {
        PermissionUiScreen(app)
    }
}
