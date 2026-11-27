package top.qiuli55.flux.mobile.ui.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.qiuli55.flux.mobile.data.McpConnector

/** 连接器页的可测逻辑：分区、搜索、表单校验、探测失败展示、GitHub 入口迁移。 */
class V2ConnectorLogicTest {

    private fun item(
        id: String,
        status: String,
        kind: String = "builtin",
        name: String = id,
        tools: List<String> = emptyList(),
        error: String? = null,
        available: Boolean = true,
    ) = McpConnector(
        id = id,
        name = name,
        kind = kind,
        status = status,
        description = "$name 说明",
        available = available,
        url = if (kind == "custom") "https://example.com/mcp" else null,
        tools = tools,
        account = if (id == "github" && status == "connected") "qiuli55" else null,
        error = error,
    )

    @Test
    fun `connected items and coming soon are split into sections`() {
        val sections = connectorSections(
            listOf(
                item("github", "connected"),
                item("mine", "connected", kind = "custom", tools = listOf("search")),
                item("figma", "coming_soon", available = false),
                item("gmail", "disconnected", available = false),
            ),
            query = "",
        )

        assertEquals(listOf("github", "mine"), sections.connected.map { it.id })
        assertEquals(listOf("figma", "gmail"), sections.featured.map { it.id })
        assertTrue(sections.needsReconnect.isEmpty())
    }

    @Test
    fun `needs reconnect is shown in connected and banner`() {
        val broken = item(
            "mine",
            "error",
            kind = "custom",
            error = "tools/list 不支持",
        )
        val sections = connectorSections(
            listOf(item("github", "connected"), broken),
            query = "",
        )

        assertEquals(listOf("mine"), sections.needsReconnect.map { it.id })
        assertEquals(listOf("github", "mine"), sections.connected.map { it.id })
        assertTrue(sections.featured.isEmpty())
        assertEquals("tools/list 不支持", connectorErrorText(broken))
    }

    @Test
    fun `search matches name description and url`() {
        val items = listOf(
            item("github", "connected"),
            item("mine", "connected", kind = "custom", name = "我的搜索服务"),
        )
        assertEquals(listOf("mine"), connectorSections(items, "搜索").connected.map { it.id })
        assertEquals(listOf("mine"), connectorSections(items, "example.com").connected.map { it.id })
        assertTrue(connectorSections(items, "不存在的关键词").isEmpty)
    }

    @Test
    fun `subtitle explains github account custom tools and errors`() {
        assertEquals(
            "@qiuli55 · 已连接",
            connectorSubtitle(item("github", "connected")),
        )
        assertEquals(
            "已连接 · 2 个工具",
            connectorSubtitle(
                item("mine", "connected", kind = "custom", tools = listOf("a", "b")),
            ),
        )
        assertEquals(
            "tools/list 不支持",
            connectorSubtitle(item("mine", "error", kind = "custom", error = "tools/list 不支持")),
        )
        assertEquals(
            "即将支持",
            connectorSubtitle(item("figma", "coming_soon", available = false)),
        )
    }

    @Test
    fun `action labels never pretend a coming soon connector is connectable`() {
        assertEquals("连接", connectorActionLabel(item("github", "disconnected")))
        assertEquals("重新连接", connectorActionLabel(item("github", "needs_reauth")))
        assertEquals(
            "即将支持",
            connectorActionLabel(item("figma", "coming_soon", available = false)),
        )
        assertNull(connectorActionLabel(item("github", "connected")))
    }

    @Test
    fun `form validation catches empty invalid and reserved values`() {
        assertEquals("请填写连接器名称", validateConnectorForm("", "https://example.com/mcp"))
        assertEquals(
            "名称 flux 保留给 Flux 内置工具",
            validateConnectorForm("FLUX", "https://example.com/mcp"),
        )
        assertEquals("请填写服务器 URL", validateConnectorForm("我的 MCP", ""))
        assertEquals(
            "服务器 URL 需要以 https:// 或 http:// 开头",
            validateConnectorForm("我的 MCP", "ftp://example.com/mcp"),
        )
        assertEquals(
            "服务器 URL 缺少主机名",
            validateConnectorForm("我的 MCP", "https:///mcp"),
        )
        assertNull(validateConnectorForm("  我的 MCP  ", " https://example.com/mcp "))
    }

    @Test
    fun `probe failure keeps the user's input and surfaces the server reason`() {
        val form = ConnectorFormState(name = "我的 MCP", url = "https://example.com/mcp", busy = true)
        val failed = form.withFailure("连接超时（8 秒），服务器没有响应 MCP 握手。")

        assertEquals("我的 MCP", failed.name)
        assertEquals("https://example.com/mcp", failed.url)
        assertFalse(failed.busy)
        assertEquals("连接超时（8 秒），服务器没有响应 MCP 握手。", failed.error)
    }

    @Test
    fun `github moved from profile settings into connectors`() {
        assertTrue(PROFILE_SETTINGS_TITLES.contains("连接器"))
        assertTrue(PROFILE_SETTINGS_TITLES.contains("订阅与账号"))
        assertFalse(PROFILE_SETTINGS_TITLES.any { it.contains("GitHub") })
        assertFalse(PROFILE_SETTINGS_TITLES.any { it.contains("ChatGPT") })

        val sections = connectorSections(
            listOf(item("github", "connected")),
            query = "",
        )
        assertEquals("github", sections.connected.single().id)
        assertEquals("连接器", CONNECTOR_SCREEN_TITLE)
    }
}
