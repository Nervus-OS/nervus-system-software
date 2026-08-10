package com.nervus.filemanager

import androidx.compose.ui.unit.dp
import com.nervus.sdk.component.ComponentConfig
import com.nervus.sdk.component.InterfaceRequirement
import com.nervus.sdk.component.NervusApp
import com.nervus.sdk.ui.NervusWindow
import com.nervus.sdk.ui.attachComposeDesktop
import java.util.logging.Logger
import kotlin.system.exitProcess

/**
 * 文件管理器。
 *
 * 它是三个系统应用里唯一**不调任何 IPC 接口**的：文件操作走本地 `java.nio`，
 * 权限由沙箱决定（声明 `perm.storage.user` → 内核把 `/var/lib/nervus/user-data`
 * 加进 ReadWritePaths）。
 *
 * 那为什么还要连控制面？两个理由：
 *
 *  1. **握手就是身份核对**。连上意味着 nervud 用 PID → cgroup → unit 确认了
 *     "这个进程确实是 nervus.filemanager/main"。连不上说明它不是被内核拉起的，
 *     此刻退出比继续跑一个身份不明的文件管理器合理。
 *  2. **控制面断开 = 内核没了**，这时应该退出让 supervisor 重启，而不是留一个
 *     还能删文件、但系统已经失控的窗口。
 *
 * 文件选择器接口（供别的 app 调用）留到 v2 —— 它需要先在 nervus-ipc 里定
 * `.proto`，而临时手搓一套 payload 编码是明确的红线。
 */
class FileManager(config: ComponentConfig) : NervusApp(config) {
    override val requiredInterfaces: List<InterfaceRequirement> = emptyList()
}

fun main() {
    val log = Logger.getLogger("filemanager")
    val fm = FileManager(ComponentConfig(componentId = "main"))

    try {
        fm.start()
    } catch (e: Exception) {
        log.severe("filemanager: cannot reach control plane: ${e.message}")
        exitProcess(1)
    }

    Runtime.getRuntime().addShutdownHook(Thread { fm.close() })

    val storageReady = UserStorage.ensureRoot()
    if (!storageReady) {
        // 不直接退出：让界面打开并说清楚原因。这个失败几乎总是配置问题
        // （preflight 没跑过，或 manifest 漏了 perm.storage.user），
        // 一句能看懂的提示比一个静默退出的进程有用得多
        log.warning("user-data 不可写：${UserStorage.ROOT}")
    }

    // 窗口句柄：让返回键隐藏窗口而不是杀掉进程（见下面 onUnhandledBack）。
    val windowHandle = NervusWindow()

    fm.attachComposeDesktop(
        title = "文件",
        width = 1000.dp,
        height = 700.dp,
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
            log.severe("filemanager: control plane lost, exiting")
            exitProcess(1)
        },
    ) {
        FileManagerScreen(storageReady)
    }
}
