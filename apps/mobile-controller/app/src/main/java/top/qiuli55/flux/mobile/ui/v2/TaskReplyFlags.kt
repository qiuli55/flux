package top.qiuli55.flux.mobile.ui.v2

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import top.qiuli55.flux.mobile.data.TaskMessage

/**
 * 助手回复上由服务端写入的诚实标记。
 *
 * - `assistant_only`：这一轮没有启动电脑上的 Agent，回复来自 flux-server 的 TaskAssistant；
 * - `needs_execution`：模型明确表示这件事只能由电脑 Agent 实际执行（读写文件 / 跑命令）。
 *
 * 两个字段都只认布尔 true；缺字段、旧服务端、类型不符时一律按 false 处理，
 * 客户端不会凭猜测替用户启动 Agent。
 */
internal fun TaskMessage.payloadFlag(key: String): Boolean {
    val root = payload as? JsonObject ?: return false
    val value = root[key] as? JsonPrimitive ?: return false
    // 只认 JSON 布尔 true：字符串 "true" / 数字 1 都不算（避免旧服务端或脏数据误触发）
    return !value.isString && value.content == "true"
}

/** 这条助手消息是否明确标记为「服务端助手直接回复、未启动电脑 Agent」。 */
internal fun TaskMessage.assistantOnly(): Boolean = payloadFlag("assistant_only")

/** 这条助手消息是否明确要求交给电脑 Agent 实际执行。 */
internal fun TaskMessage.needsExecution(): Boolean = payloadFlag("needs_execution")

/** 协议里的执行计划步骤条数（模型没给 / 类型不符时为 0）。 */
internal fun TaskMessage.planStepCount(): Int {
    val root = payload as? JsonObject ?: return 0
    return (root["steps"] as? JsonArray)?.size ?: 0
}

/**
 * 这条助手回复是否在说「这事得实际去做」。
 *
 * 两个诚实信号：模型显式标记 `needs_execution`，或它按需求确认协议给出了执行计划
 * （纯问答时 prompt 要求 steps 为空）。任一成立就把同一句交给电脑 Agent，
 * 不让用户停在一条「我打算这么做」却没有下文的对话里。
 */
internal fun TaskMessage.requestsExecution(): Boolean =
    needsExecution() || planStepCount() > 0

private val ASSISTANT_ROLES = setOf("assistant", "agent", "ai")

/** 一轮回复里的最后一条助手消息：`sendMessage` / `continue(start=false)` 都返回它。 */
internal fun List<TaskMessage>.lastAssistantMessage(): TaskMessage? =
    lastOrNull { it.role.lowercase() in ASSISTANT_ROLES }

/** 这批消息里的助手回复是否要求实际执行（缺省 false，保守处理）。 */
internal fun List<TaskMessage>.assistantRequestsExecution(): Boolean =
    lastAssistantMessage()?.requestsExecution() == true
