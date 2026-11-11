package top.qiuli55.flux.mobile.ui.v2

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** 权限模式的展示元数据：颜色与一句话说明。 */
internal data class PermissionModeMeta(
    val mode: String,
    val name: String,
    val subtitle: String,
    val color: Color,
)

internal fun permissionModeMeta(mode: String): PermissionModeMeta = when (mode) {
    "plan" -> PermissionModeMeta(
        mode = "plan",
        name = "Plan 模式",
        subtitle = "只读代码与设计稿，产出计划文档；禁 shell、禁设计写入",
        color = V2Colors.indigo,
    )
    "unattended" -> PermissionModeMeta(
        mode = "unattended",
        name = "免审核",
        subtitle = "全部自动放行，适合无人值守长任务；选前请确认任务范围可控",
        color = V2Colors.rose700,
    )
    "full" -> PermissionModeMeta(
        mode = "full",
        name = "完全访问",
        subtitle = "跳过逐步审批，可直写工作区与执行命令；受保护对象仍需你逐次点头",
        color = V2Colors.rose,
    )
    else -> PermissionModeMeta(
        mode = "normal",
        name = "正常模式",
        subtitle = "低风险命令自动放行，高风险命令会停下来等你允许",
        color = V2Colors.accent,
    )
}

/** 输入框上方的常驻模式胶囊：状态点 + 模式名 + 一句效果 + 展开箭头。 */
@Composable
internal fun PermissionModePill(mode: String, enabled: Boolean = true, onClick: () -> Unit) {
    val meta = permissionModeMeta(mode)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(meta.color.copy(alpha = .07f))
            .border(1.dp, meta.color.copy(alpha = .22f), RoundedCornerShape(999.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).background(meta.color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Label(meta.name, 9.5f, meta.color, weight = FontWeight.Medium)
        Spacer(Modifier.width(6.dp))
        Box(Modifier.width(1.dp).height(8.dp).background(meta.color.copy(alpha = .25f)))
        Spacer(Modifier.width(6.dp))
        Label(
            if (mode == "plan") "只写计划"
            else if (mode == "unattended") "全部自动放行"
            else if (mode == "full") "审批已跳过"
            else "高风险才问",
            9f,
            V2Colors.ink(.40f),
            modifier = Modifier.weight(1f),
        )
        Label("切换只影响后续消息", 8f, V2Colors.ink(.26f), mono = true)
        Spacer(Modifier.width(3.dp))
        Icon(
            Icons.Outlined.KeyboardArrowDown,
            null,
            tint = meta.color.copy(alpha = .7f),
            modifier = Modifier.size(12.dp),
        )
    }
}

/** 完全访问 / 免审核模式的红色提示条：提醒当前没有逐步审批。 */
@Composable
internal fun FullAccessNotice(mode: String = "full") {
    val unattended = mode == "unattended"
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(V2Colors.rose.copy(alpha = .08f))
            .border(1.dp, V2Colors.rose.copy(alpha = .22f), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Security, null, tint = V2Colors.rose, modifier = Modifier.size(11.dp))
        Spacer(Modifier.width(6.dp))
        Label(
            if (unattended) {
                "免审核 · 高风险命令与密钥/系统服务操作都会自动放行，无人把关，请确认任务范围"
            } else {
                "完全访问 · 高风险命令不再逐条询问；密钥文件与系统服务仍会问你"
            },
            8.5f,
            V2Colors.rose700,
        )
    }
}

/** 四选一面板：大标题 + 单选列表 + 底部说明受保护对象的边界。 */
@Composable
internal fun PermissionModeSheet(
    mode: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    SheetFrame(onDismiss = onDismiss, maxHeightFraction = .88f) {
        Column(
            Modifier
                .weight(1f, fill = false)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(6.dp))
            Label("权限模式", 21f, V2Colors.ink(.95f), FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Label("只影响后续消息；正在跑的一轮按原模式跑完", 12.5f, V2Colors.ink(.40f))
            Spacer(Modifier.height(10.dp))
            listOf("plan", "normal", "full", "unattended").forEachIndexed { index, item ->
                PermissionModeOption(
                    meta = permissionModeMeta(item),
                    selected = item == mode,
                    onClick = { onSelect(item) },
                )
                if (index != 3) ThinDivider(V2Colors.ink(.06f))
            }
            Spacer(Modifier.height(14.dp))
            Label(
                "Plan / 正常 / 完全访问模式下，受保护对象（.env、*.pem、SSH 私钥、系统服务）都要你逐次允许，" +
                    "上一次允许不会自动延续。只有免审核模式会连受保护对象一起自动放行，选它前请确认任务范围可控。",
                10.5f,
                V2Colors.ink(.42f),
            )
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun PermissionModeOption(
    meta: PermissionModeMeta,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Label(meta.name, 15f, V2Colors.ink(.92f), weight = FontWeight.Medium)
                if (meta.mode == "unattended") {
                    Spacer(Modifier.width(6.dp))
                    Label("风险自负", 11f, V2Colors.rose, weight = FontWeight.Medium)
                }
            }
            Spacer(Modifier.height(3.dp))
            Label(meta.subtitle, 11.5f, V2Colors.ink(.42f))
        }
        if (selected) {
            Spacer(Modifier.width(12.dp))
            Icon(Icons.Outlined.Check, null, tint = V2Colors.ink(.85f), modifier = Modifier.size(18.dp))
        }
    }
}

/** 计划卡：白卡 + 文档图标；标题行点击展开/收起步骤、涉及文件与计划文档路径。 */
@Composable
internal fun PlanCardMessage(card: V2PlanCard, time: String) {
    var expanded by remember(card.planPath, card.title) { mutableStateOf(false) }
    val visibleSteps = if (expanded) card.steps else card.steps.take(2)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(V2Colors.surface(.82f))
            .border(1.dp, V2Colors.ink(.08f), RoundedCornerShape(18.dp))
            .padding(12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(V2Colors.indigo.copy(alpha = .12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Description,
                    null,
                    tint = V2Colors.indigo,
                    modifier = Modifier.size(16.dp),
                )
            }
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Label("实施计划", 8.5f, V2Colors.indigo, mono = true)
                Spacer(Modifier.height(2.dp))
                Label(card.title, 12f, V2Colors.ink(.90f), weight = FontWeight.SemiBold)
            }
            Label("${card.steps.size} 步", 8.5f, V2Colors.ink(.32f), mono = true)
            Spacer(Modifier.width(3.dp))
            Icon(
                if (expanded) Icons.Outlined.KeyboardArrowDown else Icons.Outlined.KeyboardArrowRight,
                null,
                tint = V2Colors.ink(.30f),
                modifier = Modifier.size(13.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        visibleSteps.forEachIndexed { index, step ->
            Row(Modifier.padding(vertical = 2.dp)) {
                Box(
                    Modifier
                        .size(17.dp)
                        .clip(CircleShape)
                        .background(V2Colors.indigo.copy(alpha = .10f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Label("${index + 1}", 8f, V2Colors.indigo, mono = true)
                }
                Spacer(Modifier.width(7.dp))
                Column(Modifier.weight(1f)) {
                    Label(step.title, 10.5f, V2Colors.ink(.78f))
                    if (expanded && !step.detail.isNullOrBlank()) {
                        Spacer(Modifier.height(1.dp))
                        Label(step.detail, 9f, V2Colors.ink(.45f))
                    }
                }
            }
        }
        if (!expanded && card.steps.size > visibleSteps.size) {
            Label("还有 ${card.steps.size - visibleSteps.size} 步，点标题展开", 8.5f, V2Colors.ink(.35f))
        }
        if (expanded && card.files.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                card.files.take(4).forEach { file ->
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(V2Colors.ink(.05f))
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                    ) {
                        Label(file.substringAfterLast('/'), 8f, V2Colors.ink(.50f), mono = true)
                    }
                }
            }
        }
        if (expanded && card.markdown?.isNotBlank() == true) {
            Spacer(Modifier.height(6.dp))
            Label(card.markdown, 9.5f, V2Colors.ink(.55f))
        }
        if (expanded && card.planPath.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Label("计划文档 · ${card.planPath}", 8f, V2Colors.ink(.30f), mono = true)
        }
        Spacer(Modifier.height(4.dp))
        Label(time, 8.5f, V2Colors.ink(.22f))
    }
}

/**
 * 审批卡：高风险命令（正常模式）或受保护操作（任何模式）。
 * 点「允许」= 仅本次放行；点「拒绝」= 命令不执行，Agent 收到明确错误。
 */
@Composable
internal fun ApprovalCardMessage(
    card: V2ApprovalCard,
    time: String,
    onAllow: (String) -> Unit,
    onDeny: (String) -> Unit,
) {
    val protected = card.kind == "protected_access"
    val tone = if (protected) V2Colors.rose else V2Colors.amber
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(V2Colors.surface(.84f))
            .border(1.dp, tone.copy(alpha = .30f), RoundedCornerShape(18.dp))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(tone.copy(alpha = .12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Security, null, tint = tone, modifier = Modifier.size(15.dp))
            }
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Label(
                    if (protected) "受保护操作 · 任何模式都要你确认" else "高风险命令 · 需要你确认",
                    10.5f,
                    tone,
                    weight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
                Label(
                    if (protected) "密钥文件与系统服务不会自动放行" else "正常模式下这类命令不会自动执行",
                    8.5f,
                    V2Colors.ink(.38f),
                )
            }
            Label(time, 8.5f, V2Colors.ink(.22f), mono = true)
        }
        if (card.command.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF101012))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Label(card.command, 9.5f, Color(0xFFE4E4E7), mono = true)
            }
        }
        if (card.reason.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Label(card.reason, 9.5f, V2Colors.ink(.55f))
        }
        Spacer(Modifier.height(9.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SheetAction(
                text = "拒绝",
                primary = false,
                onClick = { onDeny(card.id) },
            )
            SheetAction(
                text = if (protected) "仅本次允许" else "允许执行",
                primary = true,
                onClick = { onAllow(card.id) },
                tint = tone,
            )
        }
    }
}

/** 审批停靠条：固定在输入框上方，AI 输出再长也能直接点同意。 */
@Composable
internal fun ApprovalDock(
    card: V2ApprovalCard,
    busy: Boolean,
    onAllow: (String) -> Unit,
    onDeny: (String) -> Unit,
) {
    val protected = card.kind == "protected_access"
    val tone = if (protected) V2Colors.rose else V2Colors.amber
    // 高危命令可能很长：默认只露两行，点一下看全，避免没看清就点同意
    var showFull by remember(card.id) { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(V2Colors.surface(.95f))
            .border(1.dp, tone.copy(alpha = .42f), RoundedCornerShape(14.dp))
            .padding(horizontal = 11.dp, vertical = 9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Security, null, tint = tone, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(6.dp))
            Label(
                if (protected) "受保护操作 · 需要你确认" else "高风险命令 · 需要你确认",
                10f,
                tone,
                weight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (busy) {
                Label("提交中…", 8f, tone, mono = true)
            } else {
                Label("仅本次有效", 8f, V2Colors.ink(.30f), mono = true)
            }
        }
        if (card.command.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(9.dp))
                    .background(Color(0xFF101012))
                    .clickable { showFull = !showFull }
                    .padding(horizontal = 9.dp, vertical = 6.dp),
            ) {
                Label(card.command, 9f, Color(0xFFE4E4E7), mono = true, maxLines = if (showFull) 14 else 2)
            }
            if (!showFull && card.command.length > 90) {
                Spacer(Modifier.height(3.dp))
                Label("点命令可展开全文", 8f, V2Colors.ink(.32f), mono = true)
            }
        }
        if (card.reason.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Label(card.reason, 8.5f, V2Colors.ink(.46f), maxLines = 2)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DockAction("拒绝", primary = false, enabled = !busy, modifier = Modifier.weight(1f)) {
                onDeny(card.id)
            }
            DockAction(
                if (protected) "仅本次允许" else "允许执行",
                primary = true,
                enabled = !busy,
                modifier = Modifier.weight(1f),
                tint = tone,
            ) {
                onAllow(card.id)
            }
        }
    }
}

@Composable
private fun DockAction(
    text: String,
    primary: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    tint: Color = V2Colors.accent,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (primary) tint else V2Colors.surface(.70f))
            .border(1.dp, if (primary) Color.Transparent else V2Colors.ink(.10f), RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Label(text, 9.5f, if (primary) tint.foreground() else V2Colors.ink(.62f), weight = FontWeight.Medium)
    }
}

/** 审批结果行：已允许 / 已拒绝 / 已超时；不再提供按钮。 */
@Composable
internal fun ApprovalResultRow(card: V2ApprovalCard, time: String) {
    val (text, tone) = when {
        card.expired || card.status == "expired" -> "审批已超时 · 命令未执行" to V2Colors.ink(.38f)
        card.status == "resolved" && card.chosen == "允许" -> "已允许本次执行 · 仅此一次" to V2Colors.emerald700
        card.status == "resolved" -> "已选择「${card.chosen.orEmpty()}」" to V2Colors.cyan700
        card.status == "rejected" -> "已拒绝 · 命令未执行" to V2Colors.rose
        else -> "审批记录" to V2Colors.ink(.38f)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(V2Colors.ink(.035f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Security, null, tint = tone, modifier = Modifier.size(11.dp))
        Spacer(Modifier.width(6.dp))
        Label(text, 9f, tone, mono = true)
        Spacer(Modifier.weight(1f))
        Label(time, 8f, V2Colors.ink(.22f), mono = true)
    }
}

/** 面板里的主/次操作按钮（与其它 Sheet 保持同一手感）。 */
@Composable
private fun SheetAction(
    text: String,
    primary: Boolean,
    onClick: () -> Unit,
    tint: Color = V2Colors.accent,
) {
    Box(
        Modifier
            .widthIn(min = 96.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (primary) tint else V2Colors.surface(.70f))
            .border(1.dp, if (primary) tint else V2Colors.ink(.10f), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Label(
            text,
            10f,
            if (primary) tint.foreground() else V2Colors.ink(.62f),
            weight = FontWeight.Medium,
        )
    }
}

/** 主色底上的前景：亮底(深色模式的白主色/琥珀)配墨色，暗底配白字。 */
private fun Color.foreground(): Color =
    if (luminance() > .55f) V2Colors.ink else Color.White
