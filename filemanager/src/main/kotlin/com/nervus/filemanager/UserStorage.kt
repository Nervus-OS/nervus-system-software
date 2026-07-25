package com.nervus.filemanager

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import kotlin.io.path.name

/**
 * 共享用户文档区的文件操作。
 *
 * ## 作用域
 *
 * 一切操作都被限制在 [ROOT] = `/var/lib/nervus/user-data` 之内。这不只是产品
 * 决策，也是沙箱的事实：`ProtectSystem=strict` 让整个文件系统只读，只有声明了
 * `perm.storage.user` 的包才会拿到这一个可写目录（内核
 * `service.readWritePaths`）。往外写会得到一个 AccessDeniedException。
 *
 * ## 为什么还要自己做路径校验
 *
 * 沙箱已经挡住了越界写，但**读**没有被挡住——`ProtectSystem=strict` 之下整个
 * 文件系统仍然可读。一个拼接出 `../../etc/shadow` 的路径能被打开。所以
 * [resolveInside] 做的是限制"这个应用愿意展示什么"，与内核的强制隔离叠加，
 * 不是替代它。
 *
 * ## 权限模型的已知代价
 *
 * 该目录是 sticky（01777，语义同 `/tmp`）：任何拿到 `perm.storage.user` 的包
 * 都能读别人的文件，只是删不掉。等价于 Android scoped storage 之前的共享外部
 * 存储。真正的按包隔离需要每包一个 GID + SupplementaryGroups，是 v2 的事。
 */
object UserStorage {

    val ROOT: Path = Paths.get("/var/lib/nervus/user-data")

    /** 目录项的展示模型。不持有 Path 之外的状态，每次列目录都重新读 */
    data class Entry(
        val path: Path,
        val name: String,
        val isDirectory: Boolean,
        val sizeBytes: Long,
    )

    /**
     * 把一个相对片段安全地解析到 [base] 之下。
     *
     * `normalize()` 会把 `..` 折叠掉，折叠后仍必须以 [ROOT] 为前缀 —— 只做
     * `startsWith` 而不先 normalize 是挡不住 `a/../../etc` 的。
     *
     * 用 [Path.startsWith] 而不是字符串前缀比较：后者会让
     * `/var/lib/nervus/user-data-evil` 通过 `/var/lib/nervus/user-data` 的检查，
     * 而 Path 版本是按路径段比较的，不存在这个问题。
     */
    fun resolveInside(base: Path, segment: String): Path {
        val resolved = base.resolve(segment).normalize()
        require(resolved.startsWith(ROOT)) { "路径越出用户目录：$segment" }
        return resolved
    }

    /** 确保根目录存在。开发机上 preflight 没跑过时它可能不在 */
    fun ensureRoot(): Boolean =
        try {
            Files.createDirectories(ROOT)
            Files.isWritable(ROOT)
        } catch (e: IOException) {
            false
        }

    fun list(dir: Path): List<Entry> {
        require(dir.normalize().startsWith(ROOT)) { "目录越出用户目录：$dir" }
        Files.newDirectoryStream(dir).use { stream ->
            return stream.map { p ->
                val isDir = Files.isDirectory(p)
                Entry(
                    path = p,
                    name = p.name,
                    isDirectory = isDir,
                    // 目录大小没有便宜的定义（递归统计在大目录上会卡住界面），
                    // 直接给 -1 让 UI 显示成「—」，而不是显示一个 4096 之类
                    // 让人误解为"这个目录只有 4KB"的数字
                    sizeBytes = if (isDir) -1L else runCatching { Files.size(p) }.getOrDefault(-1L),
                )
            }.sortedWith(compareByDescending<Entry> { it.isDirectory }.thenBy { it.name.lowercase() })
        }
    }

    fun createDirectory(parent: Path, name: String): Path {
        val target = resolveInside(parent, name)
        // createDirectory 而非 createDirectories：后者对已存在的目录静默成功，
        // 用户点了"新建"却什么都没发生，还以为成功了
        return Files.createDirectory(target)
    }

    fun rename(target: Path, newName: String) {
        require(target.normalize().startsWith(ROOT)) { "路径越出用户目录" }
        val parent = target.parent ?: throw IOException("无法重命名根目录")
        val dest = resolveInside(parent, newName)
        // 不加 REPLACE_EXISTING：重命名成一个已存在的名字会静默覆盖掉那个文件，
        // 那是数据丢失。让它以 FileAlreadyExistsException 失败，UI 展示出来
        Files.move(target, dest, StandardCopyOption.ATOMIC_MOVE)
    }

    /** 递归删除。目录非空时也会删干净 —— UI 负责在此之前确认 */
    fun delete(target: Path) {
        require(target.normalize().startsWith(ROOT)) { "路径越出用户目录" }
        require(target != ROOT) { "不能删除用户目录本身" }
        if (Files.isDirectory(target)) {
            Files.walk(target).use { stream ->
                // 逆序：先删子项再删自己
                stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        } else {
            Files.deleteIfExists(target)
        }
    }

    /** 供面包屑用：把绝对路径拆成相对 [ROOT] 的各段 */
    fun breadcrumb(dir: Path): List<Pair<String, Path>> {
        val rel = ROOT.relativize(dir.normalize())
        val out = mutableListOf("文件" to ROOT)
        var cur = ROOT
        for (seg in rel) {
            if (seg.toString().isEmpty()) continue
            cur = cur.resolve(seg)
            out += seg.toString() to cur
        }
        return out
    }

    fun humanSize(bytes: Long): String = when {
        bytes < 0 -> "—"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
        else -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
    }
}
