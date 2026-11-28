package top.qiuli55.flux.mobile.ui.v2

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.qiuli55.flux.mobile.data.CapabilityCandidate
import top.qiuli55.flux.mobile.data.CapabilityScanReport
import top.qiuli55.flux.mobile.data.ImportedCapability

// ---------------------------------------------------------------- 可测逻辑

/** 添加面板只展示扫描结果里的技能候选；Agent / Connector 扫描项不混进来。 */
internal fun skillScanCandidates(report: CapabilityScanReport?): List<CapabilityCandidate> =
    report?.candidates?.filter { it.kind == "skill" } ?: emptyList()

/** 被安全拦截的候选不能导入；返回给用户看的中文原因。 */
internal fun skillImportBlocker(candidate: CapabilityCandidate): String? =
    if (candidate.status == "blocked") {
        val reasons = candidate.findings.mapNotNull { it.reason.takeIf(String::isNotBlank) }
        if (reasons.isEmpty()) "未通过安全检查，不能导入" else "未通过安全检查：" + reasons.joinToString("；")
    } else {
        null
    }

/** 技能详情行：来源 / 版本 / 指纹，全是只读事实。 */
internal fun skillDetailLines(item: ImportedCapability): List<String> = buildList {
    if (item.description.isNotBlank()) add(item.description)
    add(if (item.enabled) "状态：已启用（会自动进入执行指令）" else "状态：已停用（不会自动使用）")
    if (!item.version.isNullOrBlank()) add("版本：${item.version}")
    if (item.source.isNotBlank()) add("来源：${item.source}")
}

/** 启用 / 停用的即时文案：操作落库前先用它给无障碍与测试一个稳定锚点。 */
internal fun skillToggleLabel(enabled: Boolean): String = if (enabled) "已启用" else "已停用"

/** 移除确认文案：明确不会删除用户磁盘上的源目录。 */
internal fun skillRemoveMessage(name: String): String =
    "从技能注册表移除「$name」？这不会删除电脑上的原始技能目录。"

// ---------------------------------------------------------------- 页面

/**
 * 技能管理：列表 / 启用停用 / 查看详情 / 移除 / 添加。
 *
 * 数据来自 `/api/v1/capabilities?kind=skill`，与桌面 Skills 页、Web SkillsPage 共用。
 * 电脑离线时列表仍可浏览（用缓存），扫描 / 导入会明确说明当前用不了。
 */
@Composable
fun SkillsScreen(
    capabilities: List<ImportedCapability>,
    loading: Boolean,
    error: String?,
    scanning: Boolean,
    scanReport: CapabilityScanReport?,
    scanError: String?,
    extraTop: Dp,
    showBack: Boolean,
    onBack: () -> Unit,
    onReload: () -> Unit,
    onToggle: (ImportedCapability, Boolean) -> Unit,
    onRemove: (ImportedCapability) -> Unit,
    onScan: () -> Unit,
    onImport: (CapabilityCandidate) -> Unit,
    onCloseScan: () -> Unit,
    autoScan: Boolean = false,
) {
    var expanded by remember { mutableStateOf<String?>(null) }
    var pendingRemove by remember { mutableStateOf<ImportedCapability?>(null) }
    LaunchedEffect(autoScan) {
        if (autoScan) onScan()
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = extraTop + 6.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showBack) {
                RoundIconButton(
                    Icons.Outlined.ArrowBack,
                    onBack,
                    size = 34.dp,
                    iconSize = 16.dp,
                    contentDescription = "返回",
                )
                Spacer(Modifier.width(10.dp))
            }
            Label(
                "技能",
                22f,
                V2Colors.ink(.95f),
                FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            RoundIconButton(
                Icons.Outlined.Refresh,
                onReload,
                size = 34.dp,
                iconSize = 16.dp,
                contentDescription = "刷新技能",
            )
            Spacer(Modifier.width(6.dp))
            RoundIconButton(
                Icons.Outlined.Add,
                onScan,
                size = 34.dp,
                iconSize = 17.dp,
                contentDescription = "添加技能",
            )
        }
        Label(
            "已启用的技能会随对话交给 Agent；停用后不再自动使用，仍可在这里随时打开。",
            12f,
            V2Colors.ink(.42f),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        if (!error.isNullOrBlank()) {
            Label(
                "读取失败：$error（显示的是上一次缓存）",
                10f,
                V2Colors.rose,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }

        if (loading && capabilities.isEmpty()) {
            Label(
                "正在读取技能…",
                12f,
                V2Colors.ink(.42f),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp),
            )
        }

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp, end = 16.dp, top = 6.dp, bottom = 24.dp,
            ),
        ) {
            if (capabilities.isEmpty() && !loading) {
                item {
                    Column(Modifier.fillMaxWidth().padding(vertical = 32.dp)) {
                        Label("还没有添加技能", 14f, V2Colors.ink(.76f), FontWeight.Medium)
                        Spacer(Modifier.height(6.dp))
                        Label(
                            "点右上角「+」扫描本机默认来源目录，选择要导入的 SKILL.md 能力包。",
                            11f,
                            V2Colors.ink(.40f),
                        )
                    }
                }
            }
            items(capabilities, key = { it.id.ifBlank { it.name } }) { item ->
                SkillCard(
                    item = item,
                    expanded = expanded == item.id,
                    onExpand = { expanded = if (expanded == item.id) null else item.id },
                    onToggle = { enabled -> onToggle(item, enabled) },
                    onRemove = { pendingRemove = item },
                )
            }
        }
    }

    val removeTarget = pendingRemove
    if (removeTarget != null) {
        AlertDialog(
            onDismissRequest = { pendingRemove = null },
            title = { Text("移除技能") },
            text = { Text(skillRemoveMessage(removeTarget.name)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingRemove = null
                    onRemove(removeTarget)
                }) { Text("移除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemove = null }) { Text("取消") }
            },
        )
    }

    if (scanReport != null || scanning || scanError != null) {
        SkillScanSheet(
            scanning = scanning,
            report = scanReport,
            error = scanError,
            onImport = onImport,
            onRetry = onScan,
            onDismiss = onCloseScan,
        )
    }
}

@Composable
private fun SkillCard(
    item: ImportedCapability,
    expanded: Boolean,
    onExpand: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .testTag("v2_skill_card_${item.name}")
            .clip(RoundedCornerShape(14.dp))
            .background(V2Colors.surface(.72f))
            .border(1.dp, V2Colors.line, RoundedCornerShape(14.dp))
            .clickable(onClick = onExpand)
            .padding(horizontal = 13.dp, vertical = 11.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.Psychology,
                null,
                tint = V2Colors.cyan700,
                modifier = Modifier.size(17.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Label(item.name, 12f, V2Colors.ink(.88f), maxLines = 1)
                Label(
                    skillToggleLabel(item.enabled) +
                        if (item.description.isNotBlank()) " · ${item.description}" else "",
                    9f,
                    V2Colors.ink(.38f),
                    maxLines = 1,
                )
            }
            Switch(
                checked = item.enabled,
                onCheckedChange = onToggle,
                modifier = Modifier.testTag("v2_skill_toggle_${item.name}"),
            )
        }
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            skillDetailLines(item).forEach { line ->
                Label(
                    line,
                    9.5f,
                    V2Colors.ink(.48f),
                    modifier = Modifier.padding(vertical = 1.dp),
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.DeleteOutline,
                    contentDescription = null,
                    tint = V2Colors.rose,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(5.dp))
                Label(
                    "移除技能",
                    9.5f,
                    V2Colors.rose,
                    modifier = Modifier
                        .testTag("v2_skill_remove_${item.name}")
                        .clip(RoundedCornerShape(7.dp))
                        .clickable(onClick = onRemove)
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun SkillScanSheet(
    scanning: Boolean,
    report: CapabilityScanReport?,
    error: String?,
    onImport: (CapabilityCandidate) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val candidates = skillScanCandidates(report)
    SheetFrame(onDismiss = onDismiss, maxHeightFraction = .82f) {
        SheetHeader(
            title = "添加技能",
            subtitle = "扫描电脑上的默认技能目录；导入只写 Flux 注册表",
            onClose = onDismiss,
        )
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                scanning -> Label(
                    "正在扫描本机来源…",
                    11f,
                    V2Colors.ink(.45f),
                    modifier = Modifier.padding(vertical = 16.dp),
                )
                error != null -> {
                    Label(
                        "扫描失败：$error",
                        10.5f,
                        V2Colors.rose,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                    Label(
                        "添加技能需要后端所在的电脑在线；已导入的清单仍可查看、启用或停用。",
                        9.5f,
                        V2Colors.ink(.40f),
                    )
                    TextButton(onClick = onRetry) { Text("重试") }
                }
                candidates.isEmpty() -> Label(
                    "没有扫描到可导入的技能。请确认电脑上的技能目录里有 SKILL.md。",
                    10.5f,
                    V2Colors.ink(.42f),
                    modifier = Modifier.padding(vertical = 16.dp),
                )
                else -> candidates.forEach { candidate ->
                    val blocker = skillImportBlocker(candidate)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(V2Colors.surface(.72f))
                            .border(1.dp, V2Colors.line, RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Label(candidate.name, 11.5f, V2Colors.ink(.86f), maxLines = 1)
                            Label(
                                blocker ?: candidate.source.ifBlank { "扫描候选" },
                                8.5f,
                                if (blocker != null) V2Colors.rose else V2Colors.ink(.36f),
                                maxLines = 2,
                            )
                        }
                        if (blocker == null) {
                            Label(
                                "导入",
                                10f,
                                V2Colors.cyan700,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(V2Colors.cyan500.copy(alpha = .10f))
                                    .clickable { onImport(candidate) }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                        } else {
                            Label("已拦截", 9f, V2Colors.rose)
                        }
                    }
                }
            }
        }
    }
}
