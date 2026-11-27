package top.qiuli55.flux.mobile.ui.v2

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import top.qiuli55.flux.mobile.data.BillingAccount
import top.qiuli55.flux.mobile.data.BillingOfficialWindow
import top.qiuli55.flux.mobile.data.BillingOverview
import top.qiuli55.flux.mobile.data.BillingPlan
import top.qiuli55.flux.mobile.data.BillingPrices
import top.qiuli55.flux.mobile.data.BillingWindow
import top.qiuli55.flux.mobile.data.compactTokens
import java.util.Locale

/**
 * 套餐额度弹层：官方占用率与 Flux 本地记账并排看。
 *
 * 有官方接口的套餐（OpenCode Go / 火山 Agent Plan / Codex 订阅）显示官方百分比；
 * Claude 订阅目前没有可用的官方额度接口，只显示「本地记账」，不假装有官方数据。
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
            subtitle = "有官方额度的套餐显示官方占用率；没有官方接口的套餐只统计 Flux 本地调用",
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
                    text = if (loading) "读取中…" else "重新读取",
                    onClick = onRefresh,
                    modifier = Modifier.weight(1f),
                    icon = Icons.Outlined.Refresh,
                    enabled = !loading,
                )
                PrimaryButton(
                    text = if (loading) "更新中…" else "更新官方额度",
                    onClick = onSync,
                    modifier = Modifier.weight(1f),
                    icon = Icons.Outlined.Sync,
                    enabled = !loading,
                )
            }

            if (!error.isNullOrBlank()) {
                WhiteCard(Modifier.fillMaxWidth()) {
                    Label(error, 10.5f, V2Colors.rose, maxLines = 4, modifier = Modifier.padding(14.dp))
                }
            }

            if (overview == null) {
                WhiteCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Label(if (loading) "正在读取额度…" else "还没有额度数据", 12.5f, V2Colors.ink(.80f), FontWeight.Medium)
                        Label(
                            "有官方额度：OpenCode Go、火山 Agent Plan、Codex 订阅（在「我的 → 订阅与账号」登录 ChatGPT 后自动采集）。",
                            10.5f,
                            V2Colors.ink(.46f),
                        )
                        Label(
                            "仅本地记账：OpenCode Zen、Claude 订阅（官方暂无额度接口）。",
                            10.5f,
                            V2Colors.ink(.46f),
                        )
                        Label(
                            "调用会按模型前缀或当前运行时自动归到对应套餐。",
                            10.5f,
                            V2Colors.ink(.40f),
                        )
                    }
                }
                return@Column
            }

            // 顶部环形总览：选占用率最高的「套餐 + 窗口」，状态文案也用同一份数据。
            val highlight = billingHighlight(overview)
            if (highlight != null) {
                QuotaGauge(
                    percent = highlight.percent,
                    tint = levelTint(highlight.level),
                    caption = "${highlight.planLabel} · ${highlight.windowLabel} · ${levelLabel(highlight.level)}",
                    enabled = overview.enabled,
                )
            } else {
                LocalUsageCard(overview)
            }

            WhiteCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Label(
                        if (overview.enabled) "额度统计已开启" else "额度统计已关闭",
                        11f,
                        V2Colors.ink(.72f),
                        FontWeight.Medium,
                    )
                    Label(billingThresholdNote(overview), 9.5f, V2Colors.ink(.42f))
                }
            }

            overview.plans.forEach { plan -> PlanCard(plan) }

            if (prices != null && prices.prices.isNotEmpty()) {
                PriceBookCard(prices)
            }
        }
    }
}

/** 环形额度总览：一个百分比 + 套餐/窗口/状态文案。 */
@Composable
private fun QuotaGauge(percent: Double, tint: Color, caption: String, enabled: Boolean) {
    val fraction = (percent / 100.0).coerceIn(0.0, 1.0).toFloat()
    Column(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(140.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 10.dp.toPx()
                val inset = stroke / 2f
                val arcSize = Size(size.width - stroke, size.height - stroke)
                drawArc(
                    color = V2Colors.ink(.07f),
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
                drawArc(
                    color = tint,
                    startAngle = -90f,
                    sweepAngle = 360f * fraction,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Label("${percent.roundToInt()}%", 30f, V2Colors.ink(.92f), FontWeight.Bold)
                if (!enabled) Label("额度统计已关闭", 10f, V2Colors.ink(.35f))
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(tint))
            Spacer(Modifier.width(6.dp))
            Label(caption, 12f, tint, FontWeight.Medium)
        }
    }
}

/** 没有官方百分比时不画环，只说明「暂无官方占用率」并给出本月本地用量。 */
@Composable
private fun LocalUsageCard(overview: BillingOverview) {
    WhiteCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Label("暂无官方占用率", 12.5f, V2Colors.ink(.80f), FontWeight.Medium)
            Label(billingLocalMonthSummary(overview), 11f, V2Colors.ink(.56f))
            Label(
                "接入官方额度后会在这里显示百分比；当前只展示 Flux 自己的调用记账。",
                9.5f,
                V2Colors.ink(.38f),
            )
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
                "基准 ${prices.asOf} · 每百万 token · 人民币按 1 USD≈${prices.cnyPerUsd} 折算",
                9f,
                V2Colors.ink(.38f),
                maxLines = 2,
            )
            if (open) {
                prices.prices.forEach { price ->
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Label("${price.model} · ${price.vendor}", 10f, V2Colors.ink(.72f), FontWeight.Medium, maxLines = 1)
                        Label(
                            "输入 ${priceText(price.input)} · 缓存读 ${priceText(price.cacheRead)} · 输出 ${priceText(price.output)} ${price.currency}/百万",
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
    var showOtherAccounts by remember(plan.key) { mutableStateOf(false) }
    WhiteCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Label(plan.label, 13f, V2Colors.ink(.90f), FontWeight.SemiBold, Modifier.weight(1f), maxLines = 1)
                val planType = billingPlanTypeLabel(plan.planType)
                if (planType.isNotBlank()) {
                    LevelPill(planType, V2Colors.ink(.58f))
                    Spacer(Modifier.width(6.dp))
                }
                if (plan.gate.enforce) {
                    LevelPill(if (plan.gate.blocked) "已暂停新任务" else "超额会阻止新任务", V2Colors.rose)
                    Spacer(Modifier.width(6.dp))
                }
                LevelPill(levelLabel(plan.gate.level), levelTint(plan.gate.level))
            }
            val runtime = if (plan.runtimeIds.isEmpty()) "" else " · 运行时 ${plan.runtimeIds.joinToString(" / ")}"
            Label(
                "${plan.modelPrefix.ifBlank { "—" }}$runtime · ${sourceLabel(plan.officialSource)}",
                9.5f,
                V2Colors.ink(.38f),
                maxLines = 2,
            )
            val gateNote = billingGateNote(plan)
            if (gateNote.isNotBlank()) {
                Label(gateNote, 10f, levelTint(plan.gate.level), maxLines = 2)
            }
            plan.windows.forEach { window -> WindowRow(window) }
            val others = billingOtherAccounts(plan)
            if (others.isNotEmpty()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { showOtherAccounts = !showOtherAccounts },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Label("其他账号（${others.size}）", 10f, V2Colors.ink(.58f), FontWeight.Medium, Modifier.weight(1f))
                    Label(if (showOtherAccounts) "收起" else "展开", 9.5f, V2Colors.cyan700)
                }
                if (showOtherAccounts) {
                    others.forEach { account -> OtherAccountRow(account) }
                }
            }
        }
    }
}

/**
 * 多账号展示：只有一个账号时不占地方；多于一个时把非当前账号折叠到次级区域。
 */
internal fun billingOtherAccounts(plan: BillingPlan): List<BillingAccount> =
    if (plan.accounts.size > 1) plan.accounts.filterNot { it.isCurrent } else emptyList()

/** 历史账号：当前账号已经在套餐卡主体里，这里只做次级展示。 */
@Composable
private fun OtherAccountRow(account: BillingAccount) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Label(account.label.ifBlank { "历史账号" }, 9.5f, V2Colors.ink(.62f), FontWeight.Medium, Modifier.weight(1f))
            LevelPill(levelLabel(account.gate.level), levelTint(account.gate.level))
        }
        account.windows.forEach { window ->
            Label(billingAccountWindowLine(window), 9f, V2Colors.ink(.40f), maxLines = 1)
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
            Label(billingOfficialLine(official), 10f, tint, maxLines = 2)
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
            "本地记账 ${compactTokens(window.local.totalTokens)} token · ${window.local.records} 次调用$costText",
            9.5f,
            V2Colors.ink(.38f),
            maxLines = 1,
        )
        val external = window.external
        if (external != null && external.officialIncrease > 0) {
            val mixed = if (external.mixedPoints > 0) {
                " · 另有 ${priceText(external.mixedPoints)} 个百分点与 Flux 并发、无法拆分"
            } else {
                ""
            }
            Label(
                "其他客户端约 +${priceText(external.points)} 个百分点（Flux 空闲时段）$mixed",
                9.5f,
                V2Colors.ink(.44f),
                maxLines = 2,
            )
        }
    }
}

/**
 * 顶部环形图选中的「套餐 + 窗口 + 占用率」。
 *
 * 百分比与状态必须来自同一条官方窗口记录，不能拿 A 套餐的百分比配 B 套餐的闸门状态。
 */
internal data class BillingHighlight(
    val planKey: String,
    val planLabel: String,
    val windowKey: String,
    val windowLabel: String,
    val percent: Double,
    val level: String,
)

/** 取所有套餐官方窗口里占用率最高的一条；失败/无百分比的窗口不参与。 */
internal fun billingHighlight(overview: BillingOverview): BillingHighlight? {
    val candidates = overview.plans.flatMap { plan ->
        plan.windows.mapNotNull { window ->
            val official = window.official ?: return@mapNotNull null
            if (!official.error.isNullOrBlank()) return@mapNotNull null
            val percent = official.percent ?: return@mapNotNull null
            BillingHighlight(
                planKey = plan.key,
                planLabel = plan.label,
                windowKey = window.key,
                windowLabel = window.label,
                percent = percent,
                level = if (overview.enabled) {
                    billingLevel(percent, overview.alertPercent, overview.blockPercent)
                } else {
                    "disabled"
                },
            )
        }
    }
    return candidates.maxByOrNull { it.percent }
}

/** 用与后端相同的提醒 / 拦截阈值判定状态。 */
internal fun billingLevel(percent: Double, alertPercent: Double, blockPercent: Double): String = when {
    blockPercent > 0 && percent >= blockPercent -> "exceeded"
    alertPercent > 0 && percent >= alertPercent -> "warning"
    else -> "ok"
}

/** 没有官方百分比时，改展示本月本地记账（跨套餐合计）。 */
internal fun billingLocalMonthSummary(overview: BillingOverview): String {
    val months = overview.plans.mapNotNull { plan ->
        plan.windows.firstOrNull { it.key == "monthly" }
    }
    val records = months.sumOf { it.local.records }
    if (records <= 0) return "本月还没有 Flux 本地调用记录"
    val tokens = months.sumOf { it.local.totalTokens }
    val cost = months.sumOf { it.local.cost }
    val costText = if (cost > 0) " · ≈$" + String.format(Locale.US, "%.2f", cost) else ""
    return "本月 Flux 本地调用 $records 次 · ${compactTokens(tokens)} token$costText"
}

/** 额度阈值说明：不再用「告警 / 拦截百分比」这类内部术语。 */
internal fun billingThresholdNote(overview: BillingOverview): String {
    val alert = overview.alertPercent.roundToInt()
    val block = overview.blockPercent.roundToInt()
    return if (overview.enforce) {
        "用到 $alert% 会提醒，到 $block% 会阻止新任务"
    } else {
        "用到 $alert% 会提醒；达到上限也不会阻止任务"
    }
}

/** 官方行：有百分比就报百分比与重置时间，没有就说明为什么。 */
internal fun billingOfficialLine(official: BillingOfficialWindow?): String = when {
    official == null -> "暂无官方数据"
    !official.error.isNullOrBlank() -> "暂时取不到官方额度：${official.error}"
    official.percent == null -> "官方未返回百分比"
    official.resetsAt.isNullOrBlank() -> String.format(Locale.US, "%.1f%%", official.percent)
    else -> String.format(Locale.US, "%.1f%% · %s 重置", official.percent, shortTime(official.resetsAt))
}

/** 把后端的闸门消息换成普通用户能看懂的话（窗口键也翻成中文）。 */
internal fun billingGateNote(plan: BillingPlan): String {
    val gate = plan.gate
    if (gate.message.isBlank()) return ""
    if (gate.level != "warning" && gate.level != "exceeded") return gate.message
    val windowLabel = plan.windows.firstOrNull { it.key == gate.windowKey }?.label
        ?: gate.windowKey.ifBlank { "额度窗口" }
    val percent = gate.percent?.roundToInt()
    val state = if (gate.level == "exceeded") "已超过上限" else "接近上限"
    val base = if (percent != null) {
        "「$windowLabel」已用 $percent%，$state"
    } else {
        "「$windowLabel」$state"
    }
    return if (plan.fallbackModel.isBlank()) base else "$base，可以把模型换成 ${plan.fallbackModel}"
}

/** 历史账号的窗口摘要：官方与本地各一句，避免展开后信息过载。 */
internal fun billingAccountWindowLine(window: BillingWindow): String {
    val official = window.official
    val officialText = if (official?.error.isNullOrBlank() && official?.percent != null) {
        "官方 ${official.percent.roundToInt()}%"
    } else {
        "官方暂无数据"
    }
    val localText = if (window.local.records > 0) {
        "本地 ${compactTokens(window.local.totalTokens)} token"
    } else {
        "本地无调用"
    }
    return "${window.label} · $officialText · $localText"
}

internal fun billingPlanTypeLabel(raw: String): String = when (raw.trim().lowercase(Locale.US)) {
    "" -> ""
    "plus" -> "Plus"
    "pro" -> "Pro"
    "team" -> "Team"
    "enterprise" -> "Enterprise"
    "free" -> "Free"
    else -> raw.trim()
}

internal fun officialLine(official: BillingOfficialWindow?): String = billingOfficialLine(official)

internal fun sourceLabel(source: String): String = when (source) {
    "opencode_go" -> "OpenCode Go 官方额度"
    "arkcli" -> "火山 Agent Plan 官方额度"
    "chatgpt_codex" -> "ChatGPT 官方额度"
    "cli" -> "官方额度（本机命令）"
    "http" -> "官方额度（HTTP 接口）"
    "" -> "暂无官方额度 · 只按本地记账"
    else -> "官方额度（$source）"
}

private fun officialTint(official: BillingOfficialWindow?): Color {
    if (!official?.error.isNullOrBlank()) return V2Colors.rose
    val percent = official?.percent ?: return V2Colors.ink(.38f)
    return when {
        percent >= 100 -> V2Colors.rose
        percent >= 80 -> V2Colors.amber
        else -> V2Colors.emerald
    }
}

internal fun levelLabel(level: String): String = when (level) {
    "ok" -> "额度正常"
    "warning" -> "接近上限"
    "exceeded" -> "已超额"
    "disabled" -> "额度统计已关闭"
    else -> "暂无官方数据"
}

internal fun levelTint(level: String): Color = when (level) {
    "ok" -> V2Colors.emerald
    "warning" -> V2Colors.amber
    "exceeded" -> V2Colors.rose
    else -> V2Colors.ink(.42f)
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
