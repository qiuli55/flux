package top.qiuli55.flux.mobile.notify

import top.qiuli55.flux.mobile.data.NotificationPrefs
import top.qiuli55.flux.mobile.data.Task
import top.qiuli55.flux.mobile.ui.v2.V2TaskStatus
import top.qiuli55.flux.mobile.ui.v2.isFinished
import top.qiuli55.flux.mobile.ui.v2.toV2TaskStatus
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 两类系统提醒：决策到达、任务结束。 */
enum class AlertKind { DECISION, FINISHED }

/** 一条系统通知的标题与正文（全部 zh-CN）。 */
data class NotificationContent(val title: String, val body: String)

// ------------------------------------------------------------------ 去重

/**
 * 同一决策 id / 同一轮 run 的完成只发一次。
 *
 * 服务可能在进程重建后重新开始轮询，所以键集合可以带初始值（服务从
 * SharedPreferences 恢复），避免重启后把同一条提醒又弹一遍。
 */
class AlertDeduplicator(
    private val limit: Int = 200,
    initial: Collection<String> = emptyList(),
) {
    private val seen = LinkedHashSet<String>().apply { addAll(initial) }

    /** 第一次见到返回 true；重复键返回 false。 */
    fun firstTime(key: String): Boolean {
        if (!seen.add(key)) return false
        while (seen.size > limit) seen.remove(seen.first())
        return true
    }

    /** 发送失败时撤销，等权限恢复后还能再试。 */
    fun forget(key: String) {
        seen.remove(key)
    }

    fun contains(key: String): Boolean = key in seen

    fun snapshot(): List<String> = seen.toList()
}

// ------------------------------------------------------------------ 该不该发

/**
 * 开关关掉就不发；用户此刻正在前台看这条对话时也不弹，
 * 在后台 / 别的页面 / 别的对话仍然照发。
 */
fun shouldSendAlert(
    kind: AlertKind,
    prefs: NotificationPrefs,
    taskId: String,
    appForeground: Boolean,
    viewedTaskId: String?,
): Boolean {
    val enabled = when (kind) {
        AlertKind.DECISION -> prefs.decisionAlerts
        AlertKind.FINISHED -> prefs.taskAlerts
    }
    if (!enabled) return false
    return !(appForeground && viewedTaskId == taskId)
}

// ------------------------------------------------------------------ 去重键

/**
 * 决策去重键：优先用后端决策 id；旧后端只给 waiting 状态时退回 run / 状态。
 * 不在等待决策时返回 null。
 */
fun decisionKey(task: Task): String? {
    val decision = task.pendingDecision
    if (decision != null) {
        val identity = decision.id?.takeIf { it.isNotBlank() }
            ?: decision.question?.takeIf { it.isNotBlank() }?.take(80)
            ?: "pending"
        return "decision:${task.id}:$identity"
    }
    if (task.status == "waiting_for_user_decision") {
        return "decision:${task.id}:${task.runId ?: "waiting"}"
    }
    return null
}

/** 完成去重键：同一轮 run 只提醒一次；没有 runId 时退回任务 id + 终态。 */
fun finishedKey(task: Task): String? {
    if (!task.status.toV2TaskStatus().isFinished) return null
    return "finished:${task.id}:${task.runId ?: task.status}"
}

// ------------------------------------------------------------------ 文案

/** 对话标题：描述的首个非空行，过长截断。 */
internal fun taskTitle(task: Task): String =
    task.description.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotEmpty() }
        ?.take(40)
        ?: "未命名任务"

private fun taskSummary(task: Task): String? {
    val element = task.result ?: return null
    return runCatching {
        when (element) {
            is JsonPrimitive -> element.content
            is JsonObject -> (element["summary"] ?: element["message"] ?: element["text"])
                ?.let { it as? JsonPrimitive }
                ?.content
            else -> null
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }?.take(120)
}

/** 决策通知：标题带对话名，正文用 question / context，不要只写「有新决策」。 */
fun decisionContent(task: Task): NotificationContent {
    val decision = task.pendingDecision
    val body = decision?.question?.takeIf { it.isNotBlank() }
        ?: decision?.context?.takeIf { it.isNotBlank() }
        ?: decision?.recommendation?.takeIf { it.isNotBlank() }
        ?: "任务在等你确认后才能继续"
    return NotificationContent(
        title = "等你决策：${taskTitle(task)}",
        body = body.take(140),
    )
}

/** 完成通知：区分已完成 / 执行失败 / 已取消，并带上对话标题。 */
fun finishedContent(task: Task): NotificationContent {
    val title = taskTitle(task)
    return when (task.status.toV2TaskStatus()) {
        V2TaskStatus.DONE -> NotificationContent(
            title = "任务已完成：$title",
            body = taskSummary(task) ?: "点击查看结果",
        )
        V2TaskStatus.FAILED -> NotificationContent(
            title = "任务执行失败：$title",
            body = taskSummary(task) ?: "点击查看失败原因",
        )
        V2TaskStatus.CANCELED -> NotificationContent(
            title = "任务已取消：$title",
            body = taskSummary(task) ?: "点击查看任务详情",
        )
        else -> NotificationContent(
            title = "任务已结束：$title",
            body = taskSummary(task) ?: "点击查看任务详情",
        )
    }
}
