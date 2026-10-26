package top.qiuli55.flux.mobile.ui.v2

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import top.qiuli55.flux.mobile.data.BillingOfficialWindow
import top.qiuli55.flux.mobile.data.BillingOverview
import top.qiuli55.flux.mobile.data.BillingPlan
import top.qiuli55.flux.mobile.data.BillingPrices
import top.qiuli55.flux.mobile.data.BillingWindow
import top.qiuli55.flux.mobile.data.compactTokens
import java.util.Locale

/**
 * 套餐额度弹层：本地账本与官方快照并排看。
 *
 * 官方百分比只代表套餐总量（OpenCode Go 接口 / arkcli 对账），
 * 本地 token 才是 Flux 自己的逐次记账；两者对不上时以官方为准判断闸门。
 */
@Composable
fun BillingSheet(
    overview: BillingOverview?,
    prices: BillingPrices?,
    loading: Boolean,
    error: String?,
    onRefresh: () -> Unit,
    onSync: () -> Unit,
    onDismiss: () -> Unit,
) {
    SheetFrame(onDismiss = onDismiss, maxHeightFraction = .93f) {
        SheetHeader(
            title = "套餐额度",
            subtitle = "官方额度按套餐对账；本地账本由 Flux 逐次调用记账",
            onClose = onDismiss,
        )
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton(
                    text = if (loading) "读取中…" else "刷新",
                    onClick = onRefresh,
                    modifier = Modifier.weight(1f),
                    icon = Icons.Outlined.Refresh,
                    enabled = !loading,
                )
                PrimaryButton(
                    text = "官方对账",
                    onClick = onSync,
                    modifier = Modifier.weight(1f),
                    icon = Icons.Outlined.Sync,
                    enabled = !loading,
                )
            }

            if (!error.isNullOrBlank()) {
                WhiteCard(Modifier.fillMaxWidth()) {
                    Label(error, 10.5f, V2Colors.rose, maxLines = 3, modifier = Modifier.padding(14.dp))
                }
            }

            if (overview == null) {
                WhiteCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Label(if (loading) "正在读取额度…" else "还没有额度数据", 12.5f, V2Colors.ink(.80f), FontWeight.Medium)
                        Label(
                            "把模型前缀配成 opencode-go/* 或 agent-plan/* 后，调用会自动归到对应套餐。",
                            10.5f,
                            V2Colors.ink(.40f),
                        )
                    }
                }
                return@Column
            }

            WhiteCard(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Label(if (overview.enabled) "计费已启用" else "计费已关闭", 11f, V2Colors.ink(.72f), FontWeight.Medium)
                    Spacer(Modifier.weight(1f))
                    Label(
                        "告警 ${overview.alertPercent.roundToInt()}% · 拦截 ${overview.blockPercent.roundToInt()}%",
                        9.5f,
                        V2Colors.ink(.40f),
                    )
                }
            }

            overview.plans.forEach { plan -> PlanCard(plan) }

            if (prices != null && prices.prices.isNotEmpty()) {
                PriceBookCard(prices)
            }
        }
    }
}

/** 官方价目：默认收起，展开后逐个模型列出输入/缓存读/输出单价。 */
@Composable
private fun PriceBookCard(prices: BillingPrices) {
    var open by remember { mutableStateOf(false) }
    WhiteCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { open = !open },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Label("官方 API 价目", 12.5f, V2Colors.ink(.90f), FontWeight.SemiBold, Modifier.weight(1f))
                Label(if (open) "收起" else "展开", 9.5f, V2Colors.cyan700)
            }
            Label(
                "基准 ${prices.asOf} · 每 1M tokens · 人民币按 1 USD≈${prices.cnyPerUsd} 折算",
                9f,
                V2Colors.ink(.38f),
                maxLines = 2,
            )
            if (open) {
                prices.prices.forEach { price ->
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Label("${price.model} · ${price.vendor}", 10f, V2Colors.ink(.72f), FontWeight.Medium, maxLines = 1)
                        Label(
                            "入 ${priceText(price.input)} · 缓存读 ${priceText(price.cacheRead)} · 出 ${priceText(price.output)} ${price.currency}/1M",
                            9f,
                            V2Colors.ink(.40f),
                            mono = true,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

/** 单价最多 4 位小数，去掉尾随 0。 */
private fun priceText(value: Double): String {
    if (value == 0.0) return "0"
    return String.format(Locale.US, "%.4f", value).trimEnd('0').trimEnd('.')
}

@Composable
private fun PlanCard(plan: BillingPlan) {
    WhiteCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Label(plan.label, 13f, V2Colors.ink(.90f), FontWeight.SemiBold, Modifier.weight(1f), maxLines = 1)
                if (plan.gate.enforce) {
                    LevelPill(if (plan.gate.blocked) "已拦截" else "闸门开启", V2Colors.rose)
                    Spacer(Modifier.width(6.dp))
                }
                LevelPill(levelLabel(plan.gate.level), levelTint(plan.gate.level))
            }
            val runtime = if (plan.runtimeIds.isEmpty()) "" else " · 运行时 ${plan.runtimeIds.joinToString(" / ")}"
            Label(
                "${plan.modelPrefix.ifBlank { "—" }}$runtime · ${sourceLabel(plan.officialSource)}",
                9.5f,
                V2Colors.ink(.38f),
                mono = true,
                maxLines = 1,
            )
            if (plan.gate.message.isNotBlank()) {
                Label(plan.gate.message, 10f, levelTint(plan.gate.level), maxLines = 2)
            }
            plan.windows.forEach { window -> WindowRow(window) }
        }
    }
}

@Composable
private fun WindowRow(window: BillingWindow) {
    val official = window.official
    val tint = officialTint(official)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Label(window.label, 10.5f, V2Colors.ink(.68f), FontWeight.Medium, Modifier.weight(1f), maxLines = 1)
            Label(officialLine(official), 10f, tint, maxLines = 1)
        }
        if (official?.percent != null && official.error.isNullOrBlank()) {
            ProgressBar(official.percent.roundToInt(), tint = tint, height = 4.dp)
        }
        val costText = if (window.local.cost > 0) {
            " · ≈$" + String.format(Locale.US, "%.2f", window.local.cost)
        } else {
            ""
        }
        Label(
            "本地 ${compactTokens(window.local.totalTokens)} tok · ${window.local.records} 次调用$costText",
            9.5f,
            V2Colors.ink(.38f),
            mono = true,
            maxLines = 1,
        )
    }
}

/** 官方行：有百分比就报百分比与重置时间，没有就说明为什么（未对账 / 失败）。 */
private fun officialLine(official: BillingOfficialWindow?): String = when {
    official == null -> "无官方快照"
    !official.error.isNullOrBlank() -> "对账失败"
    official.percent == null -> "无百分比"
    official.resetsAt.isNullOrBlank() -> String.format(Locale.US, "%.1f%%", official.percent)
    else -> String.format(Locale.US, "%.1f%% · %s", official.percent, shortTime(official.resetsAt))
}

private fun officialTint(official: BillingOfficialWindow?): Color {
    val percent = official?.percent ?: return V2Colors.ink(.38f)
    return when {
        percent >= 100 -> V2Colors.rose
        percent >= 80 -> V2Colors.amber
        else -> V2Colors.emerald
    }
}

private fun levelLabel(level: String): String = when (level) {
    "ok" -> "额度正常"
    "warning" -> "接近上限"
    "exceeded" -> "已超额"
    "disabled" -> "未启用"
    else -> "未对账"
}

private fun levelTint(level: String): Color = when (level) {
    "ok" -> V2Colors.emerald
    "warning" -> V2Colors.amber
    "exceeded" -> V2Colors.rose
    else -> V2Colors.ink(.42f)
}

private fun sourceLabel(source: String): String = when (source) {
    "opencode_go" -> "OpenCode Go 官方接口"
    "arkcli" -> "arkcli 对账"
    "cli" -> "CLI 对账"
    "http" -> "HTTP 额度接口"
    else -> "仅供记账"
}

@Composable
private fun LevelPill(text: String, tint: Color) {
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(tint.copy(alpha = .10f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Label(text, 8.5f, tint, maxLines = 1)
    }
}

/** ISO 时间只留到分钟：额度重置时间不需要秒。 */
private fun shortTime(value: String): String {
    val cleaned = value.replace('T', ' ')
    return if (cleaned.length >= 16) cleaned.substring(5, 16) else cleaned
}
