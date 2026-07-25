# nervus-system-software

Nervus OS 的系统内置应用。Kotlin + Compose Desktop，Material 3。

产出三个**系统镜像包**，随镜像发布，装在
`/usr/lib/nervus/system-packages/`，与系统服务（`nervus-system-server`）并列。

| 包 | 组件 | 干什么 |
|---|---|---|
| `nervus.launcher` | `desktop`(app)<br>`launcherd`(service, always-on) | 桌面 + 唤醒它的常驻服务；状态栏带电源按钮 |
| `nervus.settings` | `main`(app) | 已装软件 / 电源 / 关于 / 开发者选项 |
| `nervus.filemanager` | `main`(app) | 共享用户目录的文件管理 |

**要开发新的系统应用，看 [DEVELOPING.md](DEVELOPING.md)。**

---

## 构建

需要 JDK 21。目标 ABI 缺省 `linux-arm64`（真机）。

```sh
# WSL 上首次克隆后
sed -i 's/\r$//' gradlew

export JAVA_HOME=~/.sdkman/candidates/java/current
export PATH=$JAVA_HOME/bin:$PATH

# 签名密钥（从 deploy/keys 拷）
mkdir -p signing && cp ../../deploy/keys/platform-systemapp.pem signing/

./gradlew nspkgImageTree                            # 真机
./gradlew nspkgImageTree -Pnervus.abi=linux-x86_64  # 本机验证
```

产物：`<模块>/build/outputs/image-tree/<package_id>/`，含 `manifest.json` +
`manifest.sig` + `lib/*.jar`。

整机发布包由 `deploy/build-release.sh` 构建，它会自动收走上面全部产物。

## 仓库结构

```text
nervus-system-software/
├── settings.gradle.kts   includeBuild(../nervus-app-sdk, ../nervus-packaging)
├── build.gradle.kts      ABI 参数 + skiko 平台去重
├── ui-common/            共享：轮询、应用身份约定、电源接口
├── launcher/
├── settings-app/
├── filemanager/
└── signing/              platform-systemapp.pem（不入库）
```

SDK 与打包插件走**复合构建**而不是 Maven 依赖：三个仓库在同一轮里一起演进，
走仓库会逼着每改一行 SDK 就发一次版本。

## 相关仓库

| 仓库 | 是什么 |
|---|---|
| `nervud` | 内核。权限目录、接口目录、沙箱、装包都在这 |
| `nervus-ipc` | 控制面协议（protobuf），四语言生成 |
| `nervus-app-sdk` | Kotlin SDK：`NervusApp` / `NervusService` / Compose 接线 |
| `nervus-packaging` | Gradle 插件 + 签名库 |
| `nervus-system-server` | 系统服务（Go）：pkgmanagerd、安全恢复 |
| `deploy/` | 构建发布包与装机脚本 |
