// 三个系统内置应用的共通配置。
//
// 目标 ABI 由 -Pnervus.abi 决定，缺省 linux-arm64（真机）。它同时决定两件事：
//   1. manifest 的 supported_abis —— 内核装包时按它做 Host ABI 匹配
//   2. 拉哪个平台的 skiko —— Compose Desktop 的渲染后端带原生库，平台相关
//
// 用构建参数而不是 compose.desktop.currentOs：后者拿的是【构建机】的平台。
// 在 x86_64 上交叉构建 arm64 包时，currentOs 会悄悄塞进 x86_64 的 skiko，
// 包能打出来、能装上，到真机上才以 UnsatisfiedLinkError 炸掉。
//
//   ./gradlew nspkgImageTree                          → linux-arm64（真机）
//   ./gradlew nspkgImageTree -Pnervus.abi=linux-x86_64 → 本机 WSL 验证
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.compose) apply false
}

// 与内核 pkgregistry/manifest.go 的 canonical ABI token 一致。
// 那边只认这三个，Android NDK 名（arm64-v8a）与裸 CPU 名（aarch64）一律拒绝
val supportedAbiTokens = setOf("linux-arm64", "linux-armv7", "linux-x86_64")

val nervusAbi: String = (findProperty("nervus.abi") as String?) ?: "linux-arm64"
require(nervusAbi in supportedAbiTokens) {
    "unknown nervus.abi '$nervusAbi'; expected one of $supportedAbiTokens"
}

// 让子项目都能读到
extra["nervusAbi"] = nervusAbi

subprojects {
    group = "com.nervus.system"
    version = "0.1.0"
}
