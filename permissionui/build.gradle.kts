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
    permissions = listOf("perm.permission.admin")

    components.app("main") {
        mainClass = "com.nervus.permissionui.PermissionUiKt"
        runtime = "jvm"
        // manual：只由桌面或设置显式拉起。
        //
        // 【等 ConfirmInstall 接口落地后改成 on-demand】——那时安装方需要
        // Resolve 到本组件来请求一次确认，而 Resolve 只拉得起 on-demand 的组件。
        // 现在它还不导出任何接口，声明 on-demand 是在说一件不存在的事
        launchMode = "manual"
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
