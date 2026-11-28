package top.qiuli55.flux.mobile.ui.v2

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.qiuli55.flux.mobile.data.ImportedCapability
import top.qiuli55.flux.mobile.data.McpConnector
import top.qiuli55.flux.mobile.data.taskMessageBody

/**
 * 输入区「本次指定」的纯逻辑：
 *  - 点一下指定 / 再点一下取消；
 *  - 连接器只列已连接且可用的；
 *  - 不指定时请求不出现指定字段（证明不会变成「这次一律不用」）；
 *  - 指定只出现在这一条发送的请求体里。
 */
class V2SpecifiedCapabilitiesTest {

    private fun skill(id: String, name: String, enabled: Boolean = true) =
        ImportedCapability(id = id, kind = "skill", name = name, enabled = enabled)

    private fun connector(
        id: String,
        status: String = "connected",
        available: Boolean = true,
    ) = McpConnector(id = id, name = id, status = status, available = available)

    @Test
    fun `tapping the same item toggles it off again`() {
        val once = toggleSpecified(emptyList(), "skill-1")
        assertEquals(listOf("skill-1"), once)
        assertEquals(emptyList<String>(), toggleSpecified(once, "skill-1"))
        assertEquals(listOf("skill-1", "skill-2"), toggleSpecified(once, "skill-2"))
    }

    @Test
    fun `only connected and available connectors can be specified`() {
        val items = listOf(
            connector("github", "connected"),
            connector("mine", "connected"),
            connector("broken", "error"),
            connector("off", "disconnected"),
            connector("soon", "coming_soon", available = false),
        )
        assertEquals(listOf("github", "mine"), specifiableConnectors(items).map { it.id })
    }

    @Test
    fun `specified chips keep readable names and fall back to the id`() {
        val skills = listOf(skill("s1", "代码审查", enabled = false))
        val chips = specifiedSkillChips(listOf("s1", "missing"), skills)
        assertEquals("代码审查", chips[0].name)
        assertFalse(chips[0].enabled)
        assertEquals("missing", chips[1].name)

        val connectorChips = specifiedConnectorChips(listOf("github"), listOf(connector("github")))
        assertEquals("github", connectorChips.single().name)
        assertEquals("连接器", connectorChips.single().kindLabel)
    }

    @Test
    fun `no specification sends no specified fields at all`() {
        val body = taskMessageBody("你好")
        assertFalse(body.containsKey("specified_skill_ids"))
        assertFalse(body.containsKey("specified_connector_ids"))
        assertFalse(body.containsKey("attachment_ids"))
    }

    @Test
    fun `specified ids appear only in the request that carries them`() {
        val body = taskMessageBody(
            "用这个技能看看",
            attachmentIds = listOf("a1"),
            specifiedSkillIds = listOf("skill-1"),
            specifiedConnectorIds = listOf("github"),
        )
        assertEquals(
            listOf("skill-1"),
            body["specified_skill_ids"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(
            listOf("github"),
            body["specified_connector_ids"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(
            listOf("a1"),
            body["attachment_ids"]!!.jsonArray.map { it.jsonPrimitive.content },
        )

        // 下一条发送不再带指定：重新构造成普通请求即可恢复自动使用。
        val next = taskMessageBody("下一条")
        assertFalse(next.containsKey("specified_skill_ids"))
        assertFalse(next.containsKey("specified_connector_ids"))
    }

    @Test
    fun `blank ids are dropped so they cannot lock anything`() {
        val body: JsonObject = taskMessageBody(
            "hi",
            specifiedSkillIds = listOf("", "  "),
            specifiedConnectorIds = listOf(""),
        )
        assertFalse(body.containsKey("specified_skill_ids"))
        assertFalse(body.containsKey("specified_connector_ids"))
    }
}
