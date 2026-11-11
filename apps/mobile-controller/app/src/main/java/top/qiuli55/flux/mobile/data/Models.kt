package top.qiuli55.flux.mobile.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
    /** 执行中切换 Agent 的排队目标：非空表示本轮结束后自动换人。 */
    @SerialName("pending_agent_id") val pendingAgentId: String? = null,
    /** 对话来源：chat = 首页普通对话；assistant = 助理页的助理对话（不进首页列表）。 */
    val origin: String = "chat",
    /** bot 展示名；为空时退回 description。 */
    @SerialName("bot_name") val botName: String? = null,
    /** bot 头像形状（14 选一，顺序与后端 avatar.py 一致）。 */
    @SerialName("avatar_shape") val avatarShape: String = "circle",
    /** bot 头像颜色（12 选一，顺序与后端 avatar.py 一致）。 */
    @SerialName("avatar_color") val avatarColor: String = "violet",
) {
    /** 列表/群聊里展示的 bot 名称。 */
    val displayName: String
        get() = botName?.takeIf { it.isNotBlank() }
            ?: description.take(24).ifBlank { "未命名 Bot" }
}

/* ---------------- 内部群聊（/api/v1/groups） ---------------- */

/** 群成员 = 一个对话（bot）的公开档案。 */
@Serializable
data class ChatGroupMember(
    @SerialName("task_id") val taskId: String = "",
    val name: String = "",
    val description: String = "",
    @SerialName("avatar_shape") val avatarShape: String = "circle",
    @SerialName("avatar_color") val avatarColor: String = "violet",
    val status: String = "",
    /** admin = 群主（每轮先发言并负责拉人）；member = 普通成员。 */
    val role: String = "member",
    /** 该 bot 在群里的模型覆盖；null = 沿用默认模型。 */
    val model: String? = null,
    @SerialName("api_key_id") val apiKeyId: String? = null,
    /** plan = 只读+计划 / normal = 高风险审批 / full = 跳过逐步审批。 */
    @SerialName("permission_mode") val permissionMode: String = "normal",
) {
    val isAdmin: Boolean get() = role == "admin"
}

/** 分层记忆：scope=group 为本群，scope=team 为跨群共享。 */
@Serializable
data class ChatGroupMemory(
    @SerialName("group_id") val groupId: String? = null,
    val scope: String = "group",
    val content: String = "",
    @SerialName("up_to_seq") val upToSeq: Int = 0,
    @SerialName("updated_at") val updatedAt: String? = null,
)

/** GET /groups/{id}/memory 的返回；都没压缩过时两项为 null。 */
@Serializable
data class ChatGroupMemoryState(
    val group: ChatGroupMemory? = null,
    val team: ChatGroupMemory? = null,
)

/** 一个内部群。 */
@Serializable
data class ChatGroup(
    val id: String = "",
    val name: String = "",
    @SerialName("project_id") val projectId: String? = null,
    val description: String? = null,
    /** 每轮最多发言轮数闸门（1~5）。 */
    @SerialName("max_rounds") val maxRounds: Int = 2,
    val status: String = "",
    @SerialName("last_message_at") val lastMessageAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    /** 列表接口只给数量，members 为空数组。 */
    @SerialName("member_count") val memberCount: Int = 0,
    val members: List<ChatGroupMember> = emptyList(),
)

/** 一条群消息。 */
@Serializable
data class ChatGroupMessage(
    val id: String = "",
    @SerialName("group_id") val groupId: String = "",
    /** 群内自增游标，轮询用 after_seq。 */
    val seq: Int = 0,
    /** 发言的 bot；用户 / 系统消息为 null。 */
    @SerialName("task_id") val taskId: String? = null,
    @SerialName("author_name") val authorName: String = "",
    /** user / assistant / system。 */
    val role: String = "",
    val kind: String = "text",
    val content: String = "",
    val payload: JsonElement? = null,
    @SerialName("epoch_id") val epochId: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class ChatGroupMessagePage(
    val messages: List<ChatGroupMessage> = emptyList(),
    @SerialName("has_more") val hasMore: Boolean = false,
)

@Serializable
data class ChatGroupSendOutcome(
    val message: ChatGroupMessage = ChatGroupMessage(),
    @SerialName("epoch_id") val epochId: String = "",
    val mentioned: List<String> = emptyList(),
)

@Serializable
data class ChatGroupInterruptOutcome(
    val interrupted: Boolean = false,
    @SerialName("epoch_id") val epochId: String? = null,
)

/** 安全输入提交的返回：任务 + 落库后的密钥（只有掩码）+「已存储」回执消息。 */
@Serializable
data class SecureInputOutcome(
    val task: Task = Task(),
    val key: ApiKeyEntry? = null,
    val message: TaskMessage = TaskMessage(),
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

/** 保存后校验 key 的模型端点是否可用（POST /keys/{id}/probe）。 */
@Serializable
data class KeyProbeResult(
    val ok: Boolean = false,
    val skipped: Boolean = false,
    val provider: String = "",
    val model: String = "",
    @SerialName("base_url") val baseUrl: String = "",
    @SerialName("latency_ms") val latencyMs: Int = 0,
    val reply: String = "",
    val error: String = "",
    @SerialName("error_code") val errorCode: String = "",
    val detail: String = "",
)

/** 账号级用户长期记忆：云端权威，本机只做缓存与编辑。 */
@Serializable
data class UserMemoryEntry(
    val id: String = "",
    val content: String = "",
    val source: String = "user",
    val meta: Map<String, JsonElement> = emptyMap(),
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
) {
    /** 自动提炼（每轮结束由平台写入） vs 用户手动添加。 */
    val isAuto: Boolean get() = source == "auto"
}

/** 用户记忆设置：自动记录开关 + 云端同步就绪状态。 */
@Serializable
data class MemorySettings(
    @SerialName("auto_capture") val autoCapture: Boolean = true,
    @SerialName("cloud_sync") val cloudSync: Boolean = true,
    @SerialName("cloud_ready") val cloudReady: Boolean = false,
    @SerialName("cloud_error") val cloudError: String = "",
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
    /** 四角圆角：JSON 支持 number / [tl,tr,br,bl] / {tl,tr,br,bl|topLeft...}。 */
    @Serializable(with = DesignRadiusSerializer::class)
    val radius: DesignRadius? = null,
    /** 描边：字符串等价于 1px inside 实线；对象可带 width / align / dash / sides。 */
    @Serializable(with = DesignStrokeSerializer::class)
    val stroke: DesignStroke? = null,
    /** 外阴影（CSS box-shadow）。 */
    @Serializable(with = DesignShadowSerializer::class)
    val shadow: DesignShadow? = null,
    /** 内阴影（CSS inset box-shadow）。 */
    @Serializable(with = DesignShadowSerializer::class)
    val insetShadow: DesignShadow? = null,
    val lineHeight: Float? = null,
    val letterSpacing: Float? = null,
    /** 字族枚举：system / noto-sans-sc / noto-serif-sc / inter / mono（别名由 DesignSpec 归一）。 */
    val fontFamily: String? = null,
    /** 文字行数上限 1..64，缺省 3。 */
    val maxLines: Int? = null,
    /** 单行放不下时按 0.5 步长缩字号，最低 60%。 */
    val autoFit: Boolean? = null,
    val italic: Boolean? = null,
    val underline: Boolean? = null,
    val strikethrough: Boolean? = null,
    /** 文字渐变，语法与 fill 相同（linear-gradient(...)）。 */
    val textFill: String? = null,
    val textStroke: DesignTextStroke? = null,
    val textGlow: DesignTextEffect? = null,
    val textShadow: DesignTextEffect? = null,
    val align: String? = null,
    val blur: Float? = null,
    val padLeft: Float? = null,
    /** 绕自身中心旋转的角度（度，顺时针为正），缺省 0。 */
    val rotation: Float? = null,
    val value: Float? = null,
    val track: String? = null,
    val on: Boolean? = null,
    val items: List<String> = emptyList(),
    val active: Int? = null,
    /** 隐藏图层不参与渲染（与后端编译层的 `hidden` 剔除一致）。 */
    val hidden: Boolean? = null,
    val children: List<DesignNode> = emptyList(),
)

/** 文字描边；align 为 outside 时视觉宽度是 inside 的两倍（webkit 居中描边）。 */
@Serializable
data class DesignTextStroke(
    val width: Float? = null,
    val color: String? = null,
    val align: String? = null,
)

/** 文字发光/投影；glow 用 radius，shadow 用 dx/dy + blur。 */
@Serializable
data class DesignTextEffect(
    val dx: Float? = null,
    val dy: Float? = null,
    val blur: Float? = null,
    val radius: Float? = null,
    val intensity: Float? = null,
    val color: String? = null,
)

/**
 * 四角圆角，单位与节点一致。
 *
 * JSON 有四种写法：`12`、`[12,0,12,0]`、`{"tl":12,...}`、`{"topLeft":12,...}`，
 * 统一由 [DesignRadiusSerializer] 归一；未出现的角为 null，由 `DesignSpec.cornersOf`
 * 回落到该类型的默认圆角（与后端 `radius_of` 一致）。
 */
@Serializable
data class DesignRadius(
    val tl: Float? = null,
    val tr: Float? = null,
    val br: Float? = null,
    val bl: Float? = null,
) {
    val uniform: Boolean get() = tl != null && tl == tr && tr == br && br == bl

    companion object {
        fun all(value: Float): DesignRadius = DesignRadius(value, value, value, value)
    }
}

object DesignRadiusSerializer : JsonTransformingSerializer<DesignRadius>(DesignRadius.serializer()) {
    private val KEYS = listOf("tl", "tr", "br", "bl")

    private fun number(element: JsonElement?): Float? =
        (element as? JsonPrimitive)?.floatOrNull

    override fun transformDeserialize(element: JsonElement): JsonElement {
        if (element is JsonArray) {
            return buildJsonObject {
                KEYS.forEachIndexed { index, key ->
                    number(element.getOrNull(index))?.let { put(key, JsonPrimitive(it)) }
                }
            }
        }
        if (element is JsonObject) {
            fun corner(short: String, long: String): Float? =
                number(element[short]) ?: number(element[long])
            return buildJsonObject {
                corner("tl", "topLeft")?.let { put("tl", JsonPrimitive(it)) }
                corner("tr", "topRight")?.let { put("tr", JsonPrimitive(it)) }
                corner("br", "bottomRight")?.let { put("br", JsonPrimitive(it)) }
                corner("bl", "bottomLeft")?.let { put("bl", JsonPrimitive(it)) }
            }
        }
        val value = number(element) ?: return buildJsonObject { }
        return buildJsonObject { KEYS.forEach { put(it, JsonPrimitive(value)) } }
    }

    /** 四角一致时压成数字，保持历史 JSON 形状；否则输出短键对象（未设置的角省略）。 */
    override fun transformSerialize(element: JsonElement): JsonElement {
        val obj = element.jsonObject
        val corners = KEYS.map { number(obj[it]) }
        if (corners.all { it != null } && corners.distinct().size == 1) {
            return JsonPrimitive(corners[0] ?: 0f)
        }
        return buildJsonObject {
            KEYS.forEachIndexed { index, key ->
                corners[index]?.let { put(key, JsonPrimitive(it)) }
            }
        }
    }
}

/**
 * 节点描边；字符串形式等价于 `1px inside` 实线（与后端 `compile_stroke` 一致）。
 * [dash] 非空即虚线，[sides] 非空时只画这些边。
 */
@Serializable
data class DesignStroke(
    val color: String? = null,
    val width: Float = 1f,
    val align: String = "inside",
    val dash: List<Float> = emptyList(),
    val sides: List<String> = emptyList(),
)

object DesignStrokeSerializer : JsonTransformingSerializer<DesignStroke>(DesignStroke.serializer()) {
    private fun text(element: JsonElement?): String? = (element as? JsonPrimitive)?.content

    override fun transformDeserialize(element: JsonElement): JsonElement {
        if (element !is JsonObject) {
            val color = text(element) ?: return buildJsonObject { }
            return buildJsonObject { put("color", JsonPrimitive(color)) }
        }
        return buildJsonObject {
            text(element["color"])?.let { put("color", JsonPrimitive(it)) }
            (element["width"] as? JsonPrimitive)?.floatOrNull?.let { put("width", JsonPrimitive(it)) }
            text(element["align"])?.let { put("align", JsonPrimitive(it)) }
            (element["dash"] as? JsonArray)?.let { array ->
                put("dash", buildJsonArray {
                    array.forEach { item ->
                        (item as? JsonPrimitive)?.floatOrNull?.let { add(JsonPrimitive(it)) }
                    }
                })
            }
            (element["sides"] as? JsonArray)?.let { array ->
                put("sides", buildJsonArray {
                    array.forEach { item -> text(item)?.let { add(JsonPrimitive(it)) } }
                })
            }
        }
    }

    /** 无额外参数时压回字符串，保持历史 JSON 形状。 */
    override fun transformSerialize(element: JsonElement): JsonElement {
        val obj = element.jsonObject
        val color = text(obj["color"]) ?: return element
        val width = (obj["width"] as? JsonPrimitive)?.floatOrNull ?: 1f
        val align = text(obj["align"]) ?: "inside"
        val plain = width == 1f && align == "inside" &&
            (obj["dash"] as? JsonArray)?.isEmpty() != false &&
            (obj["sides"] as? JsonArray)?.isEmpty() != false
        return if (plain) JsonPrimitive(color) else element
    }
}

/** 外阴影 / 内阴影；[color] 缺省时按 `#000000@0.25`，与后端 `compile_shadow` 一致。 */
@Serializable
data class DesignShadow(
    val dx: Float = 0f,
    val dy: Float = 0f,
    val blur: Float = 0f,
    val color: String? = null,
    val inset: Boolean = false,
)

object DesignShadowSerializer : JsonTransformingSerializer<DesignShadow>(DesignShadow.serializer()) {
    override fun transformDeserialize(element: JsonElement): JsonElement {
        // 非对象一律按「透明阴影」处理：解析失败不能把整层节点丢掉。
        if (element !is JsonObject) {
            return buildJsonObject { put("color", JsonPrimitive("transparent")) }
        }
        return buildJsonObject {
            listOf("dx", "dy", "blur").forEach { key ->
                (element[key] as? JsonPrimitive)?.floatOrNull?.let { put(key, JsonPrimitive(it)) }
            }
            (element["color"] as? JsonPrimitive)?.let { put("color", JsonPrimitive(it.content)) }
            (element["inset"] as? JsonPrimitive)?.let {
                val on = it.content.trim().lowercase() in setOf("true", "1", "yes", "on")
                put("inset", JsonPrimitive(on))
            }
        }
    }
}

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

/** AI 对画布的一次修改：set 改属性，add/remove/reorder 调整结构。 */
@Serializable
data class DesignAiOp(
    val op: String = "set",
    val target: String? = null,
    val set: JsonObject = JsonObject(emptyMap()),
    val node: JsonObject? = null,
    val index: Int? = null,
)

@Serializable
data class DesignAiResult(
    val reply: String = "",
    val ops: List<DesignAiOp> = emptyList(),
    val model: String = "",
    val mode: String = "node",
)

/** 设计稿的一个历史版本（只带概要，正文按需单独取）。 */
@Serializable
data class DesignRevision(
    val version: Int = 0,
    @SerialName("updated_at") val updatedAt: String = "",
    val name: String = "",
    @SerialName("screen_count") val screenCount: Int = 0,
    @SerialName("layer_count") val layerCount: Int = 0,
    @SerialName("size_bytes") val sizeBytes: Long = 0,
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

/** GitHub 登录态；后端只下发展示字段与 scope，令牌永远不下发。 */
@Serializable
data class GitHubAccountStatus(
    @SerialName("logged_in") val loggedIn: Boolean = false,
    val login: String? = null,
    val name: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("token_kind") val tokenKind: String? = null,
    val scopes: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("device_flow_ready") val deviceFlowReady: Boolean = false,
    @SerialName("pending_login") val pendingLogin: GitHubLoginSession? = null,
)

/** GitHub 设备码登录会话：user_code + verification_uri 给用户授权，status 供轮询。 */
@Serializable
data class GitHubLoginSession(
    @SerialName("flow_id") val flowId: String = "",
    /** pending / success / failed / expired / cancelled */
    val status: String = "pending",
    @SerialName("user_code") val userCode: String = "",
    @SerialName("verification_uri") val verificationUri: String = "",
    val message: String = "",
    @SerialName("expires_in") val expiresIn: Int = 0,
) {
    val isPending: Boolean get() = status == "pending"
}

/** 一个 GitHub 仓库；私有仓库 isPrivate=true。 */
@Serializable
data class GitHubRepo(
    @SerialName("full_name") val fullName: String = "",
    val name: String = "",
    val owner: String? = null,
    @SerialName("private") val isPrivate: Boolean = false,
    val description: String? = null,
    @SerialName("default_branch") val defaultBranch: String = "main",
    val language: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("html_url") val htmlUrl: String? = null,
)

@Serializable
data class GitHubRepoPage(
    val repos: List<GitHubRepo> = emptyList(),
    val page: Int = 1,
    @SerialName("per_page") val perPage: Int = 50,
    val count: Int = 0,
)

@Serializable
data class GitHubLogoutResult(
    val removed: Boolean = false,
    val status: GitHubAccountStatus = GitHubAccountStatus(),
)

class FluxApiException(
    val code: String,
    override val message: String,
) : Exception(message)
