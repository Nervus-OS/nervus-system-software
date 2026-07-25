package com.nervus.sysui

/**
 * 由 package_id 推出界面上该显示什么、点开时该 Resolve 什么。
 *
 * ## 为什么是约定而不是读 manifest
 *
 * 启动器拿到的应用列表来自 `nervus.interface.pkgmanager` 的 LIST，而它的
 * `PackageInfo` 目前**只有** package_id / version / trust / source /
 * granted_permissions / disabled_components —— 没有 label、没有 icon、
 * 也没有「这个包的哪个组件能被点开、对应哪个接口」。
 *
 * 这条信息在内核里是有的（manifest 的 label 与 components[].exports），只是
 * 没有沿 `adminwire.PackageInfo` → `pkg_manager.proto` → pkgmanagerd 这条链
 * 投影出来。补齐它要同时改三个仓库，其中 pkgmanagerd 正在并行开发中，
 * 因此 v1 先用约定，把跨仓库改动留到接口稳定之后。
 *
 * ### 补齐后要做的事（三步，按顺序）
 *
 * 1. `nervud/internal/adminwire/adminwire.go`：`PackageInfo` 加
 *    `Label string` 与 `Apps []AppEntry{ComponentID, Label, Interface}`，
 *    在 `admin/handlers.go` 的投影函数里从 manifest 填。
 * 2. `nervus-ipc/proto/nervus/interface/pkgmanager/v1/pkg_manager.proto`：
 *    `PackageInfo` 加对应字段，`buf generate`。
 * 3. `nervus-system-server/pkgmanagerd`：List 转发新字段。
 *
 * 三步做完后本文件的 [displayName] 与 [launchComponentOf] 都应删除，
 * 改读服务端给的真值——**约定的问题在于它无法表达例外**：一个包有两个 app
 * 组件时，`main` 这个缺省必然选错其中一个。
 */
object AppIdentity {

    /**
     * 点开一个应用时该启动它的哪个 Component。
     *
     * `LaunchComponent` 要求显式给出 component_id（不做「留空 = 唯一的 app
     * 组件」这类推断），而 pkgmanager 的 `PackageInfo` 目前不投影组件列表 ——
     * 于是这里先用约定：内置应用查表，其余一律 `main`。
     *
     * `main` 作为缺省是有依据的：`nervus-app-example` 与 `pkgmanagerd` 的
     * manifest 都用它，是事实上的惯例。但**约定无法表达例外** —— 一个包有两个
     * app 组件时它就选错了，这也是 PackageInfo 该投影组件列表的直接理由
     * （补齐步骤见本文件类注释）。
     */
    fun launchComponentOf(packageId: String): String =
        builtinComponents[packageId] ?: DEFAULT_COMPONENT

    private const val DEFAULT_COMPONENT = "main"

    private val builtinComponents = mapOf(
        "nervus.launcher" to "desktop",
    )

    /** 系统内置应用的显示名。第三方应用走 [prettify] 兜底 */
    private val builtinNames = mapOf(
        "nervus.launcher" to "桌面",
        "nervus.settings" to "设置",
        "nervus.filemanager" to "文件",
        "nervus.pkgmanagerd" to "软件包服务",
        "nervus.sessiond" to "会话服务",
        "nervus.permissionui" to "权限确认",
        "nervus.safety.recovery" to "安全恢复",
    )

    fun displayName(packageId: String): String =
        builtinNames[packageId] ?: prettify(packageId)

    /**
     * `com.example.hello_world` → `Hello World`
     *
     * 取最后一段是因为前缀是命名空间不是名字；把 `_`/`-` 换成空格再首字母大写，
     * 是在没有 label 时能做到的最不难看的展示。
     */
    private fun prettify(packageId: String): String =
        packageId.substringAfterLast('.')
            .replace('_', ' ')
            .replace('-', ' ')
            .split(' ')
            .filter { it.isNotEmpty() }
            .joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
            .ifEmpty { packageId }

    /**
     * 是否是「不该出现在启动器里」的包。
     *
     * 纯后台服务（pkgmanagerd、sessiond…）没有界面，列出来点了也没反应。
     * 判据用显式名单而不是「package_id 里有没有 d 结尾」这类猜测——
     * 猜错的代价是用户点一个永远打不开的图标。
     */
    fun isHeadless(packageId: String): Boolean = packageId in headlessPackages

    private val headlessPackages = setOf(
        "nervus.pkgmanagerd",
        "nervus.sessiond",
        "nervus.safety.recovery",
        // 桌面自己不该出现在桌面上
        "nervus.launcher",
    )
}
