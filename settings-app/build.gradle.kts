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

    // 设置只调 pkgmanager，自己不启动别的应用，所以不要 perm.system.launch
    permissions = listOf<String>()

    // 组件 ID 必须是 main：保护名单里写的是 "nervus.settings/main"
    components.app("main") {
        mainClass = "com.nervus.settings.SettingsKt"
        runtime = "jvm"
        // manual：由桌面用 LaunchComponent 显式拉起
        launchMode = "manual"
        interfaces = listOf("nervus.interface.pkgmanager")
        limits.memoryMaxMb = 512
        limits.tasksMax = 256
    }

    signing.keyFile = rootProject.file("signing/platform-systemapp.pem")
}
