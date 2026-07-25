rootProject.name = "nervus-system-software"

// SDK 与打包插件按兄弟目录引入（与 nervus-app-example 同一模式）。
// 复合构建而不是 Maven 依赖：三个仓库在同一轮里一起演进，走仓库会逼着
// 每改一行 SDK 就发一次版本
val siblings = rootProject.projectDir.parentFile!!
includeBuild(file("$siblings/nervus-app-sdk"))
includeBuild(file("$siblings/nervus-packaging"))

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
        google()
        // Compose Multiplatform 的部分构件只在 JetBrains 仓库
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

include(":ui-common")
include(":launcher")
include(":settings-app")
include(":filemanager")
