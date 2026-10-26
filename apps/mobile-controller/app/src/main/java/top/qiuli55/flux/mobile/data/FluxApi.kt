package top.qiuli55.flux.mobile.data

import android.net.Uri
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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

    /** 对单个组件提修改要求；服务端只返回作用于该节点的属性补丁。 */
    suspend fun askDesignAi(
        designId: String,
        node: DesignNode,
        screen: DesignScreen,
        prompt: String,
    ): DesignAiResult =
        post(
            "api/v1/designs/$designId/ai",
            buildJsonObject {
                put("node", json.encodeToJsonElement(DesignNode.serializer(), node))
                put("screen", json.encodeToJsonElement(DesignScreen.serializer(), screen))
                put("prompt", prompt.trim())
            },
        )

    /** 把设计稿渲染成单文件 HTML，供内嵌浏览器离线交互预览。 */
    suspend fun designWebPreview(designId: String): DesignWebPreview =
        get("api/v1/designs/$designId/preview")

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
                throw FluxApiException(envelope.code.ifBlank { "api_error" }, envelope.message.ifBlank { "服务端请求失败" })
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
        val envelope = runCatching { json.decodeFromString<Envelope<JsonElement>>(text) }.getOrNull()
        if (envelope != null && !envelope.success) {
            return FluxApiException(
                envelope.code.ifBlank {
                    when (status) { 401 -> "unauthenticated"; 403 -> "forbidden"; else -> "http_$status" }
                },
                envelope.message.ifBlank { "HTTP $status" },
            )
        }
        return FluxApiException(
            when (status) { 401 -> "unauthenticated"; 403 -> "forbidden"; 404 -> "not_found"; else -> "http_$status" },
            "请求失败（HTTP $status）",
        )
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