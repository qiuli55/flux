package top.qiuli55.flux.mobile.ui.v2

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

/**
 * 输入栏的两个小窗：Agent 胶囊 / 模型立方体。
 *
 * 两者共用同一套 Flux 卡片语言（圆角、描边、阴影、行尾勾选），只锚定在各自按钮上，
 * 不再使用 Material3 默认 DropdownMenu 的等宽菜单壳，也不做成半屏底栏。
 */

// ------------------------------------------------------------------ 副行文案（纯函数）

/**
 * Agent 小窗副行：该 Agent 实际会用的可读模型名；没有可用模型时返回 null（副行不渲染）。
 *
 * 真实切换口径（backend/task_engine/repository.py）：
 *  - `set_agent` / `apply_pending_agent` 都会清掉任务级 `model` / `api_key_id`，
 *    所以切到另一个 Agent 后用的是该 Agent 的第一个可用模型；
 *  - 排队中的目标（pendingAgentId）在本轮结束后走同一条路径，副行要显示即将生效的模型；
 *  - 当前 Agent 则跟着正在用的模型走（按 model + keyId 匹配可读 label）。
 */
internal fun agentMenuModelLabel(
    option: V2AgentOption,
    currentAgentId: String,
    currentModel: String,
    currentModelKeyId: String?,
    pendingAgentId: String?,
): String? {
    val models = option.models
    // 没有可用模型：不给空行，也不拿 role / 内部 id 凑数。
    if (models.isEmpty()) return null
    if (option.agent.id == pendingAgentId) {
        return models.firstOrNull()?.label?.takeIf { it.isNotBlank() }
    }
    if (option.agent.id == currentAgentId) {
        val inUse = models.firstOrNull { it.model == currentModel && it.keyId == currentModelKeyId }
        return (inUse?.label ?: currentModel).takeIf { it.isNotBlank() }
            ?: models.firstOrNull()?.label?.takeIf { it.isNotBlank() }
    }
    return models.firstOrNull()?.label?.takeIf { it.isNotBlank() }
}

/**
 * 模型小窗副行：只写真实状态。
 *
 * 模型切换是立即写入任务、下一条 Run 生效，没有「本轮结束后切换」的排队，
 * 因此这里只说「当前在用」和「密钥库」，不编造生效时机。
 */
internal fun modelMenuSubtitle(
    option: V2ModelOption,
    currentModel: String,
    currentModelKeyId: String?,
): String? {
    val inUse = option.model == currentModel && option.keyId == currentModelKeyId
    return when {
        inUse && option.keyId != null -> "当前在用 · 密钥库"
        inUse -> "当前在用"
        option.keyId != null -> "密钥库"
        else -> null
    }
}

// ------------------------------------------------------------------ 定位

/**
 * 贴着锚点（Agent 胶囊 / 模型立方体）定位的小窗：
 * 优先在锚点上方展开；上方空间不足时落到下方，最后兜底夹在窗口内。
 *
 * [insetPx] 是 Popup 内容给投影预留的内边距：窗口只有和内容一样大时，卡片外侧的
 * 阴影才不会被窗口裁掉，因此定位按「卡片本身」计算，再整体减去这圈留白。
 */
private class AnchoredMenuPositionProvider(
    private val alignToAnchorEnd: Boolean,
    private val gapPx: Int,
    private val marginPx: Int,
    private val insetPx: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val cardWidth = (popupContentSize.width - insetPx * 2).coerceAtLeast(0)
        val cardHeight = (popupContentSize.height - insetPx * 2).coerceAtLeast(0)

        val maxX = (windowSize.width - cardWidth - marginPx).coerceAtLeast(marginPx)
        val rawX = if (alignToAnchorEnd) {
            anchorBounds.right - cardWidth
        } else {
            anchorBounds.left
        }
        val x = rawX.coerceIn(marginPx, maxX)

        val maxY = (windowSize.height - cardHeight - marginPx).coerceAtLeast(marginPx)
        val above = anchorBounds.top - cardHeight - gapPx
        val below = anchorBounds.bottom + gapPx
        val y = when {
            above >= marginPx -> above
            below <= maxY -> below
            else -> maxY
        }
        return IntOffset(x - insetPx, y - insetPx)
    }
}

// ------------------------------------------------------------------ 共用卡片 / 行

/** 两个小窗共用的卡片壳：深色浅色同一套圆角、描边与阴影。 */
@Composable
internal fun FluxPickerMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    width: Dp,
    testTag: String,
    alignToAnchorEnd: Boolean = false,
    maxHeight: Dp = 360.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!expanded) return
    val density = LocalDensity.current
    val gapPx = with(density) { 10.dp.roundToPx() }
    val marginPx = with(density) { 12.dp.roundToPx() }
    val shadowInset = 14.dp
    val insetPx = with(density) { shadowInset.roundToPx() }
    val positionProvider = remember(alignToAnchorEnd, gapPx, marginPx, insetPx) {
        AnchoredMenuPositionProvider(alignToAnchorEnd, gapPx, marginPx, insetPx)
    }
    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = true),
    ) {
        Box {
            // 投影留白里点一下也关窗：这一圈属于 Popup 窗口，不接管就会变成死区。
            Box(
                Modifier
                    .matchParentSize()
                    .pointerInput(Unit) {
                        detectTapGestures { onDismissRequest() }
                    },
            )
            Surface(
                modifier = Modifier
                    .padding(shadowInset)
                    .width(width)
                    .testTag(testTag),
                shape = RoundedCornerShape(20.dp),
                color = V2Colors.surface,
                contentColor = V2Colors.ink,
                tonalElevation = 0.dp,
                shadowElevation = 16.dp,
                border = BorderStroke(1.dp, V2Colors.line),
            ) {
                Column(
                    modifier = Modifier
                        .heightIn(max = maxHeight)
                        .padding(6.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    content = content,
                )
            }
        }
    }
}

/**
 * 小窗里的一行：整行可点，选中行用底色 + 描边强调，勾选固定在行尾。
 * 最小高度 46dp，保证触摸目标不小于 44dp。
 */
@Composable
internal fun FluxPickerRow(
    selected: Boolean,
    testTag: String,
    onClick: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 46.dp)
            .testTag(testTag)
            .clip(shape)
            .background(if (selected) V2Colors.ink(.06f) else Color.Transparent)
            .then(
                if (selected) {
                    Modifier.border(1.dp, V2Colors.ink(.12f), shape)
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), content = content)
        Spacer(Modifier.width(6.dp))
        // 勾选固定占位：有选中时不会把名字挤走，也不会挤在名字后面。
        Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
            if (selected) {
                Icon(
                    Icons.Outlined.Check,
                    contentDescription = "使用中",
                    tint = V2Colors.ink(.85f),
                    modifier = Modifier.size(17.dp),
                )
            }
        }
    }
}

// ------------------------------------------------------------------ Agent 小窗

/** Agent 胶囊点开的小窗：官方运行时图标 + 名称 + 会实际使用的模型副行。 */
@Composable
internal fun FluxAgentMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    options: List<V2AgentOption>,
    currentAgentId: String,
    currentModel: String,
    currentModelKeyId: String?,
    pendingAgentId: String?,
    onPick: (String) -> Unit,
) {
    FluxPickerMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        width = 272.dp,
        testTag = V2ComposerTags.AGENT_MENU,
        alignToAnchorEnd = false,
    ) {
        options.forEach { option ->
            val agent = option.agent
            val selected = agent.id == currentAgentId
            val pending = agent.id == pendingAgentId
            val subtitle = agentMenuModelLabel(
                option = option,
                currentAgentId = currentAgentId,
                currentModel = currentModel,
                currentModelKeyId = currentModelKeyId,
                pendingAgentId = pendingAgentId,
            )
            FluxPickerRow(
                selected = selected,
                testTag = V2ComposerTags.AGENT_MENU_ROW_PREFIX + agent.id,
                onClick = {
                    onDismissRequest()
                    onPick(agent.id)
                },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AgentRuntimeIcon(agent.runtime, size = 20.dp)
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Label(
                                agent.name,
                                13f,
                                V2Colors.ink(.92f),
                                FontWeight.Medium,
                                modifier = Modifier.weight(1f, fill = false),
                                maxLines = 1,
                            )
                            if (!agent.nativeStreaming) {
                                Spacer(Modifier.width(6.dp))
                                NonStreamingBadge()
                            }
                        }
                        if (subtitle != null) {
                            Spacer(Modifier.height(2.dp))
                            Label(subtitle, 10.5f, V2Colors.ink(.44f), maxLines = 1)
                        }
                        if (pending) {
                            Spacer(Modifier.height(3.dp))
                            Label("本轮结束后切换", 9.5f, V2Colors.amber700, mono = true)
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ 模型小窗

/** 模型立方体点开的小窗：只列当前 Agent 的可用模型，主行是可读 label。 */
@Composable
internal fun FluxModelMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    options: List<V2ModelOption>,
    currentModel: String,
    currentModelKeyId: String?,
    onPick: (V2ModelOption) -> Unit,
) {
    FluxPickerMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        width = 240.dp,
        testTag = V2ComposerTags.MODEL_MENU,
        alignToAnchorEnd = true,
        maxHeight = 320.dp,
    ) {
        options.forEach { option ->
            val selected = option.model == currentModel && option.keyId == currentModelKeyId
            val subtitle = modelMenuSubtitle(option, currentModel, currentModelKeyId)
            FluxPickerRow(
                selected = selected,
                testTag = V2ComposerTags.MODEL_MENU_ROW_PREFIX +
                    option.model + "|" + (option.keyId ?: "native"),
                onClick = {
                    onDismissRequest()
                    onPick(option)
                },
            ) {
                Label(option.label, 13f, V2Colors.ink(.90f), mono = true, maxLines = 1)
                if (subtitle != null) {
                    Spacer(Modifier.height(2.dp))
                    Label(subtitle, 10.5f, V2Colors.ink(.44f), maxLines = 1)
                }
            }
        }
    }
}
