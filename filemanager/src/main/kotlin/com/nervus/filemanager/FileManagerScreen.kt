package com.nervus.filemanager

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nervus.sysui.rememberPolled
import java.nio.file.Path

@Composable
fun FileManagerScreen(storageReady: Boolean) {
    var dir by remember { mutableStateOf(UserStorage.ROOT) }
    var error by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<UserStorage.Entry?>(null) }
    var renaming by remember { mutableStateOf<UserStorage.Entry?>(null) }
    var creatingFolder by remember { mutableStateOf(false) }
    // 每次改动后 +1，逼 rememberPolled 立刻重取。轮询兜底（别的应用也可能在
    // 写同一个共享目录），但自己的操作不该等到下一轮才可见
    var revision by remember { mutableStateOf(0) }

    val entries by rememberPolled<List<UserStorage.Entry>>(
        initial = emptyList(),
        intervalMs = 2000,
        // 目录变了、或自己刚改过内容，都要立刻重取而不是等下一轮
        key = dir to revision,
    ) {
        if (!storageReady) emptyList() else UserStorage.list(dir)
    }

    fun act(block: () -> Unit) {
        error = null
        try {
            block()
            revision++
        } catch (e: Exception) {
            // 原样展示：FileAlreadyExistsException、AccessDeniedException 这些
            // 名字本身就说明了问题，改写成"操作失败"反而丢信息
            error = "${e::class.simpleName}: ${e.message}"
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, tonalElevation = 3.dp) {
                Column {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Breadcrumb(dir) { dir = it }
                        Button(
                            onClick = { creatingFolder = true },
                            enabled = storageReady,
                        ) { Text("新建文件夹") }
                    }
                }
            }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when {
                !storageReady -> Message(
                    "用户目录不可写",
                    "${UserStorage.ROOT}\n" +
                        "检查两件事：manifest 是否声明了 perm.storage.user；" +
                        "以及 nervud 的 preflight 是否建好了这个目录（01777）。",
                )

                entries.value.isEmpty() && entries.error != null ->
                    Message("读不了这个目录", entries.error)

                entries.value.isEmpty() ->
                    Message("这里是空的", "用右上角新建一个文件夹")

                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(entries.value, key = { it.path.toString() }) { entry ->
                        ListItem(
                            leadingContent = { TypeGlyph(entry.isDirectory) },
                            headlineContent = { Text(entry.name) },
                            supportingContent = {
                                Text(
                                    if (entry.isDirectory) "文件夹"
                                    else UserStorage.humanSize(entry.sizeBytes),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            },
                            trailingContent = {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    TextButton(onClick = { renaming = entry }) { Text("重命名") }
                                    TextButton(onClick = { pendingDelete = entry }) { Text("删除") }
                                }
                            },
                            modifier = Modifier.clickable(enabled = entry.isDirectory) {
                                dir = entry.path
                            },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }

            error?.let { msg ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp).fillMaxWidth(),
                ) {
                    Text(
                        msg,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }

    if (creatingFolder) {
        NameDialog(
            title = "新建文件夹",
            initial = "",
            confirmLabel = "创建",
            onDismiss = { creatingFolder = false },
            onConfirm = { name ->
                creatingFolder = false
                act { UserStorage.createDirectory(dir, name) }
            },
        )
    }

    renaming?.let { entry ->
        NameDialog(
            title = "重命名",
            initial = entry.name,
            confirmLabel = "确定",
            onDismiss = { renaming = null },
            onConfirm = { name ->
                renaming = null
                act { UserStorage.rename(entry.path, name) }
            },
        )
    }

    pendingDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除 ${entry.name}？") },
            text = {
                Text(
                    if (entry.isDirectory) "这个文件夹及其全部内容都会被删除，无法撤销。"
                    else "此操作不可撤销。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    act { UserStorage.delete(entry.path) }
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun Breadcrumb(dir: Path, onNavigate: (Path) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        UserStorage.breadcrumb(dir).forEachIndexed { i, (label, path) ->
            if (i > 0) {
                Text(
                    " / ",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Text(
                label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (path == dir) FontWeight.SemiBold else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable(enabled = path != dir) { onNavigate(path) },
            )
        }
    }
}

/**
 * 文件/文件夹的图标位。
 *
 * 用色块而不是图标字体：materialIconsExtended 单独 37MB（见 ui-common 的说明），
 * 为两个图标背这个体积不值得。
 */
@Composable
private fun TypeGlyph(isDirectory: Boolean) {
    Box(
        Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(
            if (isDirectory) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (isDirectory) "▣" else "▤",
            color = if (isDirectory) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NameDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    // 名字里出现 '/' 会让 resolve 跨出当前目录；空名字则会解析成目录自身。
    // 两者都在 UserStorage 里会被挡下，但在这里就禁掉能给出更直接的反馈
    val valid = text.isNotBlank() && !text.contains('/') && text != "." && text != ".."

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    isError = text.isNotEmpty() && !valid,
                )
                if (text.isNotEmpty() && !valid) {
                    Text(
                        "名字不能为空、不能含 /、不能是 . 或 ..",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = valid) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun Message(title: String, detail: String?) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            detail?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, start = 32.dp, end = 32.dp),
                )
            }
        }
    }
}
