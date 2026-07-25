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
    packageId = "nervus.launcher"
    label = "Nervus 桌面"
    version = project.version.toString()
    versionCode = 1L
    minNervusApi = 1
    targetNervusApi = 1
    supportedAbis = listOf(nervusAbi)

    runtimeDeps.minJavaRelease = 21

    // perm.system.launch：桌面点开别的应用、launcherd 唤起桌面，都要它。
    // MinTrust=Platform，因此本包必须用平台角色签名（platform-systemapp，见 signing.keyFile）
    permissions = listOf("perm.system.launch")

    // ---- desktop：桌面本体 ----------------------------------------------
    //
    // type 用 app：内核据此给它接上 X11（把 /tmp/.X11-unix 绑进 PrivateTmp
    // 之后的私有 /tmp，并注入 DISPLAY）。service 类型拿不到这些，Compose 起不来。
    components.app("desktop") {
        mainClass = "com.nervus.launcher.DesktopKt"
        runtime = "jvm"
        // manual：桌面不该被任何 Resolve 顺带拉起，只由 launcherd 显式启动。
        // LaunchComponent 不看 launch_mode，所以 manual 组件照样能被拉起——
        // 在内核加这条消息之前，manual 是个死状态（没有任何 wire 能启动它）
        launchMode = "manual"
        // 桌面要读已装应用列表
        interfaces = listOf("nervus.interface.pkgmanager")
        limits.memoryMaxMb = 512
        limits.tasksMax = 256
    }

    // ---- launcherd：把桌面唤醒的常驻服务 --------------------------------
    //
    // 【不是】 nervus.sessiond —— 那是管 HUMAN/AI 控制主体会话的另一个系统服务
    //
    // 桌面自己起不来：内核硬校验 app 不能 always-on（ErrLaunchModeTypeMismatch）。
    // 所以要一个 always-on 的 service 在开机后主动把桌面拉起来。
    //
    // criticality 用 optional 而不是 vital：vital 组件熔断（10 秒内崩 5 次）会触发
    // 整机 Safety 锁存让机器人停下来。桌面起不来是难看，不是危险。
    components.service("launcherd") {
        mainClass = "com.nervus.launcher.LauncherdKt"
        runtime = "jvm"
        launchMode = "always-on"
        criticality = "optional"
        interfaces = listOf("nervus.launcher.app")
        limits.memoryMaxMb = 128
        limits.tasksMax = 32
    }

    signing.keyFile = rootProject.file("signing/platform-systemapp.pem")
}
