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
    //
    // perm.pkg.install / perm.pkg.query：读已装应用列表要它们。
    //
    // 【为什么列表要 install 权限】：内核的 Resolve 是【接口级】门槛，
    // nervus.interface.pkg.manager 整个接口挂的是 perm.pkg.install
    // （endpoint/catalog.go），LIST 和 INSTALL 走同一道门。method 级细分要等
    // method_registry 接线，那之前只读列表也得带上 install 权限，否则
    // Resolve 直接 PERMISSION_DENIED —— 症状是桌面一个应用都列不出来，
    // 而日志里只有一句 "failed to resolve optional interface"。
    // perm.pkg.query 现在不起作用（同上），一并声明是为了 method_registry
    // 接上那天不用再改 manifest。
    //
    // perm.authority.power：状态栏的重启/关机按钮。故意【不是】
    // perm.authority.reboot —— 那条是 reboot(2) 硬重启，只给 platform-release。
    permissions = listOf(
        "perm.system.launch",
        "perm.pkg.install",
        "perm.pkg.query",
        "perm.authority.power",
    )

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
        // 桌面要读已装应用列表；状态栏的重启/关机走内建的 power 接口。
        //
        // 内核【目前不读这个字段】（Resolve 只看 perm.Allowed），写全是因为
        // manifest 应当如实声明组件会调什么——将来真做 imports 强制时，
        // 漏声明的后果是运行期突然解析不到，而没人会想到去查一个从没生效过的字段
        interfaces = listOf("nervus.interface.pkg.manager", "nervus.interface.power")
        limits.memoryMaxMb = 512
        limits.tasksMax = 256
    }

    // ---- navigation：固定在屏幕右侧的系统返回/主页栏 --------------------
    //
    // 与 desktop 一样是 manual app，由 launcherd 开机后显式拉起。它不导出接口，
    // 也不拥有应用业务权限；Back 通过 X11 投递给当前焦点窗口，Home 复用本包已有
    // 的 perm.system.launch 保证 desktop 存在，再让窗口管理器激活它。
    components.app("navigation") {
        mainClass = "com.nervus.launcher.NavigationKt"
        runtime = "jvm"
        launchMode = "manual"
        limits.memoryMaxMb = 192
        limits.tasksMax = 128
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
