package top.qiuli55.flux.mobile.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 套餐额度的数据模型与官方刷新结果解析。 */
class BillingModelsTest {

    @Test
    fun `refresh failure only reports plans that actually have an official source`() {
        val payload = Json.parseToJsonElement(
            """
            {
              "results": [
                {"plan_key": "codex", "source": "chatgpt_codex", "refreshed": false,
                 "error": "还没有登录 ChatGPT 账号"},
                {"plan_key": "claude", "source": "", "refreshed": false,
                 "error": "Claude 订阅没有官方额度来源"},
                {"plan_key": "opencode-go", "source": "opencode_go", "refreshed": true,
                 "error": null}
              ]
            }
            """.trimIndent(),
        )

        assertEquals(
            "官方额度更新失败 · Codex 订阅：还没有登录 ChatGPT 账号",
            billingSyncFailure(payload),
        )
    }

    @Test
    fun `refresh with only local-only plans is not an error`() {
        val payload = Json.parseToJsonElement(
            """
            {"results": [
              {"plan_key": "claude", "source": "", "refreshed": false,
               "error": "Claude 订阅没有官方额度来源"}
            ]}
            """.trimIndent(),
        )
        assertNull(billingSyncFailure(payload))
    }

    @Test
    fun `refresh failure tolerates a missing results array`() {
        assertNull(billingSyncFailure(Json.parseToJsonElement("""{"cloud": {"ok": true}}""")))
    }

    @Test
    fun `plan display names cover every built-in plan`() {
        assertEquals("OpenCode Go", planDisplayName("opencode-go"))
        assertEquals("火山 Agent Plan", planDisplayName("agent-plan"))
        assertEquals("OpenCode Zen", planDisplayName("opencode"))
        assertEquals("Codex 订阅", planDisplayName("codex"))
        assertEquals("Claude 订阅", planDisplayName("claude"))
        assertEquals("套餐", planDisplayName(""))
        assertEquals("my-plan", planDisplayName("my-plan"))
    }

    @Test
    fun `headline ignores failed official windows and no longer says tok`() {
        val overview = BillingOverview(
            plans = listOf(
                BillingPlan(
                    label = "Codex 订阅",
                    windows = listOf(
                        BillingWindow(
                            key = "weekly",
                            official = BillingOfficialWindow(percent = 99.0, error = "登录失效"),
                        ),
                    ),
                ),
                BillingPlan(
                    label = "OpenCode Go",
                    windows = listOf(
                        BillingWindow(
                            key = "rolling_5h",
                            official = BillingOfficialWindow(percent = 12.4),
                        ),
                    ),
                ),
            ),
        )
        assertEquals("OpenCode Go 12%", overview.headline)
    }

    @Test
    fun `headline falls back to local monthly tokens in plain language`() {
        val overview = BillingOverview(
            plans = listOf(
                BillingPlan(
                    label = "Claude 订阅",
                    windows = listOf(
                        BillingWindow(
                            key = "monthly",
                            local = BillingTotals(records = 2, totalTokens = 1_500),
                        ),
                    ),
                ),
            ),
        )
        assertEquals("本月 1.5K token", overview.headline)
        assertTrue(overview.headline.contains("token"))
    }
}
