package com.nervus.sysui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * 一次轮询取数的结果。
 *
 * 刻意把「加载中」「有数据」「出错」做成三个并存的字段而不是密封类的三个分支：
 * 刷新时旧数据仍应留在屏幕上（否则每 3 秒闪一次空白），出错时也一样——
 * 一次网络抖动不该把已经显示的列表清空。
 */
data class Polled<T>(
    val value: T,
    val loading: Boolean = false,
    val error: String? = null,
)

/**
 * 每隔 [intervalMs] 调一次 [fetch]，把结果暴露成 Compose 状态。
 *
 * ## 为什么是轮询而不是订阅
 *
 * nervud 目前【没有实现】Subscribe：`ipc/conn.go` 的 handleReady 把
 * Subscribe / Unsubscribe / Cancel 归入 unsupported，收到即**关闭连接**。
 * SDK 里 `NervusApp.subscribe()` 和 SubscriptionManager 是存在的，但调用它们
 * 的后果是连接莫名断掉——症状离原因很远。
 *
 * 所以系统 UI 一律轮询。等内核补上订阅后，把本文件换成事件流即可，
 * 调用方的写法（一个 State）不用动。
 *
 * [fetch] 在 IO 线程上执行，不会卡住渲染。
 */
/**
 * @param key 取数依赖的外部状态。它一变就【立刻重启】轮询循环。
 *
 * 没有它会有一个不明显的 bug：`LaunchedEffect` 只在 key 变化时重启，而
 * [fetch] 是每次重组新建的闭包 —— 循环里跑的永远是【第一次】那个闭包，捕获
 * 的也是第一次的外部状态。表现是"切换目录后列表纹丝不动"，而且看起来像
 * 缓存问题，很难往闭包上想。凡是 fetch 里读了外部变量，就必须把它传进 key。
 */
@Composable
fun <T> rememberPolled(
    initial: T,
    intervalMs: Long = 3000,
    key: Any? = Unit,
    fetch: suspend () -> T,
): State<Polled<T>> {
    val state = remember { mutableStateOf(Polled(initial, loading = true)) }

    LaunchedEffect(intervalMs, key) {
        while (isActive) {
            state.value = state.value.copy(loading = true)
            try {
                val fresh = withContext(Dispatchers.IO) { fetch() }
                state.value = Polled(fresh, loading = false, error = null)
            } catch (e: Exception) {
                // 保留上一次的 value：刷新失败不该让已经显示的内容消失
                state.value = state.value.copy(
                    loading = false,
                    error = e.message ?: e::class.simpleName ?: "unknown error",
                )
            }
            delay(intervalMs)
        }
    }

    return state
}
