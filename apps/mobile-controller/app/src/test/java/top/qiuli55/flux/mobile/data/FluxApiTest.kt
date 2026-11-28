package top.qiuli55.flux.mobile.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FluxApiTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun normalizeBaseUrl_addsHttpsAndRemovesTrailingSlash() {
        assertEquals(
            "https://example.com/mobile",
            FluxApi.normalizeBaseUrl("example.com/mobile/"),
        )
    }

    @Test
    fun normalizeBaseUrl_keepsExplicitHttps() {
        assertEquals(
            "https://example.com/mobile",
            FluxApi.normalizeBaseUrl("https://example.com/mobile/"),
        )
    }

    @Test
    fun normalizeBaseUrl_rejectsUnsupportedScheme() {
        runCatching { FluxApi.normalizeBaseUrl("ftp://example.com/mobile") }
            .onSuccess { error("expected unsupported scheme to fail") }
            .onFailure { assertTrue(it is IllegalArgumentException) }
    }

    @Test
    fun normalizeBaseUrl_rejectsQueryAndFragment() {
        runCatching { FluxApi.normalizeBaseUrl("https://example.com/mobile?token=abc") }
            .onSuccess { error("expected query string to fail") }
            .onFailure { assertTrue(it is IllegalArgumentException) }
    }

    @Test
    fun decodeEnvelopeWithUnknownMetadataFields() {
        val envelope = json.decodeFromString<Envelope<kotlinx.serialization.json.JsonElement>>(
            """{"success":true,"code":"ok","message":"","data":{"status":"ok","app":"Flux","env":"server"},"metadata":{"count":1,"extra":"ignored-by-client"}}""",
        )
        assertTrue(envelope.success)
        assertEquals("ok", envelope.code)
        assertEquals("server", envelope.data?.jsonObject?.get("env")?.jsonPrimitive?.content)
    }

    @Test
    fun parseApiError_prefersFluxEnvelopeMessage() {
        val info = parseApiError(
            """{"success":false,"code":"artifact_conflict","message":"产物已被更新，请刷新后再改","data":null}""",
            409,
        )
        assertEquals("artifact_conflict", info.code)
        assertEquals("产物已被更新，请刷新后再改", info.message)
    }

    @Test
    fun parseApiError_readsFastApiDetailString() {
        val info = parseApiError("""{"detail":"Not Found"}""", 404)
        assertEquals("not_found", info.code)
        assertEquals("Not Found", info.message)
    }

    @Test
    fun parseApiError_readsFastApiValidationList() {
        val info = parseApiError(
            """{"detail":[{"loc":["body","title"],"msg":"Field required","type":"missing"}]}""",
            422,
        )
        assertEquals("validation_error", info.code)
        assertEquals("请求参数校验失败：title：Field required", info.message)
    }

    @Test
    fun parseApiError_fallsBackWithStatusMeaningInsteadOfBareHttpCode() {
        val info = parseApiError("", 404)
        assertEquals("not_found", info.code)
        assertEquals("未找到接口或资源（HTTP 404）", info.message)
    }

    @Test
    fun parseApiError_emptyEnvelopeMessageStillFallsBack() {
        val info = parseApiError("""{"success":false,"code":"","message":""}""", 500)
        assertEquals("http_500", info.code)
        assertEquals("服务器出错了（HTTP 500）", info.message)
    }

    // ---------------------------------------------------------- 未结束任务分页

    @Test
    fun unfinishedTasksPath_onlyAsksForUnfinishedWithPaging() {
        assertEquals(
            "api/v1/tasks?unfinished=true&limit=200&offset=0",
            unfinishedTasksPath(limit = TASK_PAGE_SIZE, offset = 0),
        )
        assertEquals(
            "api/v1/tasks?unfinished=true&limit=200&offset=200",
            unfinishedTasksPath(limit = TASK_PAGE_SIZE, offset = 200),
        )
    }

    @Test
    fun collectPagedTasks_walksOffsetUntilShortPage() = runBlocking {
        val offsets = mutableListOf<Int>()
        val pages = mapOf(
            0 to listOf(Task(id = "a"), Task(id = "b")),
            2 to listOf(Task(id = "c")),
        )
        val all = collectPagedTasks(pageSize = 2) { _, offset ->
            offsets += offset
            pages[offset].orEmpty()
        }
        assertEquals(listOf("a", "b", "c"), all.map { it.id })
        assertEquals(listOf(0, 2), offsets)
    }

    @Test
    fun collectPagedTasks_fetchesFinalEmptyPageWhenTotalIsExactMultiple() = runBlocking {
        val offsets = mutableListOf<Int>()
        val pages = mapOf(
            0 to listOf(Task(id = "a"), Task(id = "b")),
            2 to listOf(Task(id = "c"), Task(id = "d")),
            4 to emptyList<Task>(),
        )
        val all = collectPagedTasks(pageSize = 2) { _, offset ->
            offsets += offset
            pages[offset].orEmpty()
        }
        assertEquals(listOf("a", "b", "c", "d"), all.map { it.id })
        assertEquals(listOf(0, 2, 4), offsets)
    }

    @Test
    fun collectPagedTasks_stopsWhenServerIgnoresOffsetInsteadOfLoopingForever() = runBlocking {
        // 老后端不认识 offset，每页都把同一批任务再返回一次：不能无限请求或重复堆积。
        var calls = 0
        val all = collectPagedTasks(pageSize = 2) { _, _ ->
            calls += 1
            listOf(Task(id = "a"), Task(id = "b"))
        }
        assertEquals(listOf("a", "b"), all.map { it.id })
        assertEquals(2, calls)
    }

    @Test
    fun decodePolledTaskEventsList() {
        // 中转模式轮询 /tasks/{id}/events 拿到的事件数组：结构与 /stream 一致
        val events = json.decodeFromString<List<TaskStreamEvent>>(
            """[{"seq":3,"type":"assistant_delta","data":{"text":"你"}},
               {"seq":4,"type":"run_event","data":{"kind":"tool","command":"ls"}}]""",
        )
        assertEquals(2, events.size)
        assertEquals("assistant_delta", events.first().type)
        assertEquals("你", events.first().data["text"]?.jsonPrimitive?.content)
        assertEquals("tool", events.last().data["kind"]?.jsonPrimitive?.content)
    }
}
