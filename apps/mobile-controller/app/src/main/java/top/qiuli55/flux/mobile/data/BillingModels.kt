package top.qiuli55.flux.mobile.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.Locale

/**
 * 套餐额度：与后端 `/api/v1/billing/plans` 的返回结构一一对应。
 *
 * 官方快照只有套餐总量（百分比/重置时间），本地账本才有 token 明细；
 * 两者并排展示就能同时看到"官方说我用了多少"和"Flux 记了多少"。
 */
@Serializable
data class BillingTotals(
    val records: Int = 0,
    @SerialName("input_tokens") val inputTokens: Long = 0,
    @SerialName("output_tokens") val outputTokens: Long = 0,
    @SerialName("cache_read_tokens") val cacheReadTokens: Long = 0,
    @SerialName("cache_write_tokens") val cacheWriteTokens: Long = 0,
    @SerialName("total_tokens") val totalTokens: Long = 0,
    val cost: Double = 0.0,
)

@Serializable
data class BillingOfficialWindow(
    val used: Double? = null,
    val quota: Double? = null,
    val percent: Double? = null,
    val unit: String = "percent",
    val status: String = "ok",
    @SerialName("resets_at") val resetsAt: String? = null,
    @SerialName("fetched_at") val fetchedAt: String? = null,
    val source: String = "",
    val error: String? = null,
)

@Serializable
data class BillingExternal(
    val snapshots: Int = 0,
    val since: String? = null,
    @SerialName("latest_at") val latestAt: String? = null,
    @SerialName("official_increase") val officialIncrease: Double = 0.0,
    // 官方增长中 Flux 空闲时段的部分（可归给外部客户端）
    val points: Double = 0.0,
    // 官方增长中 Flux 同时在跑、无法拆分归因的部分
    @SerialName("mixed_points") val mixedPoints: Double = 0.0,
)

@Serializable
data class BillingWindow(
    val key: String = "",
    val label: String = "",
    val since: String = "",
    val local: BillingTotals = BillingTotals(),
    val official: BillingOfficialWindow? = null,
    val external: BillingExternal? = null,
)

@Serializable
data class BillingGate(
    val level: String = "unknown",
    val percent: Double? = null,
    @SerialName("window_key") val windowKey: String = "",
    val message: String = "",
    val enforce: Boolean = false,
    @SerialName("fallback_model") val fallbackModel: String = "",
    val blocked: Boolean = false,
)

@Serializable
data class BillingPlan(
    val key: String = "",
    val label: String = "",
    val vendor: String = "",
    @SerialName("model_prefix") val modelPrefix: String = "",
    // 归因前缀（模型 id 第一段）；空 = 不按模型归因。
    @SerialName("model_prefixes") val modelPrefixes: List<String> = emptyList(),
    // 模型不带前缀时按 runtime 归因（如 codex → Codex 订阅）。
    @SerialName("runtime_ids") val runtimeIds: List<String> = emptyList(),
    @SerialName("official_source") val officialSource: String = "",
    @SerialName("fallback_model") val fallbackModel: String = "",
    val gate: BillingGate = BillingGate(),
    val windows: List<BillingWindow> = emptyList(),
)

/** 一条官方价目：每 100 万 token 的单价（currency 可能是 CNY）。 */
@Serializable
data class BillingPrice(
    val model: String = "",
    val vendor: String = "",
    val input: Double = 0.0,
    val output: Double = 0.0,
    @SerialName("cache_read") val cacheRead: Double = 0.0,
    @SerialName("cache_write") val cacheWrite: Double = 0.0,
    val currency: String = "USD",
    @SerialName("off_peak_multiplier") val offPeakMultiplier: Double? = null,
    val source: String = "",
    val note: String = "",
)

@Serializable
data class BillingPrices(
    @SerialName("as_of") val asOf: String = "",
    @SerialName("cny_per_usd") val cnyPerUsd: Double = 7.2,
    val prices: List<BillingPrice> = emptyList(),
)

@Serializable
data class BillingOverview(
    val enabled: Boolean = false,
    val enforce: Boolean = false,
    @SerialName("alert_percent") val alertPercent: Double = 0.0,
    @SerialName("block_percent") val blockPercent: Double = 0.0,
    val plans: List<BillingPlan> = emptyList(),
) {
    /** 首页入口的一行摘要：优先报最紧张的那条官方额度，没有快照就报本月本地 token。 */
    val headline: String
        get() {
            val tight = plans
                .flatMap { plan -> plan.windows.mapNotNull { window -> window.official?.percent?.let { plan.label to it } } }
                .maxByOrNull { it.second }
            if (tight != null) return "${tight.first} ${tight.second.toInt()}%"
            val month = plans.sumOf { plan -> plan.windows.firstOrNull { it.key == "monthly" }?.local?.totalTokens ?: 0L }
            return if (month > 0) "本月 ${compactTokens(month)} tok" else "暂无额度数据"
        }
}

/** 1234567 → 1.2M，首页只用一行，不展开精确到个位。 */
fun compactTokens(value: Long): String = when {
    value >= 1_000_000_000L -> String.format(Locale.US, "%.1fB", value / 1_000_000_000.0)
    value >= 1_000_000L -> String.format(Locale.US, "%.1fM", value / 1_000_000.0)
    value >= 1_000L -> String.format(Locale.US, "%.1fK", value / 1_000.0)
    else -> value.toString()
}
