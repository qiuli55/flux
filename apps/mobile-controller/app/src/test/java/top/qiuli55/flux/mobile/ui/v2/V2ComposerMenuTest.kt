package top.qiuli55.flux.mobile.ui.v2

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import top.qiuli55.flux.mobile.data.KeyProbeModel
import top.qiuli55.flux.mobile.data.KeyProbeResult

/**
 * 输入栏两个小窗的副行文案。
 *
 * 副行必须和真实切换结果一致：
 *  - 切到另一个 Agent 时后端会清掉任务级模型覆盖，实际用该 Agent 的第一个可用模型；
 *  - 排队中的切换在本轮结束后走同一条路径，副行要显示即将生效的模型；
 *  - 当前 Agent 跟着正在用的模型走；
 *  - 没有模型时返回 null，不渲染空行，也不再出现「role · 模型」这种内部写法。
 */
class V2ComposerMenuTest {

    private val agentA = V2Agent(
        id = "a",
        name = "claude-code-agent",
        role = "developer",
        load = 0,
        running = 0,
        tint = Color.Black,
        runtime = "claude-code",
    )
    private val agentB = V2Agent(
        id = "b",
        name = "codex-agent",
        role = "developer",
        load = 0,
        running = 0,
        tint = Color.Black,
        runtime = "codex",
    )

    private val nativeA = V2ModelOption("claude-native", "Claude Sonnet 4.6")
    private val vaultA = V2ModelOption("deepseek-v4.1-flash", "DeepSeek V4.1 Flash", keyId = "k1")
    private val optionA = V2AgentOption(agentA, listOf(nativeA, vaultA))
    private val optionB = V2AgentOption(
        agentB,
        listOf(
            V2ModelOption("gpt-native", "GPT-5.6 Luna"),
            V2ModelOption("gpt-mini", "GPT-5.6 Mini"),
        ),
    )

    @Test
    fun `selected agent subtitle follows the model in use`() {
        assertEquals(
            "DeepSeek V4.1 Flash",
            agentMenuModelLabel(
                option = optionA,
                currentAgentId = agentA.id,
                currentModel = vaultA.model,
                currentModelKeyId = vaultA.keyId,
                pendingAgentId = null,
            ),
        )
        assertEquals(
            "Claude Sonnet 4.6",
            agentMenuModelLabel(
                option = optionA,
                currentAgentId = agentA.id,
                currentModel = nativeA.model,
                currentModelKeyId = null,
                pendingAgentId = null,
            ),
        )
    }

    @Test
    fun `same model id from different key keeps its readable label`() {
        // 同一模型 id 可能既有原生通道也有密钥库通道，按 keyId 区分，不能串行。
        val sharedNative = V2ModelOption("shared", "Shared Native")
        val sharedVault = V2ModelOption("shared", "Shared Vault", keyId = "k9")
        val option = V2AgentOption(agentA, listOf(sharedNative, sharedVault))

        assertEquals(
            "Shared Vault",
            agentMenuModelLabel(option, agentA.id, "shared", "k9", pendingAgentId = null),
        )
        assertEquals(
            "Shared Native",
            agentMenuModelLabel(option, agentA.id, "shared", null, pendingAgentId = null),
        )
    }

    @Test
    fun `unselected agent shows the model it would switch to`() {
        assertEquals(
            "GPT-5.6 Luna",
            agentMenuModelLabel(
                option = optionB,
                currentAgentId = agentA.id,
                currentModel = nativeA.model,
                currentModelKeyId = null,
                pendingAgentId = null,
            ),
        )
    }

    @Test
    fun `queued agent subtitle reflects the model that will take effect`() {
        // 当前在 A 用 vault 模型；排队切到 B。生效时模型覆盖会被清掉，B 用第一个可用模型。
        assertEquals(
            "GPT-5.6 Luna",
            agentMenuModelLabel(
                option = optionB,
                currentAgentId = agentA.id,
                currentModel = vaultA.model,
                currentModelKeyId = vaultA.keyId,
                pendingAgentId = agentB.id,
            ),
        )
        // 排队目标行不能显示当前 Agent 正在用的模型。
        assertEquals(
            "DeepSeek V4.1 Flash",
            agentMenuModelLabel(
                option = optionA,
                currentAgentId = agentA.id,
                currentModel = vaultA.model,
                currentModelKeyId = vaultA.keyId,
                pendingAgentId = agentB.id,
            ),
        )
    }

    @Test
    fun `subtitle is null when the agent has no usable model`() {
        val empty = V2AgentOption(agentB, emptyList())
        assertNull(agentMenuModelLabel(empty, agentA.id, nativeA.model, null, pendingAgentId = null))
        assertNull(agentMenuModelLabel(empty, agentB.id, "", null, pendingAgentId = null))
        assertNull(agentMenuModelLabel(empty, agentA.id, nativeA.model, null, pendingAgentId = agentB.id))
    }

    @Test
    fun `subtitle never falls back to the raw role`() {
        val label = agentMenuModelLabel(
            option = optionA,
            currentAgentId = agentA.id,
            currentModel = nativeA.model,
            currentModelKeyId = null,
            pendingAgentId = null,
        )
        assertEquals("Claude Sonnet 4.6", label)
        assertFalse(label!!.contains(agentA.role))
        assertFalse(label.contains("developer"))
        assertFalse(label.contains(" · "))
    }

    @Test
    fun `model menu subtitle only states real in-use and vault status`() {
        assertEquals("当前在用", modelMenuSubtitle(nativeA, nativeA.model, null))
        assertEquals("密钥库", modelMenuSubtitle(vaultA, nativeA.model, null))
        assertEquals("当前在用 · 密钥库", modelMenuSubtitle(vaultA, vaultA.model, vaultA.keyId))
        assertNull(modelMenuSubtitle(nativeA, vaultA.model, vaultA.keyId))
        assertNull(modelMenuSubtitle(optionB.models[1], nativeA.model, null))
    }

    @Test
    fun `unreachable model shows a short mark and reason in the menu`() {
        val bad = V2ModelOption(
            model = "bad",
            label = "bad · 不通 · DeepSeek",
            keyId = "k1",
            status = "unreachable",
            reasonCode = "model_unavailable",
            reason = "模型名不存在或不可用",
        )
        assertEquals("不通 · 模型名不存在或不可用", modelMenuSubtitle(bad, "other", null))
        assertEquals(
            "不通 · 模型名不存在或不可用 · 当前在用",
            modelMenuSubtitle(bad, bad.model, bad.keyId),
        )
        // 原因缺失时也不能显示空文案
        assertEquals("不通 · 其他失败", modelMenuSubtitle(bad.copy(reason = ""), "other", null))
    }

    @Test
    fun `unchecked model is not marked unreachable`() {
        val unchecked = V2ModelOption("m", keyId = "k1", status = "unchecked")
        assertFalse(unchecked.unreachable)
        assertEquals("密钥库", modelMenuSubtitle(unchecked, "other", null))
        assertFalse(V2ModelOption("native").unreachable)
    }

    @Test
    fun `probe summary and row reason keep failures visible`() {
        val ok = KeyProbeModel(model = "a", ok = true, status = "available", latencyMs = 12)
        val bad = KeyProbeModel(
            model = "b",
            ok = false,
            status = "unreachable",
            reasonCode = "model_unavailable",
            reason = "模型名不存在或不可用",
        )

        assertEquals("全部 1 个模型连通正常", keyProbeSummary(KeyProbeResult(ok = true, models = listOf(ok))))
        assertEquals("2 个模型中 1 个不通", keyProbeSummary(KeyProbeResult(ok = false, models = listOf(ok, bad))))
        assertEquals("12 ms", keyProbeReasonText(ok))
        assertEquals("模型名不存在或不可用", keyProbeReasonText(bad))
        assertEquals("其他失败", keyProbeReasonText(bad.copy(reason = "")))
    }

    @Test
    fun `model options follow the selected agent`() {
        val options = listOf(optionA, optionB)
        assertEquals(listOf("claude-native", "deepseek-v4.1-flash"), modelsForAgent(options, "a").map { it.model })
        assertEquals(listOf("gpt-native", "gpt-mini"), modelsForAgent(options, "b").map { it.model })
        // 找不到 Agent 时为空，不沿用上一个 Agent 的模型。
        assertEquals(emptyList<V2ModelOption>(), modelsForAgent(options, "missing"))
    }
}
