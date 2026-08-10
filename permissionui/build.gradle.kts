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
    // perm.service.register 是【导出接口的门槛】，与上面那条无关：
    // 内核在 RegisterEndpoint 时按 Export 的 visibility 选权限 ID 并裁决
    // （public → perm.service.register，package → .private，见
    // endpoint/register.go 的步骤 3）。本包导出 permission.ui 且 visibility
    // 是 public，因此必须持有它。
    //
    // 漏掉它的症状很难自己想到：进程正常起来、连上控制面、然后 RegisterEndpoint
    // 被拒（missing permission perm.service.register）→ SDK 抛异常 → 进程退出
    // → 内核重启它 → 再拒，直到熔断。而调用方那侧看到的是
    // RESOLVE_ENDPOINT_REASON_INTERFACE_NOT_FOUND——「没有服务注册这个接口」，
    // 一个完全不指向权限的错误。
    permissions = listOf("perm.permission.admin", "perm.service.register")

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
        // 【刻意不设 idle_timeout_sec】。
        //
        // 曾经设过 300 秒，那是错的：内核的空闲超时只看"有没有活跃调用"，而一个
        // 用户正看着的界面【没有任何活跃调用】——他在读权限说明，不产生 IPC。
        // 于是超时把进程连窗口一起回收，用户眼前的界面凭空消失；而设置那侧缓存的
        // endpoint 随即变成死号，下一次"打开权限管理"报 NOT_FOUND。
        //
        // 换句话说：空闲超时适合无界面的按需服务，不适合承载窗口的组件——
        // "空闲"对前者等于"没人用"，对后者等于"用户正在读"。
        //
        // 进程不会因此永久驻留：窗口关闭时 attachComposeDesktop 会 app.close()
        // 并退出进程（见 DesktopCompose 的 onCloseRequest），而权限申请答完之后
        // 界面自己隐藏窗口、进程随之空转但只占一份 JVM——下次申请可以直接复用，
        // 省掉一次 Compose 冷启动。
        //
        // 【如果将来要加回超时】，前提是先让内核把"有窗口在前台"算作活跃，
        // 否则改多长都只是让这个 bug 更难复现。
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
