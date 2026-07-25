package com.nervus.launcher

import io.github.nervusos.iface.pkgmanager.v1.ListRequest
import io.github.nervusos.iface.pkgmanager.v1.ListResult
import io.github.nervusos.iface.pkgmanager.v1.PackageInfo

/**
 * `nervus.interface.pkg.manager` 的方法 ID。
 *
 * 取值来自 `nervus-ipc/proto/nervus/interface/pkgmanager/v1/pkg_manager.proto`
 * 的 `PackageManagerMethod` 枚举，**以 proto 为准**——那里的 `method_meta.method_id`
 * 与枚举值编号必须一致（`go/registry` 的 ExtractMethodMetas 在抽取时会校验，
 * 不一致直接 fail closed）。
 *
 * 这里手写常量而不是从生成的枚举取，是因为生成的 Kotlin 枚举给出的是
 * `PackageManagerMethod.LIST.number`，读起来反而绕；但两边一旦不一致，
 * 调用会得到一个准确的 NOT_FOUND，不会静默走错方法。
 */
object PkgManagerMethods {
    const val INTERFACE_ID = "nervus.interface.pkg.manager"

    const val INSTALL = 1
    const val UNINSTALL = 2
    const val LIST = 3
    const val SET_COMPONENT_ENABLED = 4
}

/** 桌面/设置共用的 List 结果解码。payload 就是 protobuf，绝不手搓 */
fun decodeList(payload: ByteArray): List<PackageInfo> =
    ListResult.parseFrom(payload).packagesList

/** LIST 无参数，但仍要发一个空的 ListRequest —— payload 的类型由 method 决定 */
fun listRequestPayload(): ByteArray = ListRequest.getDefaultInstance().toByteArray()
