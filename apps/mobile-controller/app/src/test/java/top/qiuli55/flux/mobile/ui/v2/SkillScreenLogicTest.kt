package top.qiuli55.flux.mobile.ui.v2

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.qiuli55.flux.mobile.data.CapabilityCandidate
import top.qiuli55.flux.mobile.data.CapabilityFinding
import top.qiuli55.flux.mobile.data.CapabilityScanReport
import top.qiuli55.flux.mobile.data.ImportedCapability

/** 技能管理页的纯逻辑：扫描候选过滤、拦截原因、详情行、移除说明。 */
class SkillScreenLogicTest {

    private fun skill(enabled: Boolean = true) = ImportedCapability(
        id = "s1",
        kind = "skill",
        name = "代码审查",
        source = "/home/user/skills/review",
        version = "1.0",
        enabled = enabled,
        spec = JsonObject(mapOf("description" to JsonPrimitive("按清单审查"))),
    )

    @Test
    fun `only skill candidates are listed when adding`() {
        val report = CapabilityScanReport(
            candidates = listOf(
                CapabilityCandidate(kind = "skill", name = "审查"),
                CapabilityCandidate(kind = "connector", name = "GitHub"),
                CapabilityCandidate(kind = "agent", name = "codex"),
            ),
        )
        assertEquals(listOf("审查"), skillScanCandidates(report).map { it.name })
        assertTrue(skillScanCandidates(null).isEmpty())
    }

    @Test
    fun `blocked candidates explain why they cannot be imported`() {
        val blocked = CapabilityCandidate(
            kind = "skill",
            name = "可疑技能",
            status = "blocked",
            findings = listOf(CapabilityFinding("skills.x", "包含明文令牌")),
        )
        assertEquals("未通过安全检查：包含明文令牌", skillImportBlocker(blocked))
        assertNull(skillImportBlocker(CapabilityCandidate(kind = "skill", name = "ok")))
    }

    @Test
    fun `detail lines state enabled status version and source`() {
        val lines = skillDetailLines(skill(enabled = false))
        assertTrue(lines.any { it.contains("已停用") })
        assertTrue(lines.any { it.contains("版本：1.0") })
        assertTrue(lines.any { it.contains("/home/user/skills/review") })
        assertTrue(lines.any { it.contains("按清单审查") })
    }

    @Test
    fun `toggle label and remove message do not claim the source is deleted`() {
        assertEquals("已启用", skillToggleLabel(true))
        assertEquals("已停用", skillToggleLabel(false))
        val message = skillRemoveMessage("代码审查")
        assertTrue(message.contains("代码审查"))
        assertTrue(message.contains("不会删除电脑上的原始技能目录"))
        assertFalse(message.contains("删除磁盘"))
    }

    @Test
    fun `profile keeps the skills entry together with connectors`() {
        assertTrue(PROFILE_SETTINGS_TITLES.contains("技能"))
        assertTrue(PROFILE_SETTINGS_TITLES.contains("连接器"))
        assertTrue(PROFILE_SETTINGS_TITLES.indexOf("技能") < PROFILE_SETTINGS_TITLES.indexOf("终端会话"))
    }
}
