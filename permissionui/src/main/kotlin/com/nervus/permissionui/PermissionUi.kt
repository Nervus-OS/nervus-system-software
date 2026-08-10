package com.nervus.permissionui

import androidx.compose.ui.unit.dp
import com.nervus.sdk.annotations.Method
import com.nervus.sdk.component.ComponentConfig
import com.nervus.sdk.component.InterfaceRequirement
import com.nervus.sdk.component.NervusApp
import com.nervus.sdk.component.ProvidedInterface
import com.nervus.sdk.operation.OperationPending
import com.nervus.sdk.operation.currentCaller
import com.nervus.sdk.operation.currentOperationId
import com.nervus.sdk.ui.NervusWindow
import com.nervus.sdk.ui.attachComposeDesktop
import com.nervus.sysui.X11WindowControl
import io.github.nervusos.iface.permission.v1.GrantList
import io.github.nervusos.iface.permission.v1.GrantState
import io.github.nervusos.iface.permission.v1.ListGrantsRequest
import io.github.nervusos.iface.permission.v1.OpenManagerRequest
import io.github.nervusos.iface.permission.v1.PackageGrants
import io.github.nervusos.iface.permission.v1.PermissionGrant
import io.github.nervusos.iface.permission.v1.PermissionRequestDecision
import io.github.nervusos.iface.permission.v1.PermissionUiErrorDetail
import io.github.nervusos.iface.permission.v1.PermissionUiReason
import io.github.nervusos.iface.permission.v1.RequestPermissionOutcome
import io.github.nervusos.iface.permission.v1.RequestPermissionRequest
import io.github.nervusos.iface.permission.v1.RequestPermissionResult
import io.github.nervusos.iface.permission.v1.SetGrantStateRequest
import io.github.nervusos.iface.permission.v1.SetGrantStateResponse
import io.github.nervusos.ipc.v1.StatusCode
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
 * 等用户回答一次权限申请的上限（分钟）。
 *
 * 有上限而不是无界等待：用户可能永远不回答（走开了、窗口被别的东西盖住）。
 * 无界等待会让那个 operation 与工作线程一起永久占着，而串行化闸门也再不释放
 * ——下一次申请会一直 BUSY，症状是「权限申请从此再也不弹窗了」。
 *
 * 超时按【拒绝】处理，与显示失败同一方向：问不到用户就当没同意。
 *
 * 5 分钟比 SDK 侧 requestPermission 的默认超时（10 分钟）短，因此正常情况下
 * 是这一侧先回一个明确的「拒绝」，而不是让调用方撞上自己的超时——后者拿不到
 * 逐条结果，只知道「失败了」。
 */
private const val USER_ANSWER_TIMEOUT_MINUTES = 5L

/** 简写：proto 的决定枚举名字很长，模式匹配里全写一遍反而更难读。 */
private typealias Decision = PermissionRequestDecision

/**
 * 一次正在等用户回答的权限申请。
 *
 * [answer] 是界面回填答案的通道：工作线程在它上面有界等待，UI 线程在用户点了
 * 按钮之后 complete 它。用 CompletableFuture 而不是把结果写进另一个 StateFlow
 * ——那样工作线程就得轮询，而这里要等的是一个一次性事件。
 */
data class PendingRequest(
    /** 申请方包 ID。**内核给出的可信身份**，不是请求里的自述。 */
    val callerPackageId: String,
    /** 申请方的显示名（manifest 的 label）；可能为空。 */
    val callerLabel: String,
    /**
     * 应用自称的申请理由。
     *
     * 【不受信文本】。界面必须明确标注它出自应用，且视觉上不能与系统文案混同
     * ——否则一句「系统要求您授予摄像头权限」配上伪造的措辞就能骗过用户。
     */
    val rationale: String,
    /** 要问的权限，含内核给出的文案与风险等级。 */
    val items: List<PermissionGrant>,
    val answer: java.util.concurrent.CompletableFuture<Set<String>>,
)

/** 构造一条逐权限结果。 */
private fun outcome(
    permissionId: String,
    granted: Boolean,
    decision: PermissionRequestDecision,
): RequestPermissionOutcome =
    RequestPermissionOutcome.newBuilder()
        .setPermissionId(permissionId)
        .setGranted(granted)
        .setDecision(decision)
        .build()

/** 构造本接口的 typed 错误细因。 */
private fun uiErrorDetail(reason: PermissionUiReason): ByteArray =
    PermissionUiErrorDetail.newBuilder().setReason(reason).build().toByteArray()

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
    const val REQUEST_PERMISSION = 3
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

    /**
     * 向用户申请一批权限（申请方自己的）。
     *
     * # 申请方身份取自内核，不看请求
     *
     * [RequestPermissionRequest] 里**没有** package_id 字段，身份由
     * [currentCaller] 给出——那是 nervud 按连接凭据认出来的事实。有那个字段的话，
     * 任何应用都能替别人申请权限，而用户在弹窗上看到的包名会是被冒充的那个。
     *
     * # 为什么立刻返回 [OperationPending]
     *
     * 本方法要等用户读完再点，而 **SDK 的 dispatch 跑在读取循环上**
     * （`NervusClient.handleDispatch` 是同步调用）。在这里阻塞等用户，就再也
     * 收不到任何帧——包括 `acceptOperation` / `completeOperation` 自己的响应。
     * 那是一个必然的死锁，不是偶发竞态。
     *
     * 所以：在 handler 体内**同步取出** operation id 与 caller，交给工作线程，
     * 立刻返回 Pending。两个值都必须在这里取——它们放在 ThreadLocal 里，
     * 工作线程上读不到（见 SDK 的 CurrentOperation / CurrentCaller）。
     */
    @Method(id = PermissionUiInterface.REQUEST_PERMISSION)
    fun requestPermission(req: RequestPermissionRequest): OperationPending {
        app.onRequestPermission(
            operationId = currentOperationId(),
            callerPackageId = currentCaller().packageId,
            permissionIds = req.permissionIdsList.toList(),
            rationale = req.rationale,
        )
        return OperationPending.INSTANCE
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
        // 用户主动要看这个界面（从设置跳进来），申请结束后不能把它收走
        windowWanted = true
        _managerFilter.value = packageId
        // 【必须 show】：窗口可能正被隐藏着（上一次权限申请答完之后自己退下去了）。
        // 那种情况下进程活着、endpoint 有效，所以内核【不会】重新拉起本组件，
        // 也就不会有新窗口自动出现——不 show 的话调用方看起来什么都没发生。
        window.show()
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

    /**
     * 当前正在问用户的那一次权限申请；null 表示没有。
     *
     * 与 [managerFilter] 同理由用 [MutableStateFlow]：写它的是工作线程，读它的是
     * Compose 的 UI 线程。
     */
    private val _pendingRequest = MutableStateFlow<PendingRequest?>(null)
    val pendingRequest: StateFlow<PendingRequest?> get() = _pendingRequest

    /**
     * 用户是不是**主动**要看这个界面。
     *
     * 决定一次权限申请结束后要不要把窗口收走。本应用是 on-demand 的：一次纯粹的
     * 权限申请会把它拉起来，而它的窗口是全屏的（[attachComposeDesktop] 用
     * `Maximized`）。申请答完还留在前台，用户得自己按 Esc 退出去——而他压根没打算
     * 打开权限管理，只是在别的应用里点了个按钮。
     *
     * 反过来，用户**确实**在用这个界面的时候把它收走更糟：那是把他正看着的东西
     * 抽走。所以只收「为了这次申请才浮上来的」窗口。
     *
     * 置 true 的两种情形：从设置跳进来（`OpenManager`），或用户在管理界面动过
     * 任何一个开关。两者都说明他在用这个界面。
     *
     * @Volatile：写它的是 dispatch 线程与 UI 线程，读它的是权限申请的工作线程。
     */
    @Volatile
    private var windowWanted = false

    /**
     * 本应用的窗口句柄。
     *
     * 由 [main] 传给 `attachComposeDesktop` 接到真实窗口上。用它区分「隐藏自己」
     * 与「关掉自己」——**隐藏绝不能结束进程**，否则本组件注册的 endpoint 随之
     * 失效，而调用方还缓存着它。
     */
    val window = NervusWindow()

    /**
     * 用户在管理界面动了开关——从此这个窗口是他要的。
     *
     * 由界面在改授予状态时调用。**不能只在 [onOpenManager] 里置位**：用户也可能
     * 从桌面直接打开本应用，那条路上没有任何 IPC 调用经过我们。
     */
    fun markWindowWanted() {
        windowWanted = true
    }

    /**
     * 串行化用的闸门。**一次只问一个申请**。
     *
     * 两个确认框同时开着，用户分不清自己在给谁授权——而这里的后果是把权限授给了
     * 错误的应用。第二个并发申请以 BUSY 失败，让调用方稍后重试。
     */
    private val requestGate = java.util.concurrent.Semaphore(1)

    /**
     * 跑弹窗流程的工作线程。
     *
     * **必须离开 dispatch 线程**：SDK 的 dispatch 跑在读取循环上，在那里阻塞等
     * 用户会连自己 `completeOperation` 的响应都收不到——一个必然的死锁。
     *
     * 单线程而不是线程池：并发申请由 [requestGate] 挡掉，多余的线程只会让
     * 「同时弹两个窗」变成一件可能的事。守护线程 —— 进程退出时不该被一个
     * 没人回答的弹窗吊住。
     */
    private val requestWorker = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "permission-request").apply { isDaemon = true }
    }

    /**
     * `RequestPermission` 到达时的处理。**立刻返回**，真正的工作在工作线程上。
     *
     * @param operationId 由 handler 在 dispatch 线程上取出后传进来——它在
     * ThreadLocal 里，工作线程读不到。
     * @param callerPackageId 内核认出来的申请方身份，**不是**请求里的自述。
     */
    fun onRequestPermission(
        operationId: Long,
        callerPackageId: String,
        permissionIds: List<String>,
        rationale: String,
    ) {
        requestWorker.execute {
            runRequest(operationId, callerPackageId, permissionIds, rationale)
        }
    }

    /**
     * 在工作线程上走完一次权限申请。
     *
     * # 无论走哪条路都必须终结这个 operation
     *
     * 调用方停在 `callOperation` 上等终态。这里漏掉一次 complete，它就一直等到
     * 自己的超时（SDK 默认 10 分钟）——一个看起来像「系统卡住了」的故障，而真实
     * 原因只是本函数提前返回了。所以整段包在 try/catch 里，异常路径也回一个终态。
     */
    private fun runRequest(
        operationId: Long,
        callerPackageId: String,
        permissionIds: List<String>,
        rationale: String,
    ) {
        try {
            // 空身份必须拒。当成「本机可信」往下走，等于让一次没认出调用方的连接
            // 拿到一个能改权限的弹窗，而用户在窗上看到的包名会是空的。
            //
            // 【码是 FAILED_PRECONDITION 而不是 UNAUTHENTICATED】：后者不在
            // nervud 的 providerFailCode 白名单里（授权裁决只能由内核产生，
            // Provider 冒充即违规），回它会被 fail-closed 归一化成 INTERNAL
            // 并审计为契约违规——调用方拿到的原因反而更模糊。
            if (callerPackageId.isEmpty()) {
                log.warning("requestPermission: rejected, caller identity unknown")
                failOperation(operationId, StatusCode.STATUS_CODE_FAILED_PRECONDITION)
                return
            }
            if (permissionIds.isEmpty()) {
                failOperation(operationId, StatusCode.STATUS_CODE_INVALID_ARGUMENT)
                return
            }
            // tryAcquire 而不是 acquire：拿不到就以 BUSY 失败，而不是排队。排队的话
            // 调用方会等一段无从预测的时间，而它自己的超时到了照样失败
            if (!requestGate.tryAcquire()) {
                log.info("requestPermission: busy, rejecting request from $callerPackageId")
                failOperation(
                    operationId,
                    StatusCode.STATUS_CODE_FAILED_PRECONDITION,
                    uiErrorDetail(PermissionUiReason.PERMISSION_UI_REASON_BUSY),
                )
                return
            }
            try {
                askUser(operationId, callerPackageId, permissionIds, rationale)
            } finally {
                _pendingRequest.value = null
                requestGate.release()
            }
        } catch (e: Exception) {
            // 兜底：任何没预料到的失败都要终结 operation，否则调用方一直等
            log.severe("requestPermission failed for $callerPackageId: ${e.message}")
            runCatching { failOperation(operationId, StatusCode.STATUS_CODE_INTERNAL) }
        }
    }

    /**
     * 分类、弹窗、落库、回终态。
     *
     * # 权限文案与可申请性都问内核，不在这里判断
     *
     * `ListGrants(package_id = 申请方)` 回来的就是「这个包安装期真正拿到的
     * USER_CONSENT 权限」及其文案与当前状态。因此：
     *
     *   在列表里 + 未授予      → 该问用户
     *   在列表里 + 已授予      → 已经有了，不弹窗
     *   在列表里 + 永久拒绝    → 不弹窗（弹了也改不了结果）
     *   **不在列表里**         → 不可申请：可能压根没在 manifest 申请过、
     *                            安装期裁决没批、或不是 USER_CONSENT 这一档
     *
     * 最后一条是把「内核没列出来」直接当成「不可申请」。这样本应用就不需要知道
     * 授予模式、信任等级、签名角色这些判据——它们全在 nervud 里，在 UI 侧复刻
     * 一份只会产生第二个真相源。
     */
    private fun askUser(
        operationId: Long,
        callerPackageId: String,
        permissionIds: List<String>,
        rationale: String,
    ) {
        val pkg = listGrants().firstOrNull { it.packageId == callerPackageId }
        val grantable = pkg?.permissionsList?.associateBy { it.permissionId } ?: emptyMap()

        val outcomes = mutableMapOf<String, RequestPermissionOutcome>()
        val toAsk = mutableListOf<PermissionGrant>()

        for (id in permissionIds.distinct()) {
            val grant = grantable[id]
            when {
                grant == null ->
                    outcomes[id] = outcome(id, false, Decision.PERMISSION_REQUEST_DECISION_NOT_REQUESTABLE)

                grant.state == GrantState.GRANT_STATE_GRANTED ->
                    outcomes[id] = outcome(id, true, Decision.PERMISSION_REQUEST_DECISION_GRANTED)

                grant.state == GrantState.GRANT_STATE_DENIED_PERMANENT ->
                    outcomes[id] = outcome(id, false, Decision.PERMISSION_REQUEST_DECISION_DENIED_PERMANENT)

                else -> toAsk += grant
            }
        }

        // 一条都不用问：直接回结果，不弹窗。一个已经全部拿到（或全都不可申请）的
        // 申请弹一个空窗，对用户是纯骚扰
        if (toAsk.isEmpty()) {
            completeRequest(operationId, outcomes.values)
            return
        }

        val answer = java.util.concurrent.CompletableFuture<Set<String>>()
        _pendingRequest.value = PendingRequest(
            callerPackageId = callerPackageId,
            callerLabel = pkg?.label.orEmpty(),
            rationale = rationale,
            items = toAsk.toList(),
            answer = answer,
        )
        // 先让窗口可见，再拉到前台。
        //
        // 两步都要：window.show() 管的是 Compose 侧的 visible（上一次申请答完后
        // 它被隐藏了），activateWindow 管的是窗口管理器侧的焦点。少了前者，
        // 一个隐藏着的窗口无从被 wmctrl 激活；少了后者，窗口可见但压在别人下面。
        window.show()

        // 把窗口拉到前台。失败要当成「没能问到用户」而全部按拒绝处理 ——
        // 【绝不能静默放行】：显示不出确认框时把权限授出去，等于无声地替用户
        // 点了同意
        if (!X11WindowControl.activateWindow(WINDOW_TITLE)) {
            log.warning("requestPermission: cannot activate window, treating as denied")
            for (grant in toAsk) {
                outcomes[grant.permissionId] =
                    outcome(grant.permissionId, false, Decision.PERMISSION_REQUEST_DECISION_DENIED)
            }
            completeRequest(operationId, outcomes.values)
            return
        }

        // 有界等待：用户可能永远不回答（走开了、窗口被别的东西盖住）。无界等待会
        // 让这个 operation 与工作线程一起永久占着，而闸门也再不释放——下一次申请
        // 会一直 BUSY。超时按「拒绝」处理，与显示失败同一方向
        val accepted = try {
            answer.get(USER_ANSWER_TIMEOUT_MINUTES, java.util.concurrent.TimeUnit.MINUTES)
        } catch (_: java.util.concurrent.TimeoutException) {
            log.info("requestPermission: user did not answer in time, treating as denied")
            emptySet()
        }

        for (grant in toAsk) {
            val id = grant.permissionId
            if (id !in accepted) {
                outcomes[id] = outcome(id, false, Decision.PERMISSION_REQUEST_DECISION_DENIED)
                continue
            }
            // 用户点了同意，但**以落库之后回读的状态为准**而不是回显他的点击：
            // SetGrantState 仍可能失败（包刚被卸载、权限刚被降权）。回一个
            // granted=true 而实际没生效，调用方会立刻撞上 PERMISSION_DENIED
            val state = runCatching { setGrantState(callerPackageId, id, true) }.getOrElse { e ->
                log.warning("requestPermission: grant $id failed: ${e.message}")
                GrantState.GRANT_STATE_UNSPECIFIED
            }
            val ok = state == GrantState.GRANT_STATE_GRANTED
            outcomes[id] = outcome(
                id, ok,
                if (ok) Decision.PERMISSION_REQUEST_DECISION_GRANTED
                else Decision.PERMISSION_REQUEST_DECISION_DENIED,
            )
        }
        completeRequest(operationId, outcomes.values)
    }

    private fun completeRequest(operationId: Long, outcomes: Collection<RequestPermissionOutcome>) {
        // 顺序确定（按权限 ID），与 proto 里的约定一致：调用方按 ID 索引，
        // 但一份稳定排序的结果让它的日志可比对
        val result = RequestPermissionResult.newBuilder()
            .addAllOutcomes(outcomes.sortedBy { it.permissionId })
            .build()

        // 【必须在 completeOperation 之前收窗口】。completeOperation 是唤醒申请方
        // 的那一下：它从 callOperation 醒过来，很可能立刻把自己的窗口拉到前台。
        // 之后再调 hideActiveWindow 就是在跟它抢同一个「活跃窗口」——而那个函数
        // 最小化的是【当时正活跃的那个】，抢输了我们就把申请方的窗口最小化了。
        //
        // 放在这里，申请方还阻塞在 callOperation 上，不可能来抢焦点，
        // 因此 hideActiveWindow 此刻一定作用在我们自己身上。
        hideWindowIfUnwanted()

        // 【用户拒绝也走 completeOperation 而不是 failOperation】：那是一次正常
        // 完成的确认，答案是「不」。回失败码会让调用方分不清「用户说不」与
        // 「确认屏自己崩了」，而这两者在界面上该有完全不同的反应
        //
        // acceptOperation 必须先调：状态机不允许 PENDING 直接到 SUCCEEDED
        acceptOperation(operationId)
        completeOperation(operationId, result.toByteArray())
    }

    /**
     * 申请答完之后把窗口收走——**仅当**它是为这次申请才浮上来的。
     *
     * 隐藏而不是退出进程：本组件是 on-demand 的，空闲超时由内核回收
     * （manifest 的 `idle_timeout_sec`）。自己 exit 会让下一次申请重新付一次
     * Compose 冷启动，而用户在那一两秒里看不到任何反应。
     *
     * 失败只记日志：申请的结果已经落库了，一次窗口操作失败不该影响它。
     */
    private fun hideWindowIfUnwanted() {
        if (windowWanted) return
        // 【用 window.hide() 而不是 X11 最小化】。
        //
        // 曾经这里调 X11WindowControl.hideActiveWindow()（xdotool windowminimize），
        // 而在这个环境里最小化会让 Compose 触发 onCloseRequest —— 于是「隐藏自己」
        // 实际上【杀掉了本进程】。症状离原因很远：进程没了，设置侧缓存的 endpoint
        // 变成死号（NOT_FOUND），而它一旦被重新 Resolve 拉起又弹一个新的全屏窗口,
        // 用户看到的是「权限窗口关不掉」。
        //
        // window.hide() 走 Compose 自己的 visible，不经过窗口管理器，进程留着，
        // 已注册的 endpoint 继续有效。
        window.hide()
    }

    /** 界面把用户的选择交回工作线程。accepted 是用户勾上的那些权限 ID。 */
    fun resolvePendingRequest(accepted: Set<String>) {
        _pendingRequest.value?.answer?.complete(accepted)
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
        // 返回键隐藏窗口而不是结束进程。
        //
        // 【不能用 X11WindowControl.hideActiveWindow】：那是 xdotool 的最小化，
        // 在这个环境里会让 Compose 触发 onCloseRequest，于是按一下返回键整个进程
        // 就退了——本组件注册的 endpoint 随之失效，而设置那边还缓存着它。
        //
        // 返回 true 表示「这一次返回本应用自己处理了」，不要再往上冒泡。
        onUnhandledBack = {
            app.window.hide()
            true
        },
        windowHandle = app.window,
        onDisconnect = {
            log.severe("permissionui: control plane lost, exiting")
            exitProcess(1)
        },
    ) {
        PermissionUiScreen(app)
    }
}
