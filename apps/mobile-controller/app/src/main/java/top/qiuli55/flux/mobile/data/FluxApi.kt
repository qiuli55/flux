package top.qiuli55.flux.mobile.data

import android.net.Uri
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.serializer
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import okio.BufferedSink
import okio.source
import java.io.InputStream
import java.net.URI
import java.util.concurrent.TimeUnit

class FluxApi(
    private val config: ConnectionConfig,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = true
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request().newBuilder().apply {
                if (config.token.isNotBlank()) {
                    header("Authorization", "Bearer ${config.token.trim()}")
                }
                header("Accept", "application/json")
            }.build()
            chain.proceed(request)
        }
        .build()

    // 终端命令可能运行数分钟甚至更久，不能沿用普通 API 的 120 秒读取超时。
    // 实时输出由 SSE 提供，HTTP 请求只等待服务端返回最终事件。
    private val terminalClient = client.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    // 附件上传是裸请求体，50MB 在手机网络下 30 秒写不完，单独放宽读写超时。
    private val uploadClient = client.newBuilder()
        .writeTimeout(180, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    private val sseFactory = EventSources.createFactory(
        client.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build(),
    )

    suspend fun health(): HealthData = get("api/v1/health")
    suspend fun dshStatus(): DshStatus = get("api/v1/dsh/status")
    suspend fun listAgents(): List<Agent> = get("api/v1/agents")
    suspend fun listTasks(): List<Task> = get("api/v1/tasks")

    /** 套餐额度：本地账本 + 最近一次官方快照；不主动抓官方，避免每次进首页都打外部接口。 */
    suspend fun billingOverview(): BillingOverview = get("api/v1/billing/plans")

    /** 主动对账：抓 OpenCode Go 官方额度 / 跑 arkcli，结果落到快照表。 */
    suspend fun refreshBilling(): JsonElement = post("api/v1/billing/refresh", buildJsonObject {})

    /** 官方 API 价目表（每 100 万 token，用于成本估算）。 */
    suspend fun billingPrices(): BillingPrices = get("api/v1/billing/prices")

    suspend fun createTask(
        description: String,
        agentId: String?,
        decisionMode: String,
        permissionMode: String = "normal",
        model: String? = null,
        keyId: String? = null,
        /** 从旧对话接手时的前情摘要；非空时后端会落成一条 kind=summary 的消息。 */
        summary: String? = null,
        summaryFrom: String? = null,
        /** 先上传、再随首条消息一起交给后端的附件 id（最多 5 个）。 */
        attachmentIds: List<String> = emptyList(),
        /** 对话来源：首页普通对话传 chat（默认），助理页传 assistant。 */
        origin: String = "chat",
    ): Task {
        val body = buildJsonObject {
            put("description", description.trim())
            if (!agentId.isNullOrBlank()) put("agent_id", agentId)
            put("decision_mode", decisionMode)
            put("permission_mode", permissionMode)
            if (!model.isNullOrBlank()) put("model", model)
            if (!keyId.isNullOrBlank()) put("key_id", keyId)
            if (!summary.isNullOrBlank()) {
                put("summary", summary.trim())
                if (!summaryFrom.isNullOrBlank()) put("summary_from", summaryFrom)
            }
            put("origin", origin)
            putAttachmentIds(attachmentIds)
        }
        return post("api/v1/tasks", body)
    }

    suspend fun getTask(taskId: String): Task = get("api/v1/tasks/$taskId")

    /** 对话上下文占用：用于「上下文快满」提醒。 */
    suspend fun getTaskContext(taskId: String): TaskContextUsage =
        get("api/v1/tasks/$taskId/context")

    /** 把这段对话压成前情摘要；新建对话时带上它，旧消息一条不搬。 */
    suspend fun createHandoffSummary(taskId: String): HandoffSummary =
        post("api/v1/tasks/$taskId/handoff", buildJsonObject {})

    suspend fun listMessages(taskId: String, limit: Int = 60): List<TaskMessage> =
        get("api/v1/tasks/$taskId/messages?limit=$limit")

    /** 任务名下每一轮 Run 的摘要：耗时与「改动/命令/读取」计数。 */
    suspend fun listTaskRuns(taskId: String, limit: Int = 20): List<TaskRunSummary> =
        get("api/v1/tasks/$taskId/runs?limit=$limit")

    /** 一轮 Run 的完整执行轨迹：思考过程 + 命令（含输出）。 */
    suspend fun getRunTrace(taskId: String, runId: String): RunTrace =
        get("api/v1/tasks/$taskId/runs/$runId/trace")

    suspend fun sendMessage(
        taskId: String,
        content: String,
        attachmentIds: List<String> = emptyList(),
    ): TaskReplyOutcome = post(
        "api/v1/tasks/$taskId/messages",
        buildJsonObject {
            put("content", content)
            putAttachmentIds(attachmentIds)
        },
    )

    /** 终态对话续跑：新消息作为新一轮 Agent 指令，历史消息保留。 */
    suspend fun continueTask(
        taskId: String,
        content: String,
        attachmentIds: List<String> = emptyList(),
    ): TaskReplyOutcome = post(
        "api/v1/tasks/$taskId/continue",
        buildJsonObject {
            put("content", content)
            putAttachmentIds(attachmentIds)
        },
    )

    /** 安全输入卡片：把密钥交给本机密钥库；明文只在这一个请求里出现，响应只回掩码。 */
    suspend fun submitSecureInput(
        taskId: String,
        messageId: String,
        value: String,
    ): SecureInputOutcome = post(
        "api/v1/tasks/$taskId/secure-input",
        buildJsonObject {
            put("message_id", messageId)
            put("value", value)
        },
    )

    /**
     * 上传一个聊天附件：裸请求体 + query 参数传文件名 / MIME，不走 multipart。
     *
     * [openStream] 由调用方提供，写出时才打开，避免把大文件整个读进内存。
     */
    suspend fun uploadAttachment(
        name: String,
        mime: String?,
        size: Long,
        openStream: () -> InputStream,
    ): UploadedAttachment = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val encodedName = Uri.encode(name.ifBlank { "file" })
        val query = buildString {
            append("api/v1/uploads?name=").append(encodedName)
            if (!mime.isNullOrBlank()) append("&mime=").append(Uri.encode(mime))
        }
        val mediaType = (mime?.takeIf { it.isNotBlank() } ?: "application/octet-stream").toMediaType()
        val requestBody = InputStreamRequestBody(mediaType, size, openStream)
        val request = Request.Builder().url(url(query)).post(requestBody).build()
        val response = try {
            uploadClient.newCall(request).execute()
        } catch (e: Exception) {
            val detail = e.message?.takeIf { it.isNotBlank() } ?: e::class.simpleName ?: "网络请求失败"
            throw FluxApiException("network_error", "附件上传失败（$detail）")
        }
        response.use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) throw apiExceptionFrom(text, it.code)
            if (text.isBlank()) throw FluxApiException("empty_response", "服务端返回为空")
            val envelope = runCatching { json.decodeFromString<Envelope<JsonElement>>(text) }.getOrElse {
                throw FluxApiException("invalid_response", "服务端返回格式无法识别：${it.message ?: "未知格式"}")
            }
            if (!envelope.success) {
                throw FluxApiException(envelope.code.ifBlank { "api_error" }, envelope.message.ifBlank { "附件上传失败" })
            }
            val data = envelope.data ?: throw FluxApiException("empty_data", "服务端响应缺少 data")
            json.decodeFromJsonElement(UploadedAttachment.serializer(), data)
        }
    }

    suspend fun startTask(taskId: String, confirmation: List<ConfirmationItem>? = null): TaskStartOutcome {
        val body = buildJsonObject {
            if (!confirmation.isNullOrEmpty()) {
                put("confirmation", json.encodeToJsonElement(ListSerializer(ConfirmationItem.serializer()), confirmation))
            }
        }
        return post("api/v1/tasks/$taskId/start", body)
    }

    suspend fun cancelTask(taskId: String): Task = post("api/v1/tasks/$taskId/cancel", buildJsonObject {})

    /** 彻底删除一段对话（任务 + 消息 + 运行记录）；运行中的对话服务端会返回 409。 */
    suspend fun deleteTask(taskId: String): JsonElement = delete("api/v1/tasks/$taskId")

    /** 切换任务的执行 Agent：同一对话保留历史，后续 Run 交给新 Agent。 */
    suspend fun setTaskAgent(taskId: String, agentId: String): Task =
        post("api/v1/tasks/$taskId/agent", buildJsonObject { put("agent_id", agentId) })

    /**
     * 切换对话权限模式（plan / normal / full）。
     * 正在执行的一轮按启动时的模式跑完，新模式从下一轮生效。
     */
    suspend fun setTaskPermissionMode(taskId: String, mode: String): Task =
        post(
            "api/v1/tasks/$taskId/permission-mode",
            buildJsonObject { put("mode", mode) },
        )

    /** 切换任务的执行模型：必须是当前 Agent runtime 支持的模型；keyId 非空表示来自密钥库。 */
    suspend fun setTaskModel(taskId: String, model: String, keyId: String? = null): Task =
        post(
            "api/v1/tasks/$taskId/model",
            buildJsonObject {
                put("model", model)
                if (!keyId.isNullOrBlank()) put("key_id", keyId)
            },
        )

    // ---------------------------------------------------------- 密钥库

    suspend fun listKeys(): List<ApiKeyEntry> = get("api/v1/keys")

    suspend fun createKey(
        name: String,
        provider: String,
        baseUrl: String,
        apiKey: String,
        models: List<String>,
        wireApi: String,
        enabled: Boolean,
    ): ApiKeyEntry = post(
        "api/v1/keys",
        buildJsonObject {
            put("name", name.trim())
            put("provider", provider)
            put("base_url", baseUrl.trim())
            put("api_key", apiKey.trim())
            put("models", json.encodeToJsonElement(ListSerializer(String.serializer()), models))
            put("wire_api", wireApi)
            put("enabled", enabled)
        },
    )

    /** 修改密钥：apiKey 为空表示保持原 key。 */
    suspend fun updateKey(
        keyId: String,
        name: String,
        provider: String,
        baseUrl: String,
        apiKey: String?,
        models: List<String>,
        wireApi: String,
        enabled: Boolean,
    ): ApiKeyEntry = patch(
        "api/v1/keys/$keyId",
        buildJsonObject {
            put("name", name.trim())
            put("provider", provider)
            put("base_url", baseUrl.trim())
            if (!apiKey.isNullOrBlank()) put("api_key", apiKey.trim())
            put("models", json.encodeToJsonElement(ListSerializer(String.serializer()), models))
            put("wire_api", wireApi)
            put("enabled", enabled)
        },
    )

    suspend fun deleteKey(keyId: String): JsonElement = delete("api/v1/keys/$keyId")

    suspend fun setKeyEnabled(keyId: String, enabled: Boolean): ApiKeyEntry =
        patch("api/v1/keys/$keyId", buildJsonObject { put("enabled", enabled) })

    /** 保存后用它真实请求一次该 key 的模型端点，确认 Base URL / Key / 模型 id 可用。 */
    suspend fun probeKey(keyId: String): KeyProbeResult =
        post("api/v1/keys/$keyId/probe", buildJsonObject { })

    // ---------------------------------------------------------- 账号级用户记忆

    suspend fun listUserMemories(): List<UserMemoryEntry> =
        get("api/v1/memory/entries?layer=user")

    suspend fun createUserMemory(content: String): UserMemoryEntry = post(
        "api/v1/memory/entries",
        buildJsonObject {
            put("content", content.trim())
            put("source", "user")
        },
    )

    suspend fun updateUserMemory(memoryId: String, content: String): UserMemoryEntry =
        putRequest(
            "api/v1/memory/entries/$memoryId",
            buildJsonObject { put("content", content.trim()) },
        )

    suspend fun deleteUserMemory(memoryId: String): JsonElement =
        delete("api/v1/memory/entries/$memoryId")

    suspend fun memorySettings(): MemorySettings = get("api/v1/memory/settings")

    suspend fun setMemoryAutoCapture(enabled: Boolean): JsonElement = putRequest(
        "api/v1/memory/settings",
        buildJsonObject { put("auto_capture", enabled) },
    )

    /** 立即和账号云对一次账（下拉刷新用）。 */
    suspend fun syncUserMemories(): JsonElement =
        post("api/v1/memory/sync", buildJsonObject {})

    suspend fun getRun(runId: String): Run = get("api/v1/dsh/runs/$runId")
    suspend fun listRuns(): List<Run> = get("api/v1/dsh/runs")
    suspend fun interruptRun(runId: String): Run = post("api/v1/dsh/runs/$runId/interrupt", buildJsonObject {})

    suspend fun chooseDecision(
        taskId: String,
        decisionId: String,
        action: String,
        option: String?,
        note: String?,
    ): Task {
        val body = buildJsonObject {
            put("decision_id", decisionId)
            put("action", action)
            if (!option.isNullOrBlank()) put("option", option)
            if (!note.isNullOrBlank()) put("note", note)
        }
        val data: JsonObject = post("api/v1/tasks/$taskId/decisions/choose", body)
        val task = data["task"] ?: throw FluxApiException("invalid_response", "服务端响应缺少 task")
        return json.decodeFromJsonElement(Task.serializer(), task)
    }

    suspend fun listChanges(taskId: String? = null): List<Change> {
        val query = taskId?.takeIf { it.isNotBlank() }?.let { "?task_id=$it" } ?: ""
        return get("api/v1/workspace/changes$query")
    }

    suspend fun getChange(changeId: String): Change = get("api/v1/workspace/changes/$changeId")

    suspend fun acceptChanges(changeIds: List<String>): List<Change> =
        post("api/v1/workspace/accept", idsBody(changeIds))

    suspend fun applyChanges(changeIds: List<String>): List<Change> =
        post("api/v1/workspace/apply", idsBody(changeIds))

    suspend fun rejectChanges(changeIds: List<String>, reason: String?): List<Change> {
        val body = buildJsonObject {
            put("change_ids", json.encodeToJsonElement(ListSerializer(String.serializer()), changeIds))
            if (!reason.isNullOrBlank()) put("reason", reason)
        }
        return post("api/v1/workspace/reject", body)
    }

    suspend fun rollbackChange(changeId: String): Change =
        post("api/v1/workspace/changes/$changeId/rollback", buildJsonObject {})

    // ---------------------------------------------------------- Artifacts

    /** 产物列表；type 为空返回全部（含实时聚合的设计稿）。 */
    suspend fun listArtifacts(type: String? = null): List<Artifact> {
        val query = type?.takeIf { it.isNotBlank() }?.let { "?type=$it" } ?: ""
        return get("api/v1/artifacts$query")
    }

    /** 产物详情：页面 / 文档带正文；设计稿是 Luma 投影，只带摘要。 */
    suspend fun getArtifact(artifactId: String): Artifact =
        get("api/v1/artifacts/${Uri.encode(artifactId)}")

    /** 新建页面 / 文档；设计稿仍走设计工作室（POST /designs）。 */
    suspend fun createArtifact(type: String, title: String, content: String = ""): Artifact =
        post(
            "api/v1/artifacts",
            buildJsonObject {
                put("type", type)
                put("title", title.trim())
                put("content", content)
            },
        )

    /** 保存标题 / 正文；带 baseVersion 做乐观锁，冲突抛 artifact_conflict。 */
    suspend fun updateArtifact(
        artifactId: String,
        title: String? = null,
        content: String? = null,
        baseVersion: Int? = null,
    ): Artifact =
        putRequest(
            "api/v1/artifacts/${Uri.encode(artifactId)}",
            buildJsonObject {
                if (title != null) put("title", title)
                if (content != null) put("content", content)
                if (baseVersion != null) put("base_version", baseVersion)
            },
        )

    suspend fun deleteArtifact(artifactId: String): JsonElement =
        delete("api/v1/artifacts/${Uri.encode(artifactId)}")

    /**
     * 把网页提取成文档 / 页面；服务端完成抓取与正文抽取。
     *
     * [html] 是客户端当前页的 outerHTML（拿不到时留空，服务端按 [url] 抓取）；
     * [taskId] 非空时后端会把产物卡片发进该对话。
     */
    suspend fun extractArtifact(
        url: String,
        type: String,
        html: String? = null,
        title: String? = null,
        taskId: String? = null,
    ): ArtifactExtraction =
        post(
            "api/v1/artifacts/extract",
            artifactExtractBody(url, type, html, title, taskId),
        )

    // ---------------------------------------------------------- 设计工作室

    suspend fun listDesigns(projectId: String? = null): List<DesignSummary> {
        val query = projectId?.takeIf { it.isNotBlank() }?.let { "?project_id=$it" } ?: ""
        return get("api/v1/designs$query")
    }

    suspend fun getDesign(designId: String): DesignDocument = get("api/v1/designs/$designId")

    suspend fun createDesign(name: String, projectId: String? = null): DesignDocument =
        post(
            "api/v1/designs",
            buildJsonObject {
                put("name", name.trim())
                if (!projectId.isNullOrBlank()) put("project_id", projectId)
            },
        )

    /** 整份保存；服务端按 DesignDocument.version 做乐观锁，冲突时抛 design_conflict。 */
    suspend fun saveDesign(design: DesignDocument): DesignDocument =
        putRequest(
            "api/v1/designs/${design.id}",
            buildJsonObject {
                put("base_version", design.version)
                put("design", json.encodeToJsonElement(DesignDocument.serializer(), design))
            },
        )

    /** 删除整份设计稿；不可恢复。 */
    suspend fun deleteDesign(designId: String): JsonElement = delete("api/v1/designs/$designId")

    /** 对选中组件或整屏提修改要求；服务端收敛出 set/add/remove/reorder。 */
    suspend fun askDesignAi(
        designId: String,
        nodes: List<JsonObject>,
        screen: JsonObject,
        mode: String,
        prompt: String,
    ): DesignAiResult =
        post(
            "api/v1/designs/$designId/ai",
            buildJsonObject {
                put("nodes", JsonArray(nodes))
                put("screen", screen)
                put("mode", mode)
                put("prompt", prompt.trim())
            },
        )

    /** 历史版本列表（不含节点正文），用于画布「历史」面板。 */
    suspend fun listDesignRevisions(designId: String): List<DesignRevision> =
        get("api/v1/designs/$designId/revisions")

    /** 恢复到某个历史版本；上游会生成新版本号，当前版本自动留档。 */
    suspend fun revertDesign(designId: String, version: Int, baseVersion: Int): DesignDocument =
        post(
            "api/v1/designs/$designId/revert",
            buildJsonObject {
                put("version", version)
                put("base_version", baseVersion)
            },
        )

    /** 把设计稿渲染成单文件 HTML，供内嵌浏览器离线交互预览。 */
    suspend fun designWebPreview(designId: String): DesignWebPreview =
        get("api/v1/designs/$designId/preview")

    /**
     * 导入 Figma(.fig) / Sketch(.sketch) / SVG，生成一份新的 Luma 设计稿。
     *
     * 和附件上传一样走裸请求体，[openStream] 在写请求时才打开，16MB 的文件不进内存。
     */
    suspend fun importDesign(
        name: String,
        size: Long,
        project: String? = null,
        openStream: () -> InputStream,
    ): DesignDocument = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val query = buildString {
            append("api/v1/designs/import?filename=").append(Uri.encode(name.ifBlank { "import.svg" }))
            if (!project.isNullOrBlank()) append("&project=").append(Uri.encode(project))
        }
        val requestBody = InputStreamRequestBody("application/octet-stream".toMediaType(), size, openStream)
        val request = Request.Builder().url(url(query)).post(requestBody).build()
        val response = try {
            uploadClient.newCall(request).execute()
        } catch (e: Exception) {
            val detail = e.message?.takeIf { it.isNotBlank() } ?: e::class.simpleName ?: "网络请求失败"
            throw FluxApiException("network_error", "导入失败（$detail）")
        }
        response.use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) throw apiExceptionFrom(text, it.code)
            if (text.isBlank()) throw FluxApiException("empty_response", "服务端返回为空")
            val envelope = runCatching { json.decodeFromString<Envelope<JsonElement>>(text) }.getOrElse {
                throw FluxApiException("invalid_response", "服务端返回格式无法识别：${it.message ?: "未知格式"}")
            }
            if (!envelope.success) {
                throw FluxApiException(envelope.code.ifBlank { "api_error" }, envelope.message.ifBlank { "导入失败" })
            }
            val data = envelope.data ?: throw FluxApiException("empty_data", "服务端响应缺少 data")
            json.decodeFromJsonElement(DesignDocument.serializer(), data)
        }
    }

    /** 导出 SVG：all 是全部画板横向排一张，也可以传画板序号或画板 id。 */
    suspend fun exportDesignSvg(designId: String, screen: String = "all"): ByteArray =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val path = "api/v1/designs/$designId/export.svg?screen=${Uri.encode(screen)}"
            val request = Request.Builder().url(url(path)).get().build()
            val response = try {
                uploadClient.newCall(request).execute()
            } catch (e: Exception) {
                val detail = e.message?.takeIf { it.isNotBlank() } ?: e::class.simpleName ?: "网络请求失败"
                throw FluxApiException("network_error", "导出失败（$detail）")
            }
            response.use {
                if (!it.isSuccessful) throw apiExceptionFrom(it.body?.string().orEmpty(), it.code)
                it.body?.bytes() ?: throw FluxApiException("empty_response", "服务端返回为空")
            }
        }

    suspend fun listTerminalSessions(): List<TerminalSession> = get("api/v1/terminal/sessions")

    suspend fun createTerminalSession(runId: String? = null): TerminalSession {
        val body = buildJsonObject {
            if (!runId.isNullOrBlank()) put("run_id", runId)
        }
        return post("api/v1/terminal/sessions", body)
    }

    suspend fun getTerminalSession(sessionId: String): TerminalSession =
        get("api/v1/terminal/sessions/$sessionId")

    suspend fun listTerminalEvents(sessionId: String, afterSeq: Int = 0, limit: Int = 2000): List<TerminalEvent> =
        get("api/v1/terminal/sessions/$sessionId/events?after_seq=$afterSeq&limit=$limit")

    suspend fun runTerminalCommand(sessionId: String, command: String): TerminalEvent =
        terminalPost("api/v1/terminal/sessions/$sessionId/commands", buildJsonObject { put("command", command) })

    suspend fun stopTerminalSession(sessionId: String, force: Boolean): TerminalSession =
        post("api/v1/terminal/sessions/$sessionId/stop", buildJsonObject { put("force", force) })

    fun openTerminalStream(
        sessionId: String,
        afterSeq: Int = 0,
        onEvent: (TerminalEvent) -> Unit,
        onFailure: (String) -> Unit,
        onClosed: () -> Unit,
    ): EventSource {
        val request = Request.Builder()
            .url(url("api/v1/terminal/sessions/$sessionId/stream?after_seq=$afterSeq"))
            .header("Accept", "text/event-stream")
            .apply {
                if (config.token.isNotBlank()) header("Authorization", "Bearer ${config.token.trim()}")
            }
            .build()

        return sseFactory.newEventSource(request, object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                runCatching { json.decodeFromString(TerminalEvent.serializer(), data) }
                    .onSuccess(onEvent)
                    .onFailure { onFailure("终端事件解析失败：${it.message ?: "未知格式"}") }
            }

            override fun onClosed(eventSource: EventSource) = onClosed()

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: okhttp3.Response?) {
                val status = response?.code?.let { "HTTP $it" }
                onFailure(t?.message?.takeIf { it.isNotBlank() } ?: status ?: "终端实时流连接失败")
            }
        })
    }

    /** 对话增量流：助手逐字回复 + Agent 运行事件；断线后按 afterSeq 续传。 */
    fun openTaskStream(
        taskId: String,
        afterSeq: Int = 0,
        /** 连接建立（HTTP 200 + SSE 握手完成）：UI 据此结束「正在重连」状态。 */
        onOpen: () -> Unit = {},
        onEvent: (TaskStreamEvent) -> Unit,
        onFailure: (String) -> Unit,
        onClosed: () -> Unit,
    ): EventSource {
        val request = Request.Builder()
            .url(url("api/v1/tasks/$taskId/stream?after_seq=$afterSeq"))
            .header("Accept", "text/event-stream")
            .apply {
                if (config.token.isNotBlank()) header("Authorization", "Bearer ${config.token.trim()}")
            }
            .build()

        return sseFactory.newEventSource(request, object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: okhttp3.Response) {
                onOpen()
            }

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                runCatching { json.decodeFromString(TaskStreamEvent.serializer(), data) }
                    .onSuccess(onEvent)
            }

            override fun onClosed(eventSource: EventSource) = onClosed()

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: okhttp3.Response?) {
                val status = response?.code?.let { "HTTP $it" }
                onFailure(t?.message?.takeIf { it.isNotBlank() } ?: status ?: "对话实时流连接失败")
            }
        })
    }

    /**
     * 对话增量轮询版：账号云中转只做请求-响应、承载不了 SSE 长连接，
     * 中转模式下按 seq 增量拉取；事件结构与会话流 `/stream` 完全一致。
     */
    suspend fun pollTaskEvents(taskId: String, afterSeq: Int = 0): List<TaskStreamEvent> =
        get("api/v1/tasks/$taskId/events?after_seq=$afterSeq")

    // ---------------------------------------------------------- ChatGPT 账号（Codex 订阅）

    suspend fun codexAccount(): CodexAccountStatus = get("api/v1/codex-account")

    /** 发起设备码登录：返回授权链接与设备码。 */
    suspend fun startCodexLogin(): CodexLoginSession =
        post("api/v1/codex-account/login", buildJsonObject {})

    suspend fun codexLoginState(loginId: String): CodexLoginSession =
        get("api/v1/codex-account/login/$loginId")

    suspend fun logoutCodexAccount(): CodexLogoutResult =
        post("api/v1/codex-account/logout", buildJsonObject {})

    // ---------------------------------------------------------- Claude Code 订阅（claude.ai）

    suspend fun claudeAccount(): ClaudeAccountStatus = get("api/v1/claude-account")

    /** 官方 setup-token 登录：返回手机可打开的授权链接。 */
    suspend fun startClaudeLogin(): ClaudeLoginSession =
        post("api/v1/claude-account/login", buildJsonObject {})

    suspend fun claudeLoginState(loginId: String): ClaudeLoginSession =
        get("api/v1/claude-account/login/${Uri.encode(loginId)}")

    /** 浏览器授权页显示的 `code#state` 粘回后端，由 CLI 完成换票。 */
    suspend fun submitClaudeLoginCode(loginId: String, code: String): ClaudeLoginSession =
        post(
            "api/v1/claude-account/login/${Uri.encode(loginId)}/code",
            buildJsonObject { put("code", code) },
        )

    suspend fun logoutClaudeAccount(): ClaudeLogoutResult =
        post("api/v1/claude-account/logout", buildJsonObject {})

    // ---------------------------------------------------------- 连接器设置（内置 + 自定义 MCP）

    suspend fun listMcpConnectors(): McpConnectorList = get("api/v1/mcp-connectors")

    /** 新增自定义 MCP 服务器；后端探测通过才保存，失败会抛出带原因的 FluxApiException。 */
    suspend fun addMcpConnector(name: String, url: String): McpConnector =
        post(
            "api/v1/mcp-connectors",
            buildJsonObject {
                put("name", name)
                put("url", url)
            },
        )

    suspend fun probeMcpConnector(connectorId: String): McpConnector =
        post("api/v1/mcp-connectors/${Uri.encode(connectorId)}/probe", buildJsonObject {})

    suspend fun deleteMcpConnector(connectorId: String): JsonElement =
        delete("api/v1/mcp-connectors/${Uri.encode(connectorId)}")

    // ---------------------------------------------------------- GitHub 账号（仓库读写）

    suspend fun githubAccount(): GitHubAccountStatus = get("api/v1/github/status")

    /** 授权码登录第一步：拿 GitHub 授权页地址，App 用系统浏览器打开。 */
    suspend fun startGitHubLogin(): GitHubOAuthStart =
        post("api/v1/github/oauth/start", buildJsonObject {})

    /** 浏览器回跳后，把 code + state 交给服务端换票并写入加密登录态。 */
    suspend fun completeGitHubLogin(code: String, state: String): GitHubAccountStatus =
        post(
            "api/v1/github/oauth/callback",
            buildJsonObject {
                put("code", code)
                put("state", state)
            },
        )

    suspend fun logoutGitHub(): GitHubLogoutResult =
        post("api/v1/github/logout", buildJsonObject {})

    /** 当前账号可见的全部仓库（含私有与组织仓库）。 */
    suspend fun githubRepos(page: Int = 1, perPage: Int = 50): GitHubRepoPage =
        get("api/v1/github/repos?page=$page&per_page=$perPage")

    // ---------------------------------------------------------- 助理（Supervisor）

    suspend fun supervisorOverview(limit: Int = 50): SupervisorOverview =
        get("api/v1/supervisor/overview?limit=$limit")

    suspend fun supervisorObservations(limit: Int = 50): List<SupervisorTrigger> =
        get("api/v1/supervisor/observations?limit=$limit")

    suspend fun supervisorObservation(triggerId: String): SupervisorTrigger =
        get("api/v1/supervisor/observations/${Uri.encode(triggerId)}")

    /** 重新跑一次判定；只观察不落工单。 */
    suspend fun supervisorAssess(triggerId: String): SupervisorAssessResult =
        post("api/v1/supervisor/observations/${Uri.encode(triggerId)}/assess", buildJsonObject {})

    /** 让助理拆解工单；dispatch=true 时拆完立即派发可执行的。 */
    suspend fun supervisorPlan(triggerId: String, dispatch: Boolean = false): SupervisorPlanResult =
        post(
            "api/v1/supervisor/observations/${Uri.encode(triggerId)}/plan?dispatch=$dispatch",
            buildJsonObject {},
        )

    suspend fun supervisorTickets(limit: Int = 100): List<SupervisorTicket> =
        get("api/v1/supervisor/tickets?limit=$limit")

    suspend fun supervisorTicket(ticketId: String): SupervisorTicket =
        get("api/v1/supervisor/tickets/${Uri.encode(ticketId)}")

    suspend fun supervisorReports(limit: Int = 30): List<SupervisorReport> =
        get("api/v1/supervisor/reports?limit=$limit")

    suspend fun supervisorTimeline(limit: Int = 30): List<SupervisorTimelineItem> =
        get("api/v1/supervisor/timeline?limit=$limit")

    /** 把某个线程里排队中的工单一口气派发出去。 */
    suspend fun supervisorDispatchThread(threadId: String): SupervisorDispatchThreadResult =
        post("api/v1/supervisor/threads/${Uri.encode(threadId)}/dispatch", buildJsonObject {})

    suspend fun supervisorDispatchTicket(ticketId: String): SupervisorDispatchOutcome =
        post("api/v1/supervisor/tickets/${Uri.encode(ticketId)}/dispatch", buildJsonObject {})

    suspend fun supervisorRetryTicket(ticketId: String): SupervisorDispatchOutcome =
        post("api/v1/supervisor/tickets/${Uri.encode(ticketId)}/retry", buildJsonObject {})

    suspend fun supervisorStopTicket(ticketId: String, note: String? = null): SupervisorStopResult =
        post(
            "api/v1/supervisor/tickets/${Uri.encode(ticketId)}/stop",
            buildJsonObject { if (!note.isNullOrBlank()) put("note", note) },
        )

    suspend fun supervisorWatchdog(limit: Int = 50): SupervisorWatchdogResult =
        post("api/v1/supervisor/watchdog?limit=$limit", buildJsonObject {})

    /** 推进待处理事件队列（拉取 outbox / 派发）。 */
    suspend fun supervisorProcess(limit: Int = 20): SupervisorProcessResult =
        post("api/v1/supervisor/process?limit=$limit", buildJsonObject {})

    suspend fun supervisorSettings(): SupervisorSettings = get("api/v1/supervisor/settings")

    suspend fun updateSupervisorSettings(settings: SupervisorSettings): SupervisorSettings =
        putRequest(
            "api/v1/supervisor/settings",
            buildJsonObject {
                put("accept_mode", settings.acceptMode)
                put("model_provider", settings.modelProvider)
                put("model_name", settings.modelName)
                put("max_tickets_per_thread", settings.maxTicketsPerThread)
                put("max_ticket_depth", settings.maxTicketDepth)
                put("max_concurrent_runs", settings.maxConcurrentRuns)
            },
        )

    private suspend inline fun <reified T> get(path: String): T = request("GET", path, null)

    private suspend inline fun <reified T> post(path: String, body: JsonObject): T = request("POST", path, body)

    private suspend inline fun <reified T> patch(path: String, body: JsonObject): T = request("PATCH", path, body)

    private suspend inline fun <reified T> putRequest(path: String, body: JsonObject): T = request("PUT", path, body)

    private suspend inline fun <reified T> delete(path: String): T = request("DELETE", path, null)

    private suspend inline fun <reified T> terminalPost(path: String, body: JsonObject): T =
        requestWithClient(terminalClient, "POST", path, body)

    private suspend inline fun <reified T> request(method: String, path: String, body: JsonObject?): T =
        requestWithClient(client, method, path, body)

    private suspend inline fun <reified T> requestWithClient(
        httpClient: OkHttpClient,
        method: String,
        path: String,
        body: JsonObject?,
    ): T = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val requestBody = body?.toString()?.toRequestBody(JSON)
        val request = Request.Builder().url(url(path)).method(method, requestBody).build()
        val response = try {
            httpClient.newCall(request).execute()
        } catch (e: Exception) {
            val detail = e.message?.takeIf { it.isNotBlank() } ?: e::class.simpleName ?: "网络请求失败"
            throw FluxApiException("network_error", "网络请求失败（$detail）")
        }
        response.use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) throw apiExceptionFrom(text, it.code)
            if (text.isBlank()) throw FluxApiException("empty_response", "服务端返回为空")
            val envelope = runCatching { json.decodeFromString<Envelope<JsonElement>>(text) }.getOrElse {
                throw FluxApiException("invalid_response", "服务端返回格式无法识别：${it.message ?: "未知格式"}")
            }
            if (!envelope.success) {
                throw FluxApiException(
                    envelope.code.ifBlank { "api_error" },
                    envelope.message.ifBlank { fallbackApiMessage(it.code) },
                )
            }
            val data = envelope.data ?: throw FluxApiException("empty_data", "服务端响应缺少 data")
            json.decodeFromJsonElement(serializer<T>(), data)
        }
    }

    private fun idsBody(ids: List<String>): JsonObject = buildJsonObject {
        put("change_ids", json.encodeToJsonElement(ListSerializer(String.serializer()), ids))
    }

    /** 附件 id 列表：为空时不下发，避免给老版本后端多余字段。 */
    private fun kotlinx.serialization.json.JsonObjectBuilder.putAttachmentIds(ids: List<String>) {
        val clean = ids.filter { it.isNotBlank() }.take(UploadedAttachment.MAX_FILES)
        if (clean.isNotEmpty()) {
            put("attachment_ids", json.encodeToJsonElement(ListSerializer(String.serializer()), clean))
        }
    }

    /** 直接把 InputStream 写进 OkHttp，大文件不经过内存；长度未知时交给 OkHttp 分块发送。 */
    private class InputStreamRequestBody(
        private val contentType: MediaType,
        private val length: Long,
        private val openStream: () -> InputStream,
    ) : RequestBody() {
        override fun contentType(): MediaType = contentType

        override fun contentLength(): Long = if (length >= 0) length else -1L

        override fun writeTo(sink: BufferedSink) {
            openStream().use { input -> sink.writeAll(input.source()) }
        }
    }

    private fun url(path: String): String = config.baseUrl.trimEnd('/') + "/" + path.trimStart('/')

    private fun apiExceptionFrom(text: String, status: Int): FluxApiException {
        val info = parseApiError(text, status)
        return FluxApiException(info.code, info.message)
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        fun normalizeBaseUrl(raw: String): String {
            var value = raw.trim()
            require(value.isNotBlank()) { "服务器地址不能为空" }
            if (!value.contains("://")) value = "https://$value"
            val uri = runCatching { URI(value) }.getOrElse { throw IllegalArgumentException("服务器地址格式不正确") }
            require(uri.scheme.equals("https", true) || uri.scheme.equals("http", true)) { "服务器地址只支持 HTTP / HTTPS" }
            require(!uri.host.isNullOrBlank()) { "服务器地址缺少主机名" }
            require(uri.query == null && uri.fragment == null) { "服务器地址不能包含查询参数或片段" }
            return value.trimEnd('/')
        }
    }
}

/**
 * 「提取到创作」请求体。可选字段为空时不下发，避免给老后端多余字段；
 * 抽成独立函数便于单测锁住字段名（和网页提取 API 的契约）。
 */
internal fun artifactExtractBody(
    url: String,
    type: String,
    html: String? = null,
    title: String? = null,
    taskId: String? = null,
): JsonObject = buildJsonObject {
    put("url", url.trim())
    put("type", type)
    if (!html.isNullOrBlank()) put("html", html)
    if (!title.isNullOrBlank()) put("title", title.trim())
    if (!taskId.isNullOrBlank()) put("task_id", taskId.trim())
}

// ---------------------------------------------------------------- 错误文案

/** 从错误响应里解析出的、可以直接展示给用户的信息。 */
internal data class ApiErrorInfo(val code: String, val message: String)

private val apiErrorJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
}

/**
 * 把后端错误体转成可读文案。
 *
 * 兼容三种真实形态：
 *  - Flux 统一信封：`{"success":false,"code":"...","message":"..."}`
 *  - FastAPI / Starlette：`{"detail":"..."}` 或校验错误列表
 *  - 上游原样透传：`{"message":"Not Found"}` / `{"error":"...","error_description":"..."}`
 *
 * 只有完全拿不到可读信息时，才退回带状态码含义的兜底文案。
 */
internal fun parseApiError(text: String, status: Int): ApiErrorInfo {
    val root = runCatching { apiErrorJson.parseToJsonElement(text) as? JsonObject }.getOrNull()
    val code = root?.stringValue("code").orEmpty()
    val message = root?.let(::readableApiDetail).orEmpty()
    return ApiErrorInfo(
        code.ifBlank { defaultApiErrorCode(status) },
        message.ifBlank { fallbackApiMessage(status) },
    )
}

internal fun fallbackApiMessage(status: Int): String = when (status) {
    400 -> "请求参数有误（HTTP 400）"
    401 -> "登录状态已失效，请重新登录"
    403 -> "没有权限执行这个操作"
    404 -> "未找到接口或资源（HTTP 404）"
    409 -> "资源已被更新，请刷新后重试"
    422 -> "请求参数校验失败"
    429 -> "请求过于频繁，请稍后再试"
    in 200..299 -> "服务端请求失败"
    in 500..599 -> "服务器出错了（HTTP $status）"
    else -> "请求失败（HTTP $status）"
}

private fun defaultApiErrorCode(status: Int): String = when (status) {
    401 -> "unauthenticated"
    403 -> "forbidden"
    404 -> "not_found"
    409 -> "conflict"
    422 -> "validation_error"
    else -> "http_$status"
}

private fun JsonObject.stringValue(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

private fun readableApiDetail(root: JsonObject): String {
    root.stringValue("message")?.let { return it }
    root.stringValue("error_description")?.let { return it }
    root.stringValue("error")?.let { return it }
    return when (val detail = root["detail"]) {
        is JsonPrimitive -> detail.contentOrNull?.trim().orEmpty()
        is JsonArray -> {
            val parts = detail.mapNotNull(::validationDetail).distinct()
            if (parts.isEmpty()) "" else "请求参数校验失败：" + parts.joinToString("；")
        }
        is JsonObject -> validationDetail(detail).orEmpty()
        else -> ""
    }
}

/** FastAPI 校验错误的一条：`{loc:["body","title"], msg:"Field required", ...}`。 */
private fun validationDetail(element: JsonElement): String? {
    val obj = element as? JsonObject
        ?: return (element as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    val message = obj.stringValue("msg") ?: obj.stringValue("message") ?: return null
    val location = (obj["loc"] as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        ?.filterNot { it in setOf("body", "query", "path") }
        ?.joinToString(".")
        .orEmpty()
    return if (location.isBlank()) message else "$location：$message"
}
