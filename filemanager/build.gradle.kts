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
}

nspkg {
    packageId = "nervus.filemanager"
    label = "文件"
    version = project.version.toString()
    versionCode = 1L
    minNervusApi = 1
    targetNervusApi = 1
    supportedAbis = listOf(nervusAbi)

    runtimeDeps.minJavaRelease = 21

    // perm.storage.user 是文件管理器的全部能力来源。
    //
    // 没有它，沙箱下这个应用只能读到只读的整个文件系统（ProtectSystem=strict）、
    // 写不了任何地方，连一个目录都建不出来——界面能开，但什么也做不了。
    // 声明之后内核会把 /var/lib/nervus/user-data 加进 ReadWritePaths
    // （见 service.readWritePaths）
    permissions = listOf("perm.storage.user")

    components.app("main") {
        mainClass = "com.nervus.filemanager.FileManagerKt"
        runtime = "jvm"
        launchMode = "manual"
        limits.memoryMaxMb = 384
        limits.tasksMax = 128
    }

    signing.keyFile = rootProject.file("signing/platform-systemapp.pem")
}
