plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
}

kotlin { jvmToolchain(21) }

// 目标 ABI 决定拉哪个平台的 skiko（Compose 的渲染后端带原生库）。
// 不用 compose.desktop.currentOs：它取的是构建机平台，交叉构建时会把
// x86_64 的 skiko 塞进 arm64 包，在真机上才以 UnsatisfiedLinkError 暴露
val nervusAbi: String = rootProject.extra["nervusAbi"] as String

dependencies {
    // api 而不是 implementation：下游三个应用直接用 SDK 的类型（NervusApp、
    // NervusTheme、ProvidedInterface），不该再各自声明一遍
    api("com.nervus.sdk:nervus-app-sdk:0.1.0")
    api(libs.kotlinx.coroutines.core)
    api(
        when (nervusAbi) {
            "linux-arm64" -> compose.desktop.linux_arm64
            "linux-x86_64" -> compose.desktop.linux_x64
            else -> throw GradleException("no compose desktop artifact for abi '$nervusAbi'")
        }
    )
    api(compose.material3)
    testImplementation(libs.kotlin.test)
    // 【不要】加 compose.materialIconsExtended：那一个 jar 就是 37 MB
    // （整包 79 MB 的 47%），装的是全套 Material 图标的矢量定义。
    // Compose Desktop 没有 R8/ProGuard 那样的按需裁剪，加进来就是整包搬走。
    //
    // 系统 UI 用到的 Material Symbols 矢量路径集中在 NervusIcons.kt，只把
    // 实际渲染的十几个图标编进包里。
}
