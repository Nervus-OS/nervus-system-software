plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
    id("com.nervus.packaging")
}

kotlin { jvmToolchain(21) }

val nervusAbi: String = rootProject.extra["nervusAbi"] as String

dependencies {
    implementation(project(":ui-common"))
    implementation(libs.protobuf.java)
}

nspkg {
    // package_id 【不可更改】：内核 pkgregistry/lifecycle.go 的
    // isProtectedComponent 把 "nervus.settings/main" 写进了不可停用名单
    // ——提供停用 UI 的自己不能被停用，否则系统失去自我修复能力。
    // 改名等于让那条保护失效，而且不会有任何报错
    packageId = "nervus.settings"
    label = "设置"
    version = project.version.toString()
    versionCode = 1L
    minNervusApi = 1
    targetNervusApi = 1
    supportedAbis = listOf(nervusAbi)

    runtimeDeps.minJavaRelease = 21

    // pkgmanager 接口以 perm.pkg.query 保护 Resolve 和 LIST；UNINSTALL /
    // SET_COMPONENT_ENABLED 在每次 method 路由时再叠加 perm.pkg.install。
    // 设置既展示列表又执行这两类变更，因此两项都要声明。
    //
    // perm.authority.power：电源页的重启/关机。MinTrust=Platform（本包是
    // platform-systemapp 签的，够）。故意【不是】perm.authority.reboot ——
    // 那条是 reboot(2) 硬重启，只给 platform-release，属于故障恢复路径。
    //
    // 【刻意不要 perm.system.launch】。权限管理入口改走 permission.ui 的
    // OpenManager 之后不再需要它：Resolve 那个接口就会由内核 on-demand 拉起
    // permissionui，而 OpenManager 的 required_permission 是 perm.pkg.query
    // ——上面已经有了。
    //
    // 这个差别值得留一笔：perm.system.launch 不限制目标组件（内核
    // handleLaunchComponent 只查权限），持有它就能拉起机器上任意组件；而
    // Resolve 只能到达「导出了这个接口的那个组件」。同一件事，后者的授权面
    // 窄得多，且还能顺带传参（要看哪个包的权限）。
    //
    // 【为什么设置自己不承载权限管理】：改 USER_CONSENT 授予状态需要
    // perm.permission.admin，那条权限要求 platform-release 签名，而本模块签
    // platform-systemapp，够不着。这是设计意图不是疏漏——见 permissionui 的
    // build.gradle.kts。所以设置只负责跳转过去。
    permissions = listOf(
        "perm.pkg.install",
        "perm.pkg.query",
        "perm.authority.power",
    )

    // 组件 ID 必须是 main：保护名单里写的是 "nervus.settings/main"
    components.app("main") {
        mainClass = "com.nervus.settings.SettingsKt"
        runtime = "jvm"
        // manual：由桌面用 LaunchComponent 显式拉起
        launchMode = "manual"
        // 电源页走内建的 power 接口。内核目前不读这个字段（Resolve 只看
        // perm.Allowed），写全是为了 manifest 如实反映组件会调什么
        interfaces = listOf("nervus.interface.pkg.manager", "nervus.interface.power")
        limits.memoryMaxMb = 512
        limits.tasksMax = 256
    }

    signing.keyFile = rootProject.file("signing/platform-systemapp.pem")
}
