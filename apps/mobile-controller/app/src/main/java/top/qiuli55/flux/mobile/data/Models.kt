package top.qiuli55.flux.mobile.data

import kotlinx.serialization.JsonElement
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class ConnectionConfig(
    val baseUrl: String = DEFAULT_BASE_URL,
    val token: String = "",
) {
    val isComplete: Boolean
        get() = baseUrl.isNotBlank() && token.isNotBlank()

    companion object {
        const val DEFAULT_BASE_URL = "https://flux.qiuli55.top/mobile"
    }
}

@Serializable
data class Envelope<T>(
    val success: Boolean = false,
    val code: String = "",
    val message: String = "",
    val data: T? = null,
    val metadata: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class HealthData(
    val status: String = "",
    val app: String = "",
    val env: String = "",
)

@Serializable
data class DshStatus(
    val enabled: Boolean = false,
    val provider: String? = null,
    val model: String? = null,
    val workspace: String? = null,
)

@Serializable
data class AgentSpec(
    val name: String = "",
    val role: String = "",
    val description: String = "",
    val skills: List<String> = emptyList(),
    val tools: List<String> = emptyList(),
    val permissions: List<String> = emptyList(),
)

@Serializable
data class Agent(
    val id: String = "",
    val state: String = "",
    val spec: AgentSpec? = null,
)

@Serializable
data class ConfirmationItem(
    val label: String = "",
    val value: String = "",
)

@Serializable
data class Confirmation(
    val items: List<ConfirmationItem> = emptyList(),
    val actor: String? = null,
    val updatedAt: String? = null,
)

@Serializable
data class DecisionOption(
    val label: String = "",
    val description: String? = null,
    val impact: String? = null,
    val recommended: Boolean = false,
)

@Serializable
data class Decision(
    val id: String? = null,
    val question: String? = null,
    val status: String? = null,
    val options: List<DecisionOption> = emptyList(),
    val context: String? = null,
    val recommendation: String? = null,
    val chosen: String? = null,
    val note: String? = null,
    val resolvedAt: String? = null,
)

@Serializable
data class TaskMessage(
    val id: String = "",
    val taskId: String = "",
    val seq: Int = 0,
    val role: String = "",
    val kind: String = "text",
    val content: String = "",
    val payload: JsonElement? = null,
    val createdAt: String? = null,
)

@Serializable
data class Task(
    val id: String = "",
    val description: String = "",
    val status: String = "",
    val priority: Int = 100,
    val agentId: String? = null,
    val projectId: String? = null,
    val result: JsonElement? = null,
    val createdAt: String? = null,
    val decisionMode: String = "auto",
    val confirmation: Confirmation? = null,
    val decisions: List<Decision> = emptyList(),
    val pendingDecision: Decision? = null,
    val runId: String? = null,
)

@Serializable
data class Run(
    val runId: String = "",
    val sessionId: String = "",
    val taskId: String? = null,
    val agentId: String? = null,
    val instruction: String = "",
    val status: String = "",
    val pid: Int? = null,
    val pgid: Int? = null,
    val cancelRequested: Boolean = false,
    val timeoutKind: String? = null,
    val error: String? = null,
    val finishReason: String? = null,
    val startedAt: String? = null,
    val finishedAt: String? = null,
    val lastStateChangeAt: String? = null,
    val lastHeartbeatAt: String? = null,
    val lastOutputAt: String? = null,
    val lastMcpActivityAt: String? = null,
    val lastEvent: JsonElement? = null,
    val createdAt: String? = null,
)

@Serializable
data class Change(
    val id: String = "",
    val projectId: String? = null,
    val taskId: String? = null,
    val groupId: String? = null,
    val filePath: String = "",
    val kind: String = "modify",
    val originalHash: String = "",
    val originalContent: String = "",
    val proposedContent: String? = null,
    val diff: String = "",
    val addedLines: Int = 0,
    val removedLines: Int = 0,
    val hunks: Int = 0,
    val reason: String? = null,
    val summary: String? = null,
    val agentSource: String? = null,
    val status: String = "pending",
    val backupPath: String? = null,
    val applyError: String? = null,
    val expiresAt: String? = null,
    val expiredReason: String? = null,
    val recoveryResolution: String? = null,
) {
    val kindLabel: String
        get() = when (kind) {
            "create" -> "新建"
            "modify" -> "修改"
            "delete" -> "删除"
            else -> kind
        }
}

@Serializable
data class TaskReplyOutcome(
    val task: Task = Task(),
    val messages: List<TaskMessage> = emptyList(),
)

@Serializable
data class TaskStartOutcome(
    val task: Task = Task(),
    val run: Run = Run(),
    val message: TaskMessage = TaskMessage(),
)

@Serializable
data class TerminalSession(
    val id: String = "",
    val runId: String? = null,
    val workspaceRoot: String? = null,
    val status: String = "active",
    val nextSeq: Int = 1,
    val createdAt: String? = null,
    val finishedAt: String? = null,
)

@Serializable
data class TerminalEvent(
    val id: String = "",
    val sessionId: String = "",
    val seq: Int = 0,
    val kind: String = "",
    val source: String = "",
    val command: String? = null,
    val chunk: String? = null,
    val exitCode: Int? = null,
    val createdAt: String? = null,
)

class FluxApiException(
    val code: String,
    override val message: String,
) : Exception(message)
