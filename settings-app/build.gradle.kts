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

    // 设置只调 pkgmanager，自己不启动别的应用，所以不要 perm.system.launch。
    //
    // 但 pkgmanager 这个接口本身是有门槛的：内核的 Resolve 只做【接口级】裁决，
    // nervus.interface.pkg.manager 整个接口挂 perm.pkg.install
    // （endpoint/catalog.go），LIST / UNINSTALL / SET_COMPONENT_ENABLED 走同一道门。
    // 权限列表留空 = Resolve 直接 PERMISSION_DENIED，设置里一个应用都列不出来。
    //
    // perm.authority.power：电源页的重启/关机。MinTrust=Platform（本包是
    // platform-systemapp 签的，够）。故意【不是】perm.authority.reboot ——
    // 那条是 reboot(2) 硬重启，只给 platform-release，属于故障恢复路径。
    permissions = listOf("perm.pkg.install", "perm.pkg.query", "perm.authority.power")

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
