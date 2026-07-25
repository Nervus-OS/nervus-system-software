package com.nervus.launcher

import com.nervus.sdk.component.ComponentConfig
import com.nervus.sdk.component.InterfaceRequirement
import com.nervus.sdk.component.NervusApp
import java.util.logging.Logger
import kotlin.system.exitProcess

/**
 * launcherd：把桌面唤醒并保活的常驻服务。
 *
 * ⚠ **不要和 `nervus.sessiond` 混淆**：那是另一个东西——管 HUMAN/AI 控制主体
 * 会话、配合 ControlLease `controller_class` 的系统服务（内核保护名单里的
 * `nervus.sessiond/main`），与桌面无关。
 *
 * ## 它为什么存在
 *
 * 桌面是个 app，而内核硬校验 **app 不能 always-on**
 * （`pkgregistry.ErrLaunchModeTypeMismatch`）。所以桌面自己没法开机自启。
 *
 * launcherd 就干这一件事：always-on 起来，用 `LaunchComponent`（Envelope body 80）
 * 请求内核拉起 `nervus.launcher/desktop`。
 *
 * 需要 `perm.system.launch`（MinTrust=Platform）——所以它必须是平台签名的
 * 系统镜像包，第三方装不出一个能抢占桌面的会话服务。
 *
 * ## 为什么不自己写重试循环
 *
 * 连不上 nervud 就直接退出，让 nervud 的 supervisor 按指数退避重启
 * （系统服务的统一纪律，见 nervus-system-server 的 README）。自己重试会把
 * "nervud 没起来"这个事实盖住，进程看起来健康、实际什么也没做。
 *
 * 崩溃预算烧不完：退避 1s→2s→4s→8s，崩溃时刻约 t=0,1,3,7,15，而熔断阈值是
 * 10 秒窗口内 5 次，指数退避永远凑不满。
 *
 * ## 桌面挂了怎么办
 *
 * [keepDesktopAlive] 定期重新 Resolve。桌面活着时这是一次廉价的查表；
 * 桌面死了则会再次触发拉起。轮询而非事件驱动，是因为 nervud 尚未实现
 * Subscribe（发过去会被直接关连接）。
 */
class Launcherd(config: ComponentConfig) : NervusApp(config) {

    // 不在 requiredInterfaces 里声明桌面接口：那批在 start() 里解析，一旦失败
    // 整个组件启动失败。而"桌面暂时拉不起来"不该让 launcherd 也起不来——
    // launcherd 活着才有人继续尝试。改为 start 之后自己按节奏 resolveNow
    override val requiredInterfaces: List<InterfaceRequirement> = emptyList()

    private val log = Logger.getLogger(Launcherd::class.java.name)

    fun keepDesktopAlive(intervalMs: Long = DESKTOP_CHECK_INTERVAL_MS) {
        while (true) {
            try {
                // LaunchComponent 本身幂等：桌面在跑时这是一次廉价的查表，
                // 不在跑时就地把它拉起来。所以"检测 + 启动"是一次调用而不是两次，
                // 中间也就不存在"查完到启动之间桌面死了"的窗口
                val alreadyRunning = launchComponent(DESKTOP_PACKAGE, DESKTOP_COMPONENT)
                if (!alreadyRunning) {
                    log.info("desktop was not running; launched it")
                }
            } catch (e: Exception) {
                // 常见原因：桌面正在启动、崩溃退避中、或被停用。
                // 都不是 launcherd 该处理的问题，记一笔下轮再试
                log.warning("launch desktop failed, will retry: ${e.message}")
            }
            Thread.sleep(intervalMs)
        }
    }

    private companion object {
        const val DESKTOP_PACKAGE = "nervus.launcher"
        const val DESKTOP_COMPONENT = "desktop"
        const val DESKTOP_CHECK_INTERVAL_MS = 5_000L
    }
}

fun main() {
    val log = Logger.getLogger("launcherd")
    val launcherd = Launcherd(ComponentConfig(componentId = "launcherd"))

    try {
        launcherd.start()
    } catch (e: Exception) {
        // 连不上控制面就退出，交给 supervisor 退避重启。
        // 组件被拉起时 ipc 模块可能还没 listen（service 注册在第 8 位、ipc 在第 12 位），
        // 这个竞态是预期内的，退避重启就能过
        log.severe("launcherd: cannot reach control plane: ${e.message}")
        exitProcess(1)
    }

    log.info("launcherd: up, waking desktop")
    Runtime.getRuntime().addShutdownHook(Thread { launcherd.close() })
    launcherd.keepDesktopAlive()
}
