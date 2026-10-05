package top.qiuli55.flux.mobile.data

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
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
    @SerialName("updated_at") val updatedAt: String? = null,
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
    @SerialName("resolved_at") val resolvedAt: String? = null,
)

@Serializable
data class TaskMessage(
    val id: String = "",
    @SerialName("task_id") val taskId: String = "",
    val seq: Int = 0,
    val role: String = "",
    val kind: String = "text",
    val content: String = "",
    val payload: JsonElement? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class Task(
    val id: String = "",
    val description: String = "",
    val status: String = "",
    val priority: Int = 100,
    @SerialName("agent_id") val agentId: String? = null,
    @SerialName("project_id") val projectId: String? = null,
    val result: JsonElement? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("decision_mode") val decisionMode: String = "auto",
    val confirmation: Confirmation? = null,
    val decisions: List<Decision> = emptyList(),
    @SerialName("pending_decision") val pendingDecision: Decision? = null,
    @SerialName("run_id") val runId: String? = null,
)

@Serializable
data class Run(
    val runId: String = "",
    @SerialName("session_id") val sessionId: String = "",
    @SerialName("task_id") val taskId: String? = null,
    @SerialName("agent_id") val agentId: String? = null,
    val instruction: String = "",
    val status: String = "",
    val pid: Int? = null,
    val pgid: Int? = null,
    @SerialName("cancel_requested") val cancelRequested: Boolean = false,
    @SerialName("timeout_kind") val timeoutKind: String? = null,
    val error: String? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("finished_at") val finishedAt: String? = null,
    @SerialName("last_state_change_at") val lastStateChangeAt: String? = null,
    @SerialName("last_heartbeat_at") val lastHeartbeatAt: String? = null,
    @SerialName("last_output_at") val lastOutputAt: String? = null,
    @SerialName("last_mcp_activity_at") val lastMcpActivityAt: String? = null,
    @SerialName("last_event") val lastEvent: JsonElement? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class Change(
    val id: String = "",
    @SerialName("project_id") val projectId: String? = null,
    @SerialName("task_id") val taskId: String? = null,
    @SerialName("group_id") val groupId: String? = null,
    @SerialName("file_path") val filePath: String = "",
    val kind: String = "modify",
    @SerialName("original_hash") val originalHash: String = "",
    @SerialName("original_content") val originalContent: String = "",
    @SerialName("proposed_content") val proposedContent: String? = null,
    val diff: String = "",
    @SerialName("added_lines") val addedLines: Int = 0,
    @SerialName("removed_lines") val removedLines: Int = 0,
    val hunks: Int = 0,
    val reason: String? = null,
    val summary: String? = null,
    @SerialName("agent_source") val agentSource: String? = null,
    val status: String = "pending",
    @SerialName("backup_path") val backupPath: String? = null,
    @SerialName("apply_error") val applyError: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("expired_reason") val expiredReason: String? = null,
    @SerialName("recovery_resolution") val recoveryResolution: String? = null,
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
    @SerialName("run_id") val runId: String? = null,
    @SerialName("workspace_root") val workspaceRoot: String? = null,
    val status: String = "active",
    @SerialName("next_seq") val nextSeq: Int = 1,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("finished_at") val finishedAt: String? = null,
)

@Serializable
data class TerminalEvent(
    val id: String = "",
    @SerialName("session_id") val sessionId: String = "",
    val seq: Int = 0,
    val kind: String = "",
    val source: String = "",
    val command: String? = null,
    val chunk: String? = null,
    @SerialName("exit_code") val exitCode: Int? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

class FluxApiException(
    val code: String,
    override val message: String,
) : Exception(message)
