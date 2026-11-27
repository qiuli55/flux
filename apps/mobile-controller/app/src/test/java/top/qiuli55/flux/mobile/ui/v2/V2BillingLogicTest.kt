package top.qiuli55.flux.mobile.ui.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.qiuli55.flux.mobile.data.BillingAccount
import top.qiuli55.flux.mobile.data.BillingGate
import top.qiuli55.flux.mobile.data.BillingOfficialWindow
import top.qiuli55.flux.mobile.data.BillingOverview
import top.qiuli55.flux.mobile.data.BillingPlan
import top.qiuli55.flux.mobile.data.BillingTotals
import top.qiuli55.flux.mobile.data.BillingWindow

/** 「套餐额度」弹层的纯函数：环形图选中的套餐/窗口、来源文案、多账号与失败原因。 */
class V2BillingLogicTest {

    private fun window(
        key: String,
        label: String,
        percent: Double? = null,
        error: String? = null,
        tokens: Long = 0,
        records: Int = 0,
    ) = BillingWindow(
        key = key,
        label = label,
        local = BillingTotals(records = records, totalTokens = tokens),
        official = if (percent == null && error == null) {
            null
        } else {
            BillingOfficialWindow(percent = percent, error = error)
        },
    )

    @Test
    fun `gauge picks the same plan and window as the displayed percent`() {
        val overview = BillingOverview(
            enabled = true,
            alertPercent = 80.0,
            blockPercent = 95.0,
            plans = listOf(
                BillingPlan(
                    key = "codex",
                    label = "Codex 订阅",
                    windows = listOf(
                        // 失败窗口即使百分比更大也不能参与
                        window("rolling_5h", "5 小时", percent = 99.0, error = "登录态已失效"),
                        window("weekly", "7 天", percent = 92.0),
                    ),
                ),
                BillingPlan(
                    key = "opencode-go",
                    label = "OpenCode Go",
                    windows = listOf(window("monthly", "自然月", percent = 30.0)),
                ),
            ),
        )

        val highlight = billingHighlight(overview)!!
        assertEquals("codex", highlight.planKey)
        assertEquals("Codex 订阅", highlight.planLabel)
        assertEquals("weekly", highlight.windowKey)
        assertEquals("7 天", highlight.windowLabel)
        assertEquals(92.0, highlight.percent, 0.0)
        assertEquals("warning", highlight.level)
        // 百分比、窗口与状态文案必须来自同一条数据
        assertEquals(
            "Codex 订阅 · 7 天 · 接近上限",
            "${highlight.planLabel} · ${highlight.windowLabel} · ${levelLabel(highlight.level)}",
        )
    }

    @Test
    fun `gauge is absent when no plan has official percent`() {
        val overview = BillingOverview(
            plans = listOf(
                BillingPlan(
                    key = "claude",
                    label = "Claude 订阅",
                    windows = listOf(window("monthly", "自然月", tokens = 1_200, records = 3)),
                ),
            ),
        )
        assertNull(billingHighlight(overview))
        assertTrue(billingLocalMonthSummary(overview).contains("3 次"))
        assertTrue(billingLocalMonthSummary(overview).contains("token"))
    }

    @Test
    fun `level uses the configured alert and block thresholds`() {
        assertEquals("ok", billingLevel(79.9, 80.0, 95.0))
        assertEquals("warning", billingLevel(80.0, 80.0, 95.0))
        assertEquals("exceeded", billingLevel(95.0, 80.0, 95.0))
    }

    @Test
    fun `local month summary handles an empty ledger`() {
        val overview = BillingOverview(
            plans = listOf(BillingPlan(key = "claude", label = "Claude 订阅")),
        )
        assertEquals("本月还没有 Flux 本地调用记录", billingLocalMonthSummary(overview))
    }

    @Test
    fun `codex source is official not bookkeeping-only`() {
        val codex = sourceLabel("chatgpt_codex")
        assertTrue(codex.contains("ChatGPT"))
        assertTrue(codex.contains("官方"))
        assertFalse(codex.contains("仅供记账"))
    }

    @Test
    fun `plans without official source say they are local bookkeeping`() {
        val claude = sourceLabel("")
        assertTrue(claude.contains("暂无官方额度"))
        assertTrue(claude.contains("本地记账"))
    }

    @Test
    fun `official line shows the backend failure reason instead of a generic label`() {
        val failed = billingOfficialLine(
            BillingOfficialWindow(
                error = "还没有登录 ChatGPT 账号：在「我的 → 订阅与账号」登录后会自动采集额度",
            ),
        )
        assertTrue(failed.startsWith("暂时取不到官方额度："))
        assertTrue(failed.contains("还没有登录 ChatGPT 账号"))
        assertTrue(failed.contains("我的 → 订阅与账号"))
        assertFalse(failed.contains("对账失败"))
    }

    @Test
    fun `official line formats percent and reset time`() {
        assertEquals("暂无官方数据", billingOfficialLine(null))
        assertEquals("12.5%", billingOfficialLine(BillingOfficialWindow(percent = 12.5)))
        assertEquals(
            "12.5% · 11-27 10:30 重置",
            billingOfficialLine(
                BillingOfficialWindow(
                    percent = 12.5,
                    resetsAt = "2026-11-27T10:30:00Z",
                ),
            ),
        )
    }

    @Test
    fun `other accounts only appear when there is more than one`() {
        val single = BillingPlan(
            label = "Codex 订阅",
            accounts = listOf(BillingAccount(key = "a", label = "账号 a", isCurrent = true)),
        )
        assertTrue(billingOtherAccounts(single).isEmpty())

        val multi = single.copy(
            accounts = listOf(
                BillingAccount(key = "a", label = "账号 a", isCurrent = true),
                BillingAccount(key = "b", label = "账号 b", isCurrent = false),
            ),
        )
        assertEquals(listOf("b"), billingOtherAccounts(multi).map { it.key })
    }

    @Test
    fun `account window line keeps official and local numbers distinguishable`() {
        val line = billingAccountWindowLine(
            window("weekly", "7 天", percent = 99.0, tokens = 1_500, records = 2),
        )
        assertTrue(line.contains("7 天"))
        assertTrue(line.contains("官方 99%"))
        assertTrue(line.contains("本地 1.5K token"))
    }

    @Test
    fun `plan type maps to a short user-facing label`() {
        assertEquals("Plus", billingPlanTypeLabel("plus"))
        assertEquals("Pro", billingPlanTypeLabel("PRO"))
        assertEquals("", billingPlanTypeLabel(""))
        assertEquals("custom", billingPlanTypeLabel("custom"))
    }

    @Test
    fun `threshold note avoids alert and block jargon`() {
        val overview = BillingOverview(alertPercent = 80.0, blockPercent = 95.0, enforce = false)
        assertEquals("用到 80% 会提醒；达到上限也不会阻止任务", billingThresholdNote(overview))
        assertEquals(
            "用到 80% 会提醒，到 95% 会阻止新任务",
            billingThresholdNote(overview.copy(enforce = true)),
        )
    }

    @Test
    fun `gate note translates the internal window key`() {
        val plan = BillingPlan(
            label = "Codex 订阅",
            fallbackModel = "gpt-5.5-mini",
            windows = listOf(window("weekly", "7 天", percent = 85.0)),
            gate = BillingGate(
                level = "warning",
                percent = 85.0,
                windowKey = "weekly",
                message = "Codex 订阅 额度告警：weekly 已用 85.0%，可降级到 gpt-5.5-mini",
            ),
        )
        assertEquals(
            "「7 天」已用 85%，接近上限，可以把模型换成 gpt-5.5-mini",
            billingGateNote(plan),
        )
    }

    @Test
    fun `gate note keeps already-readable unknown-state messages`() {
        val plan = BillingPlan(
            label = "Claude 订阅",
            gate = BillingGate(level = "unknown", message = "Claude 订阅 没有可用的额度数据"),
        )
        assertEquals("Claude 订阅 没有可用的额度数据", billingGateNote(plan))
    }
}
