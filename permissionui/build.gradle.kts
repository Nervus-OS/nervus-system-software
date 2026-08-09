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
    // isProtectedComponent 与 ui-common 的 AppIdentity 都写了
    // "nervus.permissionui"——提供授权 UI 的自己不能被停用，否则用户会失去
    // 唯一能收回敏感权限的地方，而且不会有任何报错
    packageId = "nervus.permissionui"
    label = "权限"
    version = project.version.toString()
    versionCode = 1L
    // 依赖 nervus.interface.permission.admin，那是 API level 2 才有的内建接口
    minNervusApi = 2
    targetNervusApi = 2
    supportedAbis = listOf(nervusAbi)

    runtimeDeps.minJavaRelease = 21

    // perm.permission.admin：读写各包 USER_CONSENT 权限的运行期授予状态。
    //
    // 这条权限是 SYSTEM_ONLY + MinTrust=Platform + 【platform-release 签名角色】。
    // 最后一项是本模块与 settings-app / launcher / filemanager 的分界线：
    // 那三个签 platform-systemapp，够 Platform 信任但拿不到这条权限。
    //
    // 【这正是设计意图，不是配置疏忽】。「能授予权限的权限」一旦落到设置应用
    // 手里，就等于把「谁能给任意应用开摄像头和运动控制」和一个功能繁多、
    // 迭代频繁的包绑在一起。Android 把 PermissionController 做成独立 APK
    // 是同一个理由。设置应用应当跳转到这里，而不是自己承载。
    //
    // 【只有这一条】。本包刻意【不持有】任何装包权限。
    //
    // 安装期的权限展示与确认归 nervus.packageinstaller，那是另一个包。理由与
    // 上面那段是同一条：「能授予权限」和「能装任意软件」是两个高权限能力，
    // 合在一个进程里就等于造出一个能同时做这两件事的目标。Android 把
    // PermissionController 与 PackageInstaller 做成两个独立 APK 正是如此。
    permissions = listOf("perm.permission.admin")

    // Provider 契约产物（provider.binpb + schemas.binpb）。
    //
    // 导出接口的包【必须】带它：内核 loadRequiredProviderArtifacts 对「有
    // exports 却没有 provider」直接返回 ErrProviderArtifactsRequired，症状是
    // 本包在开机扫描时被隔离，而它自己什么日志都没有。
    //
    // 产物不在这里生成——两份字节必须是 Go 的 Deterministic protobuf 编码，
    // protobuf-java 不保证逐字节相同。它们由 nervus-ipc 的 registry/providerkit
    // 生成并提交，随 nervus-app-sdk 的 jar 发布，这里只负责取出来放进包。
    provider { fromClasspath("nervus.permissionui") }

    components.app("main") {
        mainClass = "com.nervus.permissionui.PermissionUiKt"
        runtime = "jvm"
        // on-demand：设置（或别的应用）Resolve 本接口时由内核拉起。
        //
        // 【必须是 on-demand 而不是 manual】：Resolve 只拉得起 on-demand 的
        // 组件。留 manual 的话，设置 Resolve permission.ui 会在「组件没在跑」
        // 这一步失败——而那正是「从设置跳到某个包的权限页」需要的路径。
        launchMode = "on-demand"
        // 空闲超时后由内核回收。
        //
        // 【必须显式设一个值】：一次纯粹的权限申请会把本组件拉起来，答完之后
        // 界面自己会隐藏窗口（见 PermissionUi.hideWindowIfUnwanted），但隐藏
        // 只是看不见——进程还在。留空的话它会一直驻留到下次重启，而它是个带
        // 全屏 Compose 窗口的进程，白占内存。
        //
        // 300 秒而不是更短：用户从设置跳进权限页读那些说明要时间，超时把界面
        // 从他眼前收走是最糟的一种打断。回收只发生在没有活跃调用之后。
        idleTimeoutSec = 300
        // 导出权限管理界面接口（OpenManager）。
        //
        // visibility = public：调用方是设置一类的别的包，不可能与本包同属一个
        // package。private 只在同包组件之间可见，写成那样等于谁都 Resolve 不到。
        //
        // 这一条必须与 provider.binpb 里声明的接口【完全对应】：catalog 的
        // addArtifacts 是双向闭合的——descriptor 里有而 exports 里没有，或
        // 反之，两种都会让整个 Catalog 构建失败（不只是本包被隔离）。
        exports.register("nervus.interface.permission.ui") { visibility = "public" }
        // 内核目前不读这个字段（Resolve 只看 perm.Allowed），写全是为了 manifest
        // 如实反映组件会调什么
        interfaces = listOf("nervus.interface.permission.admin")
        limits.memoryMaxMb = 512
        limits.tasksMax = 256
    }

    // 【与同仓其它模块不同的那一行】。见上面 permissions 的说明：
    // platform-release 是拿到 perm.permission.admin 的必要条件。
    // deploy/build-release.sh 会把这个 key 拷进 signing/
    signing.role = "platform-release"
    signing.keyFile = rootProject.file("signing/platform-release.pem")
}
