package top.qiuli55.flux.mobile.data

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
