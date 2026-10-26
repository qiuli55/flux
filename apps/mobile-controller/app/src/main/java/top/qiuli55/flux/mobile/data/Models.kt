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

    /** 账号模式：baseUrl 指向云端设备中转 `.../api/devices/{id}/p`。 */
    val isRelay: Boolean
        get() = baseUrl.contains("/api/devices/")

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
data class KeyModelOption(
    @SerialName("key_id") val keyId: String = "",
    @SerialName("key_name") val keyName: String = "",
    val provider: String = "",
    @SerialName("provider_title") val providerTitle: String = "",
    val model: String = "",
)

@Serializable
data class AgentSpec(
    val name: String = "",
    val role: String = "",
    val description: String = "",
    val skills: List<String> = emptyList(),
    val tools: List<String> = emptyList(),
    val permissions: List<String> = emptyList(),
    /** 执行该 Agent 的 runtime（dsh / codex / opencode）。 */
    val runtime: String = "",
    /** 是否原生逐 token 流式；false 时界面标注「非逐字流式」（Agent 仍可正常使用）。 */
    @SerialName("native_streaming") val nativeStreaming: Boolean = true,
    /** 该 runtime 的默认模型；任务未覆盖时实际用它。 */
    val model: String = "",
    /** 该 runtime 支持的模型（移动端「切换模型」下拉的数据源）。 */
    val models: List<String> = emptyList(),
    /** 密钥库中该 runtime 可用的模型。 */
    @SerialName("key_models") val keyModels: List<KeyModelOption> = emptyList(),
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
    /** 对话权限模式：plan = 只读+计划文档 / normal = 高风险命令审批 / full = 跳过逐步审批。 */
    @SerialName("permission_mode") val permissionMode: String = "normal",
    val confirmation: Confirmation? = null,
    val decisions: List<Decision> = emptyList(),
    @SerialName("pending_decision") val pendingDecision: Decision? = null,
    @SerialName("run_id") val runId: String? = null,
    /** 任务级模型覆盖；null = 用所属 Agent 的默认模型。 */
    val model: String? = null,
    /** 任务级模型来自哪条 key 库条目；null = 原生模型。 */
    @SerialName("api_key_id") val apiKeyId: String? = null,
)

/** 密钥库条目（GET /keys）：只有掩码，没有明文/密文。 */
@Serializable
data class ApiKeyEntry(
    val id: String = "",
    val name: String = "",
    val provider: String = "",
    @SerialName("base_url") val baseUrl: String = "",
    @SerialName("wire_api") val wireApi: String = "",
    val models: List<String> = emptyList(),
    @SerialName("key_hint") val keyHint: String = "",
    val enabled: Boolean = true,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
) {
    val providerTitle: String
        get() = when (provider) {
            "deepseek" -> "DeepSeek"
            "openai" -> "OpenAI"
            "openai_compatible" -> "OpenAI 兼容"
            "anthropic" -> "Anthropic"
            else -> provider
        }
}

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

/** 已上传的聊天附件：id 用于发送消息时回传，path 是服务端给 Agent 读的绝对路径。 */
@Serializable
data class UploadedAttachment(
    val id: String = "",
    val name: String = "",
    val size: Long = 0,
    val mime: String = "",
    val path: String = "",
) {
    companion object {
        /** 单个附件上限，与服务端一致。 */
        const val MAX_BYTES: Long = 50L * 1024 * 1024

        /** 一条消息最多带几个附件，与服务端一致。 */
        const val MAX_FILES: Int = 5
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
    /** 开始执行不再落「已开始执行」气泡，后端返回 null；执行过程走对话实时流。 */
    val message: TaskMessage? = null,
)

/** 一轮 Agent Run 的摘要：对话里的「任务耗时」入口与详情页概览。 */
@Serializable
data class TaskRunSummary(
    @SerialName("run_id") val runId: String = "",
    @SerialName("task_id") val taskId: String? = null,
    @SerialName("agent_id") val agentId: String? = null,
    val status: String = "",
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("finished_at") val finishedAt: String? = null,
    @SerialName("duration_ms") val durationMs: Long? = null,
    /** 运行过的命令条数（bash/shell/exec 类工具调用）。 */
    val commands: Int = 0,
    /** 读取过的文件数（按路径去重）。 */
    @SerialName("files_read") val filesRead: Int = 0,
    /** 改动过的文件数（write/edit/apply_patch，按路径去重）。 */
    @SerialName("files_changed") val filesChanged: Int = 0,
)

/** 一轮 Run 的轨迹项：思考段 / 命令卡 / 错误行（持久化后的历史回看用）。 */
@Serializable
data class RunTraceItem(
    val seq: Int = 0,
    val kind: String = "",
    /** command / read / write / other；思考与错误行为空。 */
    val category: String = "",
    val text: String = "",
    val name: String = "",
    val detail: String = "",
    val output: String = "",
    val status: String = "",
    @SerialName("call_id") val callId: String = "",
    val paths: List<String> = emptyList(),
)

/** 一轮 Run 的完整轨迹（概览 + 明细）。 */
@Serializable
data class RunTrace(
    @SerialName("run_id") val runId: String = "",
    val status: String = "",
    @SerialName("duration_ms") val durationMs: Long? = null,
    val commands: Int = 0,
    @SerialName("files_read") val filesRead: Int = 0,
    @SerialName("files_changed") val filesChanged: Int = 0,
    val items: List<RunTraceItem> = emptyList(),
)

/** 对话上下文占用：后端按「平台助手这一轮实际能带上的历史」算，不是模型标称窗口。 */
@Serializable
data class TaskContextUsage(
    val limit: Int = 0,
    @SerialName("message_count") val messageCount: Int = 0,
    @SerialName("used_messages") val usedMessages: Int = 0,
    @SerialName("used_chars") val usedChars: Int = 0,
    @SerialName("budget_chars") val budgetChars: Int = 0,
    val ratio: Double = 0.0,
    val percent: Int = 0,
    /** normal / warning / full */
    val level: String = "normal",
    @SerialName("message_chars") val messageChars: Int = 0,
)

/** 旧对话的前情摘要：新建对话时随首条消息带给后端。 */
@Serializable
data class HandoffSummary(
    val summary: String = "",
    @SerialName("from_task_id") val fromTaskId: String = "",
    val provider: String = "",
    val model: String = "",
    @SerialName("source_messages") val sourceMessages: Int = 0,
    val truncated: Boolean = false,
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

@Serializable
data class TaskStreamEvent(
    val seq: Int = 0,
    val type: String = "",
    val data: JsonObject = JsonObject(emptyMap()),
)

// ------------------------------------------------------------------ 设计工作室

/** 设计稿列表项（Luma Studio 的 summary，不含画板正文）。 */
@Serializable
data class DesignSummary(
    val id: String = "",
    val name: String = "",
    val project: String = "",
    @SerialName("project_id") val projectId: String? = null,
    val description: String = "",
    val status: String = "draft",
    val bg: String = "#F4F6F9",
    val version: Int = 0,
    @SerialName("updated_at") val updatedAt: String = "",
    @SerialName("screen_count") val screenCount: Int = 0,
    @SerialName("layer_count") val layerCount: Int = 0,
    val preview: DesignPreview? = null,
)

/** 列表缩略图：第一屏的尺寸与节点子集。 */
@Serializable
data class DesignPreview(
    val w: Float = 390f,
    val h: Float = 844f,
    val bg: String? = null,
    val nodes: List<DesignNode> = emptyList(),
)

/** 画板上的一个图层；子节点坐标相对父节点。 */
@Serializable
data class DesignNode(
    val id: String = "",
    val type: String = "",
    val name: String = "",
    val x: Float = 0f,
    val y: Float = 0f,
    val w: Float = 0f,
    val h: Float = 0f,
    val fill: String? = null,
    val color: String? = null,
    val text: String? = null,
    val fontSize: Float? = null,
    val fontWeight: Int? = null,
    val opacity: Float? = null,
    val radius: Float? = null,
    val stroke: String? = null,
    val lineHeight: Float? = null,
    val letterSpacing: Float? = null,
    val align: String? = null,
    val blur: Float? = null,
    val padLeft: Float? = null,
    val value: Float? = null,
    val track: String? = null,
    val on: Boolean? = null,
    val items: List<String> = emptyList(),
    val active: Int? = null,
    val children: List<DesignNode> = emptyList(),
)

@Serializable
data class DesignScreen(
    val id: String = "",
    val name: String = "",
    val x: Float = 0f,
    val y: Float = 0f,
    val w: Float = 390f,
    val h: Float = 844f,
    val nodes: List<DesignNode> = emptyList(),
)

/** 整份设计稿；保存时带 [version] 做乐观锁。 */
@Serializable
data class DesignDocument(
    val id: String = "",
    val name: String = "",
    val project: String = "",
    @SerialName("project_id") val projectId: String? = null,
    val description: String = "",
    val status: String = "draft",
    val bg: String = "#F4F6F9",
    val version: Int = 0,
    @SerialName("updated_at") val updatedAt: String = "",
    val screens: List<DesignScreen> = emptyList(),
) {
    val layerCount: Int
        get() = screens.sumOf { screen -> countLayers(screen.nodes) }
}

private fun countLayers(nodes: List<DesignNode>): Int =
    nodes.size + nodes.sumOf { countLayers(it.children) }

/** AI 对单个组件的修改：每条 op 的 set 是一组属性补丁。 */
@Serializable
data class DesignAiOp(
    val set: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class DesignAiResult(
    val reply: String = "",
    val ops: List<DesignAiOp> = emptyList(),
    val model: String = "",
)

/** 设计稿渲染成的单文件 HTML；可以在 WebView 里离线加载并真实交互。 */
@Serializable
data class DesignWebPreview(
    @SerialName("design_id") val designId: String = "",
    val name: String = "",
    val version: Int = 0,
    @SerialName("screen_count") val screenCount: Int = 0,
    @SerialName("size_bytes") val sizeBytes: Int = 0,
    val html: String = "",
)

/** ChatGPT 账号（Codex 订阅通道）的登录态；后端不下发任何令牌。 */
@Serializable
data class CodexAccountStatus(
    @SerialName("logged_in") val loggedIn: Boolean = false,
    @SerialName("auth_mode") val authMode: String? = null,
    val email: String? = null,
    val plan: String? = null,
    @SerialName("account_id") val accountId: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    val home: String = "",
    val binary: String = "",
    @SerialName("binary_found") val binaryFound: Boolean = false,
    @SerialName("pending_login") val pendingLogin: CodexLoginSession? = null,
)

/** 设备码登录会话：url + code 给用户授权，status 供轮询。 */
@Serializable
data class CodexLoginSession(
    @SerialName("login_id") val loginId: String = "",
    /** starting / pending / success / failed / cancelled */
    val status: String = "starting",
    val url: String = "",
    val code: String = "",
    val message: String = "",
    @SerialName("expires_in") val expiresIn: Int = 0,
) {
    val isPending: Boolean get() = status == "starting" || status == "pending"
}

@Serializable
data class CodexLogoutResult(
    val removed: Boolean = false,
    val status: CodexAccountStatus = CodexAccountStatus(),
)

class FluxApiException(
    val code: String,
    override val message: String,
) : Exception(message)
