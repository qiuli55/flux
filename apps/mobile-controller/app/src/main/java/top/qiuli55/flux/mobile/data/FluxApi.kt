package top.qiuli55.flux.mobile.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.serializer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
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

    private val sseFactory = EventSources.createFactory(
        client.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build(),
    )

    suspend fun health(): HealthData = get("api/v1/health")
    suspend fun dshStatus(): DshStatus = get("api/v1/dsh/status")
    suspend fun listAgents(): List<Agent> = get("api/v1/agents")
    suspend fun listTasks(): List<Task> = get("api/v1/tasks")

    suspend fun createTask(description: String, agentId: String?, decisionMode: String): Task {
        val body = buildJsonObject {
            put("description", description.trim())
            if (!agentId.isNullOrBlank()) put("agent_id", agentId)
            put("decision_mode", decisionMode)
        }
        return post("api/v1/tasks", body)
    }

    suspend fun getTask(taskId: String): Task = get("api/v1/tasks/$taskId")

    suspend fun listMessages(taskId: String, limit: Int = 60): List<TaskMessage> =
        get("api/v1/tasks/$taskId/messages?limit=$limit")

    suspend fun sendMessage(taskId: String, content: String): TaskReplyOutcome =
        post("api/v1/tasks/$taskId/messages", buildJsonObject { put("content", content) })

    suspend fun startTask(taskId: String, confirmation: List<ConfirmationItem>? = null): TaskStartOutcome {
        val body = buildJsonObject {
            if (!confirmation.isNullOrEmpty()) {
                put("confirmation", json.encodeToJsonElement(ListSerializer(ConfirmationItem.serializer()), confirmation))
            }
        }
        return post("api/v1/tasks/$taskId/start", body)
    }

    suspend fun cancelTask(taskId: String): Task = post("api/v1/tasks/$taskId/cancel", buildJsonObject {})
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

    private suspend inline fun <reified T> get(path: String): T = request("GET", path, null)

    private suspend inline fun <reified T> post(path: String, body: JsonObject): T = request("POST", path, body)

    private suspend inline fun <reified T> terminalPost(path: String, body: JsonObject): T =
        requestWithClient(terminalClient, "POST", path, body)

    private suspend inline fun <reified T> request(method: String, path: String, body: JsonObject?): T =
        requestWithClient(client, method, path, body)

    private suspend inline fun <reified T> requestWithClient(
        httpClient: OkHttpClient,
        method: String,
        path: String,
        body: JsonObject?,
    ): T {
        val requestBody = body?.toString()?.toRequestBody(JSON)
        val request = Request.Builder().url(url(path)).method(method, requestBody).build()
        val response = try {
            httpClient.newCall(request).execute()
        } catch (e: Exception) {
            throw FluxApiException("network_error", e.message ?: "网络请求失败")
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
            return json.decodeFromJsonElement(serializer<T>(), data)
        }
    }

    private fun idsBody(ids: List<String>): JsonObject = buildJsonObject {
        put("change_ids", json.encodeToJsonElement(ListSerializer(String.serializer()), ids))
    }

    private fun url(path: String): String = config.baseUrl.trimEnd("/") + "/" + path.trimStart("/")

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
            return value.trimEnd("/")
        }
    }
}