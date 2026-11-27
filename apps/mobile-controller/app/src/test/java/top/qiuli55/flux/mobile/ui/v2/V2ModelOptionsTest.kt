package top.qiuli55.flux.mobile.ui.v2

import androidx.compose.ui.graphics.Color
import org.junit.After
import org.junit.Assert.assertEquals
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
}
