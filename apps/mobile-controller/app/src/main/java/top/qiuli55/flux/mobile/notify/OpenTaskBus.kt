package top.qiuli55.flux.mobile.notify

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

/** 通知点击排队的一条回跳：等主界面就绪（登录 / 选设备之后）再消费。 */
data class PendingOpenTask(
    val id: Long,
    val taskId: String,
    val reason: OpenTaskReason,
)

/**
 * 通知点击入口。
 *
 * 与系统分享一样用 StateFlow 而不是 SharedFlow：冷启动时 Compose 还没开始收集，
 * 点击也必须等主界面准备好后再跳转，不能丢。登录墙期间先排队。
 */
object OpenTaskBus {
    private val _pending = MutableStateFlow<List<PendingOpenTask>>(emptyList())
    val pending: StateFlow<List<PendingOpenTask>> = _pending.asStateFlow()
    private val nextId = AtomicLong(0L)

    fun publish(taskId: String, reason: OpenTaskReason) {
        val id = taskId.trim()
        if (id.isEmpty()) return
        _pending.update { it + PendingOpenTask(nextId.incrementAndGet(), id, reason) }
    }

    fun consume(ids: Set<Long>) {
        if (ids.isEmpty()) return
        _pending.update { list -> list.filterNot { it.id in ids } }
    }

    /** 只给测试用：避免单例在用例之间串数据。 */
    internal fun resetForTest() {
        _pending.value = emptyList()
    }
}
