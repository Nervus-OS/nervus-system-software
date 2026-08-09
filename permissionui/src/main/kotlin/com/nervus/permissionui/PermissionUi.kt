package com.nervus.permissionui

import androidx.compose.ui.unit.dp
import com.nervus.sdk.annotations.Method
import com.nervus.sdk.component.ComponentConfig
import com.nervus.sdk.component.InterfaceRequirement
import com.nervus.sdk.component.NervusApp
import com.nervus.sdk.component.ProvidedInterface
import com.nervus.sdk.ui.attachComposeDesktop
import com.nervus.sysui.X11WindowControl
import io.github.nervusos.iface.permission.v1.GrantList
import io.github.nervusos.iface.permission.v1.GrantState
import io.github.nervusos.iface.permission.v1.ListGrantsRequest
import io.github.nervusos.iface.permission.v1.OpenManagerRequest
import io.github.nervusos.iface.permission.v1.PackageGrants
import io.github.nervusos.iface.permission.v1.SetGrantStateRequest
import io.github.nervusos.iface.permission.v1.SetGrantStateResponse
import io.github.nervusos.ipc.v1.StdInterfaceSchema
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.logging.Logger
import kotlin.system.exitProcess

/**
 * 窗口标题。
 *
 * **必须与 [attachComposeDesktop] 的 title 一致**：`OpenManager` 靠它把窗口
 * 激活到前台（X11 按标题找窗口）。两处写成不同的字符串不会有任何编译或运行
 * 错误，症状是「设置里点了权限管理，界面没浮上来」——而筛选状态其实已经改了，
 * 所以看起来像是随机失效。抽成常量让这件事没法写岔。
 */
const val WINDOW_TITLE = "权限"

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
 * 本应用**导出**的接口：`nervus.interface.permission.ui`。
 *
 * 取值同样以 proto 的 `PermissionUiMethod` 枚举为准。
 */
object PermissionUiInterface {
    const val INTERFACE_ID = "nervus.interface.permission.ui"
    const val MAJOR = 1

    const val CONFIRM_INSTALL = 1
    const val OPEN_MANAGER = 2
}

/**
 * `permission.ui` 的服务端实现。
 *
 * 只实现 `OPEN_MANAGER`。**`CONFIRM_INSTALL` 刻意不实现**：安装期的权限展示与
 * 确认归 `nervus.packageinstaller`，那是另一个包——「能授予权限」与「能装任意
 * 软件」是两个高权限能力，合在一个进程里就等于造出一个能同时做这两件事的目标。
 * 未实现的方法由内核回一个准确的 NOT_FOUND，不会静默走错。
 */
class PermissionUiEndpoint(private val app: PermissionUi) {

    /**
     * 打开权限管理界面，可选地只看某一个包。
     *
     * 做两件事：把「只看谁」记下来让界面收窄，再把窗口激活到前台。
     *
     * **两件都必须做**。只记状态不激活窗口，调用方（设置）看起来什么都没发生
     * ——Nervus 是单前台窗口环境，本应用可能被压在设置后面，甚至刚被内核
     * on-demand 拉起、窗口还没浮上来。
     *
     * 返回空 ByteArray 而不是 Unit：本方法的 `response_type` 是空串，wire 上
     * 就该是一个不带载荷的成功。**返回 Unit 会让 SDK 把它 toString 成
     * `"kotlin.Unit"` 当作载荷发出去**——那是个能通过编译、也不会立刻报错，
     * 但在 wire 上明显错误的载荷。
     */
    @Method(id = PermissionUiInterface.OPEN_MANAGER)
    fun openManager(req: OpenManagerRequest): ByteArray {
        app.onOpenManager(req.packageId)
        return ByteArray(0)
    }
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

    /**
     * 本应用导出的接口。
     *
     * schemaHash 【必须】取自 [StdInterfaceSchema]：内核会把 RegisterEndpoint
     * 带来的这份与 Catalog 里那份逐字节比对，不符或留空一律拒
     * （nervud internal/endpoint/register.go）。JVM 侧算不出它——hash 是
     * sha256(确定性编码的 FileDescriptorSet)，而 protobuf-java 不保证与 Go 的
     * Deterministic 编码逐字节相同。所以由 Go 算一次落盘，两侧读同一份生成物。
     *
     * 这一条也必须与 manifest 的 `exports` 以及 provider.binpb 里声明的接口
     * 完全对应：catalog 的 addArtifacts 是双向闭合的，三者少了任何一边都会让
     * 整个 Catalog 构建失败（不只是本包被隔离）。
     */
    override val providedInterfaces: List<ProvidedInterface> = listOf(
        ProvidedInterface(
            id = PermissionUiInterface.INTERFACE_ID,
            major = PermissionUiInterface.MAJOR,
            instance = PermissionUiEndpoint(this),
            schemaHash = StdInterfaceSchema.hashOf(
                PermissionUiInterface.INTERFACE_ID,
                PermissionUiInterface.MAJOR,
            ),
        ),
    )

    private val log = Logger.getLogger(PermissionUi::class.java.name)

    /**
     * 「只看这一个包」的筛选条件；空串表示总览。
     *
     * 用 [MutableStateFlow] 而不是 Compose 的 mutableStateOf：写它的是 IPC 的
     * dispatch 线程，读它的是 Compose 的 UI 线程。Compose 的状态要求在
     * 主线程写，从别的线程写会得到一个与线程时序有关的偶发错误。
     */
    private val _managerFilter = MutableStateFlow("")
    val managerFilter: StateFlow<String> get() = _managerFilter

    /**
     * `OpenManager` 到达时的处理：收窄筛选并把窗口拉到前台。
     *
     * 由 [PermissionUiEndpoint] 在 dispatch 线程上调用，**不做任何裁决**——
     * 调用方有没有资格打开本界面由内核按 `required_permission` 判定，
     * 这里只负责照做。
     */
    fun onOpenManager(packageId: String) {
        log.info("openManager packageId='${packageId.ifEmpty { "(all)" }}'")
        _managerFilter.value = packageId
        // 激活失败只记日志不抛：筛选状态已经改好了，用户手动切到本窗口仍能
        // 看到正确的内容。为一次窗口激活失败让整个调用失败是不成比例的
        if (!X11WindowControl.activateWindow(WINDOW_TITLE)) {
            log.warning("openManager: cannot activate window '$WINDOW_TITLE'")
        }
    }

    /**
     * 回到总览。由界面上那个「查看全部应用的权限」调用。
     *
     * 必须有这条出口：`OpenManager` 带包 ID 进来之后界面被收窄，而本应用没有
     * 别的导航——没有它，从设置跳进来的用户就被锁在单包视图里，只能关掉窗口
     * 再从桌面重新打开。
     */
    fun clearManagerFilter() {
        _managerFilter.value = ""
    }

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
        title = WINDOW_TITLE,
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
