package top.qiuli55.flux.mobile.ui.v2

import androidx.compose.ui.graphics.Color
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模型选择只来自后端：注册表为空时不能再冒出演示型号；
 * 后端注入的原生模型 / 密钥库模型是唯一可选项。
 */
class V2ModelOptionsTest {

    @After
    fun resetRegistry() {
        V2Registry.agents = emptyList()
        V2Registry.devices = emptyList()
        V2Registry.models = emptyList()
        V2Registry.agentModels = emptyMap()
    }

    @Test
    fun `empty registry exposes no hardcoded demo models`() {
        val options = agentsForDevice(ALL_DEVICES)
        assertTrue(options.flatMap { it.models }.none { option ->
            option.model.contains("Opus") ||
                option.model.contains("GPT-5") ||
                option.model.contains("Qwen") ||
                option.model.contains("Claude")
        })
    }

    @Test
    fun `backend agent models are used verbatim`() {
        val agent = V2Agent("a1", "Flux Agent", "执行", 0, 0, Color.Black)
        V2Registry.agents = listOf(agent)
        V2Registry.models = listOf("native-model")
        V2Registry.agentModels = mapOf(
            "a1" to listOf(
                V2ModelOption("native-model"),
                V2ModelOption("vault-model", "vault-model · 我的 key", keyId = "k1"),
            ),
        )

        val options = agentsForDevice("本机")
        val models = options.first { it.agent.id == "a1" }.models
        assertEquals(listOf("native-model", "vault-model"), models.map { it.model })
        assertEquals("k1", models.last().keyId)
    }


    private fun codexCatalog(): List<V2ModelOption> = listOf(
        V2ModelOption(
            model = "gpt-5.5",
            label = "gpt-5.5",
            accountId = "acc-1",
            channelId = "subscription:acc-1",
            channelKind = "subscription",
            channelLabel = "Codex · user@example.com",
            contextWindow = 400_000,
            capabilities = listOf("思考", "图片输入", "工具调用"),
            thinking = V2Thinking(
                kind = "levels",
                levels = listOf("minimal", "low", "medium", "high"),
                labels = listOf("极简", "低", "中", "高"),
                default = "medium",
                canDisable = true,
            ),
        ),
        V2ModelOption(
            model = "deepseek-chat",
            label = "deepseek-chat",
            keyId = "k1",
            channelId = "key:k1",
            channelKind = "key",
            channelLabel = "密钥库 · 我的 key",
            contextWindow = 128_000,
            capabilities = listOf("思考", "工具调用"),
            thinking = V2Thinking(
                kind = "toggle",
                levels = listOf("on", "off"),
                labels = listOf("开", "关"),
                default = "on",
                canDisable = true,
            ),
        ),
    )

    @Test
    fun `channels keep subscription accounts and each compatible key separate`() {
        val options = codexCatalog()
        val channels = listOf(
            V2ModelChannel("subscription:acc-1", "subscription", "Codex · user@example.com"),
            V2ModelChannel("key:k1", "key", "密钥库 · 我的 key"),
        )
        assertEquals(2, channels.size)
        assertEquals(listOf("gpt-5.5"), modelsForChannel(options, "subscription:acc-1").map { it.model })
        assertEquals(listOf("deepseek-chat"), modelsForChannel(options, "key:k1").map { it.model })
        // 「全部」仍只含当前 Agent 的通道，不会串入别的 Agent。
        assertEquals(listOf("gpt-5.5", "deepseek-chat"), modelsForChannel(options, ALL_MODELS).map { it.model })
        assertTrue(modelsForChannel(options, ALL_MODELS).none { it.model.contains("claude") })
    }

    @Test
    fun `favorites only reorder and never change the channel`() {
        val options = codexCatalog()
        val favorite = setOf(options.last().favoriteKey)
        val sorted = sortedModelOptions(options, favorite)
        assertEquals(listOf("deepseek-chat", "gpt-5.5"), sorted.map { it.model })
        assertEquals("k1", sorted.first().keyId)
        assertEquals(options, sortedModelOptions(options, emptySet()))
    }

    @Test
    fun `context window labels are honest`() {
        assertEquals("1M ctx", contextWindowLabel(1_000_000))
        assertEquals("400k ctx", contextWindowLabel(400_000))
        assertEquals("128k ctx", contextWindowLabel(128_000))
        assertNull(contextWindowLabel(null))
        assertNull(contextWindowLabel(0))
    }

    @Test
    fun `thinking level falls back to the model default and unsupported stays empty`() {
        val levels = codexCatalog()[0]
        assertEquals("medium", thinkingLevelOf(levels, null))
        assertEquals("high", thinkingLevelOf(levels, "high"))
        val toggle = codexCatalog()[1]
        assertEquals("on", thinkingLevelOf(toggle, null))
        assertEquals("off", thinkingLevelOf(toggle, "off"))
        val none = V2ModelOption("plain-model")
        assertFalse(none.thinking.supported)
        assertEquals("", thinkingLevelOf(none, null))
    }

    @Test
    fun `always thinking model has no off switch`() {
        val always = V2Thinking(kind = "always", levels = emptyList(), labels = emptyList(), default = "always", canDisable = false)
        assertFalse(always.canDisable)
        assertEquals("always", always.default)
        assertTrue(always.levels.none { it == "off" })
    }

    @Test
    fun `two subscription accounts stay separate chips with account names`() {
        val channels = listOf(
            V2ModelChannel(
                id = "subscription:a1",
                kind = "subscription",
                label = "Kimi 订阅 · qiuli",
                chipLabel = "qiuli",
            ),
            V2ModelChannel(
                id = "subscription:a2",
                kind = "subscription",
                label = "Kimi 订阅 · qiuli1",
                chipLabel = "qiuli1",
            ),
            V2ModelChannel(
                id = "key:k1",
                kind = "key",
                label = "密钥库 · opencode",
                chipLabel = "opencode",
            ),
        )
        val options = listOf(
            V2ModelOption("kimi-k2", channelId = "subscription:a1", channelKind = "subscription", channelLabel = "Kimi 订阅 · qiuli"),
            V2ModelOption("kimi-k2-thinking", channelId = "subscription:a2", channelKind = "subscription", channelLabel = "Kimi 订阅 · qiuli1"),
            V2ModelOption("deepseek-v4-pro", channelId = "key:k1", channelKind = "key", channelLabel = "密钥库 · opencode"),
        )
        // chips 是账号名 / 密钥名，不会把两个同厂账号合并
        assertEquals(listOf("qiuli", "qiuli1", "opencode"), channels.map(::channelChipLabel))
        assertEquals(
            listOf("kimi-k2"),
            modelsForChannel(options, "subscription:a1").map { it.model },
        )
        assertEquals(
            listOf("kimi-k2-thinking"),
            modelsForChannel(options, "subscription:a2").map { it.model },
        )
        assertEquals(
            listOf("kimi-k2", "kimi-k2-thinking", "deepseek-v4-pro"),
            modelsForChannel(options, ALL_MODELS).map { it.model },
        )
        // 列表来源仍写成「订阅 · 账号」/「密钥库 · 密钥名」
        assertEquals("Kimi 订阅 · qiuli", modelDetailLine(options[0], false))
        assertEquals("密钥库 · opencode", modelDetailLine(options[2], false))
    }

    @Test
    fun `search only filters models and channels inside the current agent list`() {
        val options = listOf(
            V2ModelOption("kimi-k2", channelLabel = "Kimi 订阅 · qiuli"),
            V2ModelOption("gpt-5.5", channelLabel = "OpenAI 订阅 · user@example.com"),
            V2ModelOption("deepseek-v4-pro", channelLabel = "密钥库 · opencode"),
        )
        assertEquals(
            listOf("kimi-k2"),
            filterModelOptions(options, "qiuli").map { it.model },
        )
        assertEquals(
            listOf("deepseek-v4-pro"),
            filterModelOptions(options, "opencode").map { it.model },
        )
        assertEquals(
            listOf("gpt-5.5"),
            filterModelOptions(options, "GPT").map { it.model },
        )
        // 传进来的就是当前 Agent 的目录；搜索不会凭空混入别的 Agent 的模型。
        assertTrue(filterModelOptions(options, "claude").isEmpty())
        assertEquals(options, filterModelOptions(options, "  "))
    }
}
