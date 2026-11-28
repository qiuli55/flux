package top.qiuli55.flux.mobile.ui.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.qiuli55.flux.mobile.data.ThinkingSpec

/** 密钥库模型的思考模式：预填、可改类型 / 默认档、保存前不能空着。 */
class V2KeyThinkingTest {

    private fun levels(vararg items: String) = ThinkingSpec(
        kind = "levels",
        levels = items.toList(),
        labels = items.map(::thinkingLevelLabel),
        default = items.first(),
        canDisable = true,
    )

    @Test
    fun `official and generic defaults stay in their own shapes`() {
        val deepseek = levels("none", "low", "high", "max")
        assertEquals(listOf("无思考", "低", "高", "最高"), thinkingSpecLabels(deepseek))
        val generic = levels("none", "minimal", "low", "medium", "high", "max")
        assertEquals(
            listOf("无思考", "最低", "低", "中", "高", "最高"),
            thinkingSpecLabels(generic),
        )
    }

    @Test
    fun `user can change type and default level, and it is kept`() {
        val saved = switchThinkingKind(levels("none", "low", "high", "max"), "toggle")
        assertEquals("toggle", saved.kind)
        assertEquals(listOf("on", "off"), saved.levels)
        assertEquals("on", saved.default)

        val changed = saved.copy(default = "off")
        assertEquals("off", thinkingActiveLevel(changed))

        val back = switchThinkingKind(changed, "levels")
        assertEquals("levels", back.kind)
        // 从开关切回多档时用通用 6 档（不凭空发明官方档位）
        assertEquals(
            listOf("none", "minimal", "low", "medium", "high", "max"),
            back.levels,
        )
        assertEquals("none", back.default)

        val always = switchThinkingKind(back, "always")
        assertEquals("always", always.kind)
        assertFalse(always.canDisable)
        assertEquals("always", always.default)

        val none = switchThinkingKind(always, "none")
        assertEquals("none", none.kind)
        assertTrue(none.levels.isEmpty())
    }

    @Test
    fun `save is rejected until every model has a complete thinking mode`() {
        val models = listOf("deepseek-v4-pro", "gpt-5.5")
        val one = mapOf(
            "deepseek-v4-pro" to levels("none", "low", "high", "max"),
        )
        assertFalse(keyThinkingComplete(models, one))
        assertFalse(keyThinkingComplete(emptyList(), one))
        assertFalse(
            keyThinkingComplete(
                models,
                one + ("gpt-5.5" to ThinkingSpec(kind = "levels", levels = listOf("low"), default = "")),
            ),
        )
        val both = one + (
            "gpt-5.5" to ThinkingSpec(
                kind = "levels",
                levels = listOf("minimal", "low", "medium", "high"),
                labels = listOf("最低", "低", "中", "高"),
                default = "medium",
                canDisable = true,
            )
            )
        assertTrue(keyThinkingComplete(models, both))
        // 只提交当前模型清单里的项
        assertEquals(models.toSet(), keyThinkingPayload(models, both).keys)
        assertEquals(emptySet<String>(), keyThinkingPayload(listOf("removed"), both).keys)
    }
}
