# Nervus 系统应用开发指南

从零写一个跑在 Nervus OS 上的系统应用，到把它装进设备。

本文里出现的每一个 API 签名、DSL 字段、权限 ID、枚举取值，都是从对应仓库的源码
里核出来的，不是凭印象写的。改了那些源码，这份文档也要跟着改——文中标了每一条
的出处，方便核对。

---

## 0. 先明确你要做的是哪一种

| | 系统应用（本文） | 普通应用 |
|---|---|---|
| 放在哪 | `/usr/lib/nervus/system-packages/`，随镜像发布 | `/var/lib/nervus/packages/`，`nervusctl install` 动态装 |
| 签名角色 | `platform-systemapp` | `developer` |
| 拿到的 trust | `platform` | `ordinary`（**动态安装永远只能是 ordinary**，签什么都一样） |
| 能拿的权限 | 含 `MinTrust=Platform` 的那些 | 只有 `MinTrust=Ordinary` 的 |
| 产物形态 | 目录树（`nspkgImageTree`） | `.nspkg` 压缩包（`nspkg`） |
| 装法 | 刷镜像 / OTA | `nervusctl install` |

**判据是安装来源，不是签名。**一份带 `platform-systemapp` 签名的包走动态安装
路径，照样只拿 `ordinary`（`nervud/internal/pkgregistry/arbitrate.go`）。所以
"给普通应用签个平台密钥就能提权"这条路不存在。

本仓库产出的三个包（`nervus.launcher` / `nervus.settings` /
`nervus.filemanager`）都是系统应用，可以直接照抄。

---

## 1. 加一个新系统应用

假设要做一个叫 `nervus.monitor` 的系统监视器。

### 1.1 建目录

```sh
cd nervus-system-software
mkdir -p monitor/src/main/kotlin/com/nervus/monitor
```

### 1.2 注册进复合构建

`settings.gradle.kts` 末尾加一行：

```kotlin
include(":monitor")
```

### 1.3 `monitor/build.gradle.kts`

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
    id("com.nervus.packaging")
}

kotlin { jvmToolchain(21) }

// 目标 ABI 由根项目的 -Pnervus.abi 决定，见 build.gradle.kts 顶部注释。
// 【必须读它】，不能用 compose.desktop.currentOs——那拿的是构建机的平台
val nervusAbi: String = rootProject.extra["nervusAbi"] as String

dependencies {
    // ui-common 用 api 传递了 SDK、coroutines、compose desktop、material3，
    // 不要再各自声明这几样（版本分叉会变成运行期 NoSuchMethodError）
    implementation(project(":ui-common"))

    // 【要调接口就必须显式加它】。接口的 payload 是 protobuf，生成类的父类
    // GeneratedMessage / MessageOrBuilder 来自 protobuf-java，而 SDK 只是
    // implementation 依赖它、不往下传。缺了就是一串
    //   Cannot access 'com.google.protobuf.GeneratedMessage' which is a
    //   supertype of '...ListResult'
    // 纯界面、不调任何接口的模块（如 filemanager）不需要这一行
    implementation(libs.protobuf.java)
}

nspkg {
    packageId = "nervus.monitor"
    label = "监视器"
    version = project.version.toString()
    versionCode = 1L
    minNervusApi = 1
    targetNervusApi = 1
    supportedAbis = listOf(nervusAbi)

    runtimeDeps.minJavaRelease = 21

    // 见 §6。漏声明 = 运行期 PERMISSION_DENIED，而不是构建期报错
    permissions = listOf("perm.pkg.install", "perm.pkg.query")

    components.app("main") {
        mainClass = "com.nervus.monitor.MonitorKt"
        runtime = "jvm"
        launchMode = "manual"
        interfaces = listOf("nervus.interface.pkg.manager")
        limits.memoryMaxMb = 384
        limits.tasksMax = 128
    }

    signing.keyFile = rootProject.file("signing/platform-systemapp.pem")
}
```

### 1.4 `Monitor.kt`

```kotlin
package com.nervus.monitor

import androidx.compose.material3.Text
import androidx.compose.ui.unit.dp
import com.nervus.sdk.component.ComponentConfig
import com.nervus.sdk.component.InterfaceRequirement
import com.nervus.sdk.component.NervusApp
import com.nervus.sdk.ui.attachComposeDesktop
import java.util.logging.Logger
import kotlin.system.exitProcess

class Monitor(config: ComponentConfig) : NervusApp(config) {

    override val requiredInterfaces: List<InterfaceRequirement> = listOf(
        InterfaceRequirement(id = "nervus.interface.pkg.manager", isRequired = false)
    )

    // call() 在 NervusApp 里是 protected —— 界面代码在类外，
    // 所以每个要用的调用都在这里包一个 public 方法。§4.3 有说明
    fun packageCount(): Int =
        ListResult.parseFrom(
            call(
                interfaceId = "nervus.interface.pkg.manager",
                methodId = 3, // LIST
                payload = ListRequest.getDefaultInstance().toByteArray(),
            )
        ).packagesCount
}

fun main() {
    val log = Logger.getLogger("monitor")
    // componentId 必须与 manifest 里的组件 ID 一致，见 §3.3
    val monitor = Monitor(ComponentConfig(componentId = "main"))

    try {
        monitor.start()
    } catch (e: Exception) {
        log.severe("monitor: cannot reach control plane: ${e.message}")
        exitProcess(1)
    }
    Runtime.getRuntime().addShutdownHook(Thread { monitor.close() })

    monitor.attachComposeDesktop(
        title = "监视器",
        width = 900.dp,
        height = 600.dp,
        onDisconnect = {
            log.severe("monitor: control plane lost, exiting")
            exitProcess(1)
        },
    ) {
        Text("已装 ${monitor.packageCount()} 个包")
    }
}
```

### 1.5 打包

```sh
./gradlew :monitor:nspkgImageTree -Pnervus.abi=linux-arm64
```

产物在 `monitor/build/outputs/image-tree/nervus.monitor/`，含 `manifest.json`、
`manifest.sig`、`lib/*.jar`。`deploy/build-release.sh` 会自动把
`*/build/outputs/image-tree/*/` 全部收进发布包，**不需要改构建脚本**。

---

## 2. 运行模型：你的代码是怎么被跑起来的

搞清楚这一节，后面的约束才不像是随意规定。

### 2.1 谁启动你

nervud 开机扫描 `/usr/lib/nervus/system-packages/*/manifest.json`，验签、校
digest、分配 UID，然后按 `launch_mode` 决定什么时候起哪个组件：

| launch_mode | 什么时候启动 | 可用于 |
|---|---|---|
| `always-on` | 开机就起，崩了自动重启 | **只能是 service** |
| `on-demand` | 有人 Resolve 它导出的接口时起 | app / service |
| `manual` | 只有别人显式 `LaunchComponent` 才起 | **只能是 app** |

内核硬校验这条：`app` 不能 `always-on`，`service` 不能 `manual`
（`pkgregistry/manifest.go` 的 `ErrLaunchModeTypeMismatch`）。

所以**桌面这类"有界面但要开机自启"的东西必须拆开**：一个 `always-on` 的
service 负责唤醒，一个或多个 `manual` 的 app 是界面本体。`nervus.launcher`
就是这么做的（`launcherd` 唤醒 `desktop` 和 `navigation`）。

### 2.2 每个组件是一个独立进程

每个组件由 nervud 经 systemd D-Bus 起成一个**瞬态 unit**：

```
nervus-<package_id>-<component_id>.service
```

它以本包专属的 UID 运行（20000–59999，每个包一个，跨重启稳定，从不回收复用），
并带一整套沙箱。你的 `main()` 就跑在那里面。

同一个包的两个组件是**两个进程、同一个 UID**。

### 2.3 JVM 是共享的

`runtime = "jvm"` 的组件由平台 JRE（`/usr/lib/nervus/jre`）拉起，
**不是每个应用自带一份 JVM**。你的包里只有自己的 jar 和依赖。

Compose Desktop 的运行时（含 30 MB 的 skiko 原生库）目前**是每个包各带一份**，
所以一个带界面的系统应用大约 41 MB。共享运行时是 v2 的事（见 §10）。

### 2.4 握手是有身份核对的

组件连上 `/run/nervus/nervud.sock` 时要报 `declared_component_id`，nervud 会做
PID → cgroup → unit 名的三重核对。**核不上就 `UNAUTHENTICATED` 断开。**

后果：**你没法脱离 nervud 单独跑一个组件去连控制面**。开发期 UI 迭代要么用假
数据跑纯 Compose（§8.3），要么全程在装好的系统里调。

---

## 3. 包与组件

### 3.1 package_id

`pkgregistry/manifest.go` 的 `validPackageID`：

- 点分段，1–8 段，总长 ≤ 128
- 每段只能 `[A-Za-z0-9_-]`，且不能以 `-`/`_` 开头结尾
- 系统应用用 `nervus.` 前缀是约定，不是强制

它同时是**目录名**（`/var/lib/nervus/package-data/<package_id>`）和
**记账文件名**，所以字符集卡得比看上去严。

### 3.2 component_id

`validComponentID`：单段，≤ 64 字符，同样的字符集。

**约定 `main`**：`ui-common` 的 `AppIdentity.launchComponentOf()` 在没有内建映射
时默认返回 `"main"`，桌面靠它决定点图标启动哪个组件。你的 app 组件不叫 `main`
的话，得在 `AppIdentity` 的 builtin 映射里补一条，否则桌面点不开。

### 3.2.1 让新应用在桌面上正常显示

`PackageInfo` 目前**不投影 label / icon / 组件列表**（见 §12），所以桌面只能靠
`ui-common/AppIdentity.kt` 里的约定表。加了新包之后按需要改这三处：

| 情况 | 改哪里 |
|---|---|
| app 组件不叫 `main` | `builtinComponents` 加 `"你的包" to "你的组件"` |
| 想要中文显示名 | `builtinNames` 加一条；不加会走 `prettify`（`nervus.monitor` → `Monitor`） |
| 是纯后台服务，不该出现在桌面 | `headlessPackages` 加一条 |

不改也能跑，只是显示名难看或点不开。`icon` 字段桌面暂时没用（M3 卡片显示的是
首字母），填了也不会显示。

### 3.3 componentId 必须两边一致

`ComponentConfig(componentId = "main")` 里的值必须等于 manifest 里的组件 ID。
不一致时握手核对失败，症状是组件起来就 `UNAUTHENTICATED` 退出、反复重启。

### 3.4 受保护组件

这几个组件内核拒绝停用（`pkgregistry/lifecycle.go` 的 `isProtectedComponent`）：

```
nervus.pkgmanagerd/main      装包通道
nervus.settings/main         提供停用 UI 的自己不能被停用
nervus.permissionui/main     权限确认通道
nervus.sessiond/main
nervus.safety.recovery/main  安全恢复
```

**这也是 `nervus.settings` 的 package_id 和组件 ID 不能改的原因**——名单是硬编码
字符串。

---

## 4. SDK

依赖 `com.nervus.sdk:nervus-app-sdk`，本仓库里由 `ui-common` 以 `api` 传递。

### 4.1 两个基类

| | `NervusApp` | `NervusService` |
|---|---|---|
| 干什么 | **消费**接口 | **提供**接口 |
| 必须实现 | `requiredInterfaces` | `providedInterfaces` |
| 也可以 | `providedInterfaces`（可选） | — |

两者都继承 `Component`，都有 `start()` / `close()` / `isActive()`。
`attachComposeDesktop` 挂在 `Component` 上，所以**两种都能有界面**。

### 4.2 `NervusApp` 的成员

```kotlin
abstract class NervusApp(config: ComponentConfig = ComponentConfig()) : Component(config) {
    protected abstract val requiredInterfaces: List<InterfaceRequirement>
    protected open val providedInterfaces: List<ProvidedInterface> = emptyList()

    protected fun call(
        interfaceId: String,
        methodId: Int,
        payload: ByteArray = ByteArray(0),
        timeoutSeconds: Long = 30,
    ): ByteArray

    protected fun launchComponent(
        packageId: String,
        componentId: String,
        timeoutSeconds: Long = 30,
    ): Boolean   // true = 调用之前就已经在跑

    protected fun resolveNow(
        interfaceId: String,
        minMajor: Int = 1,
        maxMajor: Int = 1,
        timeoutSeconds: Long = 30,
    ): Long

    protected open fun dispatch(/* 收到别人调用你导出的接口时 */)
}
```

`InterfaceRequirement`：

```kotlin
InterfaceRequirement(
    id: String,
    minMajor: Int = 1,
    maxMajor: Int = 1,
    resourceType: String = "",   // 留空 = 默认 nervus.resource.motion.base/main
    resourceRole: String = "",
    isRequired: Boolean = true,  // false = 解析失败只 WARNING，组件照常启动
)
```

`ComponentConfig`：

```kotlin
ComponentConfig(
    socketPath: String = "/run/nervus/nervud.sock",
    sdkName: String = "nervus-app-sdk",
    sdkVersion: String = "0.1.0",
    handshakeTimeoutMs: Long = 5000,
    autoReconnect: Boolean = true,
    maxReconnectAttempts: Int = 5,
    componentId: String = "",    // 必填，见 §3.3
)
```

### 4.3 `call` 是 protected —— 要包一层

界面代码在类外，调不到 `protected fun call`。所以**每个要用的调用都在组件类里包一个
public 方法**，顺便把 protobuf 解码也放进去：

```kotlin
fun listPackages(): List<PackageInfo> =
    ListResult.parseFrom(
        call(PkgManager.INTERFACE_ID, PkgManager.LIST, ListRequest.getDefaultInstance().toByteArray())
    ).packagesList
```

这不是绕开封装，是有意的：调用点集中在一处，UI 只看到语义方法。三个现有应用
都是这个形态。

### 4.4 为什么不用动态代理 `use<T>()`

SDK 有一套基于反射的 `InterfaceProxy`，但 Kotlin 的 `suspend fun` 和
`CompletableFuture<T>` 在字节码里返回类型分别是 `Object` 和 `CompletableFuture`
——**泛型参数运行期拿不到**，代理会退化成返回原始 `ByteArray`，调用方一转型就
`ClassCastException`。

系统接口的 payload 都是 protobuf，显式写 `XxxResult.parseFrom(call(...))`
既准确又看得懂。**新代码一律用 `call`。**

### 4.5 千万别调 `subscribe`

SDK 里有 `NervusApp.subscribe()` 和 `SubscriptionManager`，但**内核没实现
Subscribe**：`ipc/conn.go` 的 `handleReady` 把 `Subscribe` / `Unsubscribe` /
`Cancel` 全部落进 `unsupported(env)` → **直接关闭连接**。

调它的症状是"连接莫名其妙断了"，看不出跟订阅有关。

**所有需要刷新的 UI 一律轮询**，用 `ui-common` 的 `rememberPolled`（§8.1）。

---

## 5. 调用系统接口

### 5.1 三步

1. 在 `requiredInterfaces` 里声明 → SDK 启动时自动 Resolve
2. 在 manifest 的 `permissions` 里声明该接口需要的权限（§6）
3. 用 `call(interfaceId, methodId, payload)`

### 5.2 现有接口一览

| interface_id | 需要的权限 | 提供方 | method_id 定义在 |
|---|---|---|---|
| `nervus.interface.pkg.manager` | `perm.pkg.install` | `nervus.pkgmanagerd` | `nervus-ipc` 的 `pkg_manager.proto` |
| `nervus.interface.safety.control` | `perm.safety.rearm` | **内核内建** | `safety_control.proto` |
| `nervus.interface.power` | `perm.authority.power` | **内核内建** | 手写常量，见下 |
| `nervus.interface.motion.base` | `perm.motion.control` | OEM Provider | `motion.proto` |
| `nervus.interface.manipulator.arm` | `perm.manipulator.control` | OEM Provider | `manipulator.proto` |

门槛表的唯一真源是 `nervud/internal/endpoint/catalog.go`。

> ⚠️ **interface_id 不等于 proto 的目录名。**
> `nervus.interface.pkg.manager` 的 proto 在
> `nervus/interface/pkgmanager/v1/`——中间**没有点**。照着目录名抄成
> `nervus.interface.pkgmanager` 会解析失败，而症状只有一句
> `failed to resolve optional interface`，看不出是拼错了。真机上踩过。

### 5.3 权限是**接口级**的

`endpoint/resolve.go` 步骤 5 只查一个接口级门槛，**method 级细分还没接线**。
后果：

- 只想读列表（LIST）也要 `perm.pkg.install`，因为它和 INSTALL 走同一道门
- `perm.pkg.query` 已登记但**当前不生效**，写上是为了 method_registry 接线那天
  不用改 manifest

### 5.4 内建接口

`nervus.interface.safety.control` 和 `nervus.interface.power` 由 nervud 自己实现
（`endpoint/builtin.go`），调用方用**完全标准**的 Resolve + call 访问，感知不到
对面是内核还是外部 Provider。

`nervus.interface.power` 的方法 ID **没有 proto 兜底**，唯一定义在
`nervud/internal/power/builtin.go`，Kotlin 侧镜像在
`ui-common/.../PowerControl.kt`。用它请直接引 `PowerControl`，别再抄一份数字。

### 5.5 启动别的应用

```kotlin
fun launch(packageId: String, componentId: String) {
    val alreadyRunning = launchComponent(packageId, componentId)
}
```

走 Envelope 的 `LaunchComponent`(body 80)。需要 `perm.system.launch`
（`MinTrust=Platform`，所以只有系统应用能用）。

组件 ID 一般用 `AppIdentity.launchComponentOf(packageId)` 推。

---

## 6. 权限

### 6.1 完整目录

真源：`nervud/internal/permission/catalog.go`。

| 权限 ID | MinTrust | 授予方式 | 说明 |
|---|---|---|---|
| `perm.diagnostics.read` | Ordinary | install | 读诊断信息 |
| `perm.service.register.private` | Ordinary | install | 注册仅本包可见的接口 |
| `perm.service.register` | **OEM** | install | 注册 public 接口 |
| `perm.storage.user` | Ordinary | install | 共享用户目录 `/var/lib/nervus/user-data` |
| `perm.pkg.query` | Ordinary | install | 列出已装包（**当前不生效**，见 §5.3） |
| `perm.pkg.install` | Ordinary | **user** | 装/卸包 —— 也是 pkgmanager 接口的门槛 |
| `perm.camera.capture` | Ordinary | user | 摄像头 |
| `perm.motion.control` | Ordinary | user | 底盘运动 |
| `perm.manipulator.control` | Ordinary | user | 机械臂 |
| `perm.bluetooth.admin` | Ordinary | user | 蓝牙管理 |
| `perm.network.admin` | Ordinary | user | 网络管理 |
| `perm.safety.observe` | **OEM** | install | 读安全状态 |
| `perm.system.launch` | **Platform** | install | 启动别的组件 |
| `perm.platform.control` | **Platform** | signature | 平台控制（占位） |
| `perm.authority.power` | **Platform** | signature | 有序重启/关机 |
| `perm.authority.reboot` | **Platform** | signature | `reboot(2)` 硬重启，**仅 `platform-release`** |
| `perm.safety.rearm` | **Platform** | signature | 解除停机锁存，**仅 `platform-release`** |

系统应用（`platform-systemapp` 签名 → `platform` trust）能拿到除最后两条之外的
全部。那两条带 `RequireSignerRole: "platform-release"`，只给内核和核心系统服务。

### 6.2 V1 的现状：申请即授予

`permission.V1GrantAll = true`：运行期不做用户确认，`GrantUser` 的权限也直接给。

**但 manifest 里仍然必须声明。**`permission.Allowed` 跳过的是运行期授予状态，
不是安装集检查。漏写 = 运行期 `PERMISSION_DENIED`，**构建期不会报错**。

这是最容易踩的一个坑：代码写对了、接口 ID 也对，就因为 manifest 少一行，
界面上什么都没有，日志里只有一句 warning。

### 6.3 重启 vs 关机，别选错

| | `perm.authority.reboot` | `perm.authority.power` |
|---|---|---|
| 动作 | `reboot(2)` | systemd `shutdown.target` |
| 组件收得到 SIGTERM | 否 | 是 |
| 文件系统正常卸载 | 否 | 是 |
| 谁能拿 | 仅 `platform-release` | 任何 Platform trust |
| 用途 | 故障恢复 | **用户按的那个按钮** |

系统应用要做电源按钮，用 `perm.authority.power`，直接复用
`ui-common` 的 `PowerControl` + `PowerConfirmDialog`。

---

## 7. 沙箱：你能碰什么

组件的瞬态 unit 带这些（`nervud/internal/authority/systemd/props.go`）：

```
NoNewPrivileges=yes
ProtectSystem=strict          整个文件系统只读
ProtectHome=yes               /home、/root 完全不可访问
PrivateTmp=yes                私有 /tmp
PrivateDevices=yes            无设备节点（系统镜像包可放开）
DevicePolicy=closed
SystemCallFilter=@system-service
RestrictAddressFamilies=AF_UNIX AF_INET AF_INET6
```

### 7.1 可写的地方

`service/supervise.go` 的 `readWritePaths`：

| 路径 | 条件 |
|---|---|
| `/var/lib/nervus/package-data/<package_id>` | 恒有。**你的私有数据目录** |
| `/var/lib/nervus/user-data` | 声明了 `perm.storage.user` |
| staging 根 | 只有 `nervus.pkgmanagerd` |

**别的地方一律写不了**，即使属主和权限看着对——`ProtectSystem=strict` 是另一道
独立的门。

### 7.2 JVM 的临时目录已经处理好了

内核给 `runtime=jvm` 的组件注入：

```
-Djava.io.tmpdir=<私有数据目录>
-Duser.home=<私有数据目录>
```

**这是 Compose 能跑起来的前提**：skiko 要把 30 MB 的 `.so` 从 jar 里解出来再
dlopen，默认 tmpdir 在 strict 下写不了。

### 7.3 图形

`type = "app"` 的组件内核会自动：

- 把 `/tmp/.X11-unix` 绑定挂载进私有 `/tmp`
- 注入 `DISPLAY` 和 `XAUTHORITY`（取自 nervud 自己的环境）
- 把 cookie 文件也绑进去

`type = "service"` 的组件**拿不到这些**，Compose 起不来。要界面就用 `app`。

### 7.4 读不到的地方

`/var/lib/nervus/registry`（内核记账）被设成 `InaccessiblePaths`，任何组件都读
不到。想知道装了什么，走 pkgmanager 接口，别试图读文件。

---

## 8. 界面

### 8.1 `rememberPolled` —— 唯一的刷新方式

```kotlin
val apps by rememberPolled<List<PackageInfo>>(
    initial = emptyList(),
    intervalMs = 3000,
    key = currentDir,        // key 变了就重建协程
) {
    desktop.listApps()       // 自动在 Dispatchers.IO 上跑
}

apps.value      // 最近一次成功的值
apps.loading    // 正在取
apps.error      // 最近一次失败的信息；失败【不清空】上一次的值
```

> ⚠️ **`key` 参数不能省。**内部是 `LaunchedEffect(intervalMs, key)`——只传
> `intervalMs` 的话，闭包捕获的变量（比如当前目录）变了协程也不会重建，界面
> 一直显示旧数据。文件管理器的目录导航就是这么坏过的。

### 8.2 Material 3

`attachComposeDesktop` 内部已经套了 `NervusTheme`（就是配好 light/dark
colorScheme 的 `MaterialTheme`），**直接用 `MaterialTheme.colorScheme` /
`typography` 即可**，不用自己再包一层。

> ⚠️ **不要加 `compose.materialIconsExtended`。**那一个 jar 就是 37 MB
> （整包的 47%），Compose Desktop 没有 R8 那样的按需裁剪，加进来就是整包搬走。
> 要图标从 `material3` 自带的 `Icons.Default` 里取；确实需要冷门图标就把那几个
> 的矢量数据拷进项目。

### 8.3 不连内核跑纯 UI

最快的迭代回路：留一个只跑 Compose 的 `main()`，喂假数据。

```kotlin
fun main() = application {
    Window(onCloseRequest = ::exitApplication) {
        NervusTheme { MonitorScreen(fakeData()) }
    }
}
```

因为 §2.4 的握手核对，**没有别的办法在开发机上单独跑一个组件**。

### 8.4 接入系统「返回」

右侧系统导航栏会向当前前台 X11 窗口发送 `Escape`。使用
`attachComposeDesktop` 的应用不用自己监听键盘，只需在当前页面注册处理器：

```kotlin
NervusBackHandler(enabled = dialogOpen || currentDirectory != rootDirectory) {
    when {
        dialogOpen -> dialogOpen = false
        currentDirectory != rootDirectory -> currentDirectory = currentDirectory.parent
    }
}
```

同一窗口里可以嵌套多个处理器；最近注册且 `enabled` 的处理器优先。页面没有可返回
状态时，由 `attachComposeDesktop(onUnhandledBack = { ... })` 的根 fallback 决定
怎么处理。系统内置应用目前用它隐藏当前根窗口、露出下层窗口，桌面则直接消费返回。

应用被再次从桌面启动时，nervud 会返回 `alreadyRunning`。桌面随后按窗口标题激活
现有窗口，因此内置应用的标题必须保持为 `AppIdentity.displayName(packageId)`。
不要用退出进程实现返回或主页；进程生命周期仍由 nervud 管理。

---

## 9. 构建、签名、部署

### 9.1 两个任务

| 任务 | 产物 | 签名角色 |
|---|---|---|
| `nspkgImageTree` | `build/outputs/image-tree/<pkg>/` 目录树 | `platform-systemapp`（缺省） |
| `nspkg` | `.nspkg` 压缩包 | `developer` |

系统应用用前者。`nspkgImageTree` **拒绝 `developer` 角色**——用错角色的后果是
装上去 trust 掉成 `ordinary`，症状离根因很远，所以在构建期就失败。

### 9.2 ABI

```sh
./gradlew nspkgImageTree                            # linux-arm64（缺省，真机）
./gradlew nspkgImageTree -Pnervus.abi=linux-x86_64  # 本机 WSL 验证
```

它同时决定 `supported_abis`（内核装包时按它匹配 Host ABI）和拉哪个平台的 skiko。
**别用 `compose.desktop.currentOs`**——那拿的是构建机平台，交叉构建时会悄悄塞进
错误架构的原生库，包能打出来、能装上，到真机才 `UnsatisfiedLinkError`。

内核只认三个 token：`linux-arm64` / `linux-armv7` / `linux-x86_64`。
Android NDK 名（`arm64-v8a`）和裸 CPU 名（`aarch64`）一律拒绝。

### 9.3 签名密钥

`signing/platform-systemapp.pem`，由 `deploy/build-release.sh` 从
`deploy/keys/` 拷进来。本地手工构建时自己拷一份。

key_id 是**裸 32 字节公钥**的 sha256：

```
key_id = "sha256:" + hex(sha256(raw 32-byte ed25519 pubkey))
```

不是 X.509 SPKI（44 字节）的 sha256。算错的后果是包装得上、跑得起来，只是
`trust=ordinary`——因为 `scanSystemImage` 对验签失败是 fail-closed 到 Ordinary
而不是跳过整个包。真机上踩过。

### 9.4 进发布包

`deploy/build-release.sh` 会 glob `*/build/outputs/image-tree/*/` 全部收走，
**新加的模块不用改脚本**。

### 9.5 装到设备

```sh
cd deploy && ./build-release.sh
scp nervus-os-linux-arm64-*.tar.gz <板子>:/tmp/
# 板子上
tar -xzf /tmp/nervus-os-*.tar.gz -C /tmp
sudo /tmp/nervus-release/install-nervus.sh /tmp/nervus-os-*.tar.gz
```

细节见 `deploy/README.md`。

---

## 10. 调试

### 10.1 日志在哪

**组件的日志不在 nervud 的 journal 里**，各自独立：

```sh
journalctl -u 'nervus-<package_id>-<component_id>.service' -n 50 -f
```

内核侧的裁决过程在 nervud 那边：

```sh
journalctl -u nervud -b | grep -i 'ResolveEndpoint\|LaunchComponent'
```

审计里有 `denied` 和具体权限 ID，**比应用侧那句 warning 有用得多**。

### 10.2 看包状态

```sh
sudo nervusctl list          # trust / source / disabled
systemctl list-units 'nervus-*'
```

### 10.3 按症状查

| 症状 | 大概率原因 |
|---|---|
| 组件起来立刻 `UNAUTHENTICATED` 退出 | `ComponentConfig.componentId` 与 manifest 不一致 |
| `failed to resolve optional interface` | interface_id 拼错，或 manifest 没声明所需权限 |
| 界面空白但没报错 | 同上，Resolve 失败被 `isRequired=false` 吞了 |
| `UnsatisfiedLinkError` / skiko | `-Pnervus.abi` 与目标机不符 |
| `NoClassDefFoundError: java/awt/*` | JRE 是 `-headless` 版，缺 `java.desktop` |
| `Can't connect to X11 window server` | 组件 `type` 写成了 `service`，或 X 没起来 |
| 写文件 `read-only file system` | 目标不在 `readWritePaths` 里（§7.1） |
| `trust=ordinary`（本该 platform） | 签名角色错，或 key_id 算法错（§9.3） |
| 组件 `203/EXEC` | `entry`/`mainClass` 与实际不符 |
| 组件 `226/NAMESPACE` | 私有数据目录根不存在 |
| 改了 DSL 但 manifest 没变 | 不会发生了——`nspkgImageTree` 永不判 UP-TO-DATE |

### 10.4 WSL 上的两个坑

```sh
# gradlew 是 CRLF，WSL 里执行不了（报 "cannot execute: required file not found"）
sed -i 's/\r$//' gradlew

# JDK 21 在 sdkman 里，不在默认 PATH
export JAVA_HOME=~/.sdkman/candidates/java/current
export PATH=$JAVA_HOME/bin:$PATH
```

`.gitattributes` 已经有 `gradlew text eol=lf`，新克隆的不会再有第一个问题。

---

## 11. manifest DSL 完整参考

`nspkg { }` 块，插件 `com.nervus.packaging`。

### 包级

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `packageId` | String | ✓ | §3.1 |
| `label` | String | ✓ | 显示名 |
| `labels` | Map<String,String> | | 多语言显示名 |
| `icon` | String | | 包内相对路径，**必须被 digests 覆盖** |
| `version` | String | ✓ | 只允许 `[A-Za-z0-9._+-]`，≤64（会当目录名用） |
| `versionCode` | Long | ✓ | 升级比较用的单调整数 |
| `minNervusApi` | Int | ✓ | 当前是 1 |
| `targetNervusApi` | Int | ✓ | 当前是 1 |
| `supportedAbis` | List<String> | ✓ | §9.2 |
| `permissions` | List<String> | | §6 |
| `runtimeDeps.minJavaRelease` | Int? | | JVM 组件填 21 |
| `usesFeatures.register(id) { required }` | | | 硬件特性声明 |
| `signing.keyFile` | File | ✓ | §9.3 |
| `signing.role` | String | | 镜像树缺省 `platform-systemapp` |

### 组件级

`components.app(id) { }` / `components.service(id) { }`

| 字段 | 缺省 | 说明 |
|---|---|---|
| `mainClass` | — | JVM 入口类。Kotlin 的 `Foo.kt` → `FooKt` |
| `entry` | JVM 恒为 `lib/<组件id>.jar` | **只有 `runtime="native"` 才需要手填**；JVM 下填了也会被插件覆盖 |
| `runtime` | `"jvm"` | `jvm` / `native` |
| `nativeLibDir` | null | 包内相对路径 |
| `launchMode` | app→`manual`<br>service→`on-demand` | §2.1 |
| `criticality` | null | `optional` / `required` / `vital` |
| `disableable` | null | 能否被用户停用 |
| `interfaces` | null | 本组件会**调用**的接口（当前内核不强制，但要如实写） |
| `idleTimeoutSec` | null | on-demand 组件空闲多久回收 |
| `limits.memoryMaxMb` | null | |
| `limits.cpuQuotaPercent` | null | |
| `limits.tasksMax` | null | |
| `exports.register(id) { visibility }` | | 本组件**提供**的接口，`package` / `public` |

`exports` 里声明的接口才能 `RegisterEndpoint`——**manifest 没声明的接口，运行期
注册一律被拒**。可见性两档：

| visibility | 谁能 Resolve 到 | 需要的权限 |
|---|---|---|
| `package` | 只有同一个包的其它组件 | `perm.service.register.private`（Ordinary） |
| `public` | 任何包（还要过接口级权限门槛） | `perm.service.register`（**MinTrust=OEM**） |

trust 的序是 `Ordinary < OEM < Platform`，判据是 `trust < entry.MinTrust`
（`permission/intersect.go`），所以 **Platform trust 的系统应用能拿到
`perm.service.register`**，可以对外提供 public 接口。普通应用（Ordinary）不行，
只能提供 `package` 可见性的。

> 新增 public 接口还要同步登记 `nervud/internal/endpoint/catalog.go`。
> **漏登记不是 fail-closed 而是 fail-open**——`Lookup` 未命中时门槛取空串，
> 即不设门槛，任意 Ordinary 应用都能解析到你的接口。`pkg.manager` 和
> `manipulator.arm` 都因为漏登记裸奔过一段时间。

---

## 12. v1 已知限制

写代码前该知道的：

| 限制 | 对你的影响 |
|---|---|
| Subscribe 未实现 | 所有 UI 必须轮询（§4.5） |
| 权限只有接口级 | 只读操作也要写操作的权限（§5.3） |
| `V1GrantAll = true` | 权限申请即授予，但 manifest 仍须声明（§6.2） |
| `PackageInfo` 不投影 label/icon/组件列表 | 桌面和设置只能按约定推显示名和启动组件（`AppIdentity`）。一个包有两个 app 组件时会选错 |
| 每个包自带 Compose 运行时 | 一个带界面的包约 41 MB。共享运行时要 `runtime_deps` 长出 `platform_runtime` 字段 + 内核拼 classpath，是 v2 |
| 空 selector 默认绑运动基座 | `DefaultRegistry` 编译期写死了 `base.main`，所以能解析到；但一旦改成真实资源注册表，没有底盘的设备会全部 Resolve 失败 |
| 审计写 slog | 无防篡改、无轮转 |
| `nervus.interface.power` 无 proto | 方法 ID 是两侧手写常量，改一侧必须同步改另一侧（§5.4） |

---

## 13. 三个现成的例子

照抄比读文档快：

| 想做的事 | 看哪个 |
|---|---|
| 最简单的带界面 app | `filemanager/` —— 一个组件、一个权限 |
| 调系统接口 + 多页面导航 | `settings-app/` —— NavigationRail、pkgmanager 四个方法、电源 |
| app + always-on service | `launcher/` —— `desktop` / `navigation` + `launcherd`，以及怎么唤醒多个界面 |
| 沙箱内的文件操作 | `filemanager/UserStorage.kt` —— 路径规范化 + 逃逸检查 |
| 轮询与状态 | `ui-common/Polling.kt` |

---

## 14. 新增一个系统应用的检查清单

按顺序过一遍。打 ✗ 的那几条**构建期不会报错**，只在真机上表现为"界面空白"或
"点了没反应"，是最费时间的一类问题。

**构建接线**

- [ ] `settings.gradle.kts` 里 `include(":你的模块")`
- [ ] `build.gradle.kts` 里读了 `rootProject.extra["nervusAbi"]`，没用 `currentOs`
- [ ] 依赖只写 `implementation(project(":ui-common"))`
- [ ] `signing.keyFile` 指向 `signing/platform-systemapp.pem`

**manifest**

- [ ] `packageId` 合法（点分段、`[A-Za-z0-9_-]`）
- [ ] 组件 `type` 与 `launchMode` 相容（app ≠ always-on，service ≠ manual）
- [ ] ✗ **要调的每个接口的权限都写进了 `permissions`**（§6.2）
- [ ] `interfaces` 如实列出会调的接口
- [ ] 要提供接口的话，`exports.register` 写了，且已登记内核的
      `endpoint/catalog.go`

**代码**

- [ ] ✗ **`ComponentConfig(componentId = ...)` 与 manifest 的组件 ID 一致**（§3.3）
- [ ] ✗ **interface_id 从内核 `endpoint/catalog.go` 抄，不是从 proto 目录名抄**（§5.2）
- [ ] `call()` 用 public 方法包了一层，UI 才调得到（§4.3）
- [ ] 没有调 `subscribe()`（§4.5）
- [ ] `rememberPolled` 传了 `key`（§8.1）
- [ ] 只往私有数据目录或 `user-data` 写（§7.1）
- [ ] 有界面的组件是 `app` 不是 `service`（§7.3）

**桌面显示**

- [ ] 组件不叫 `main` 的话，`AppIdentity.builtinComponents` 补了映射
- [ ] `AppIdentity.builtinNames` 补了显示名（可选）

**验证**

- [ ] `./gradlew :你的模块:nspkgImageTree -Pnervus.abi=linux-arm64` 通过
- [ ] `manifest.json` 里 `permissions` 和 `interfaces` 是你想要的
      （`grep -o '"permissions":\[[^]]*\]' .../manifest.json`）
- [ ] 装机后 `sudo nervusctl list` 显示 `trust=platform`
- [ ] `journalctl -u nervud -b | grep ResolveEndpoint` 没有 denied
