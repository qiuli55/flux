package top.qiuli55.flux.mobile.ui.v2

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DesignServices
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import top.qiuli55.flux.mobile.data.ImportedCapability
import top.qiuli55.flux.mobile.data.McpConnector

/** 输入区已经指定的能力：只随这一条消息发送，不写进任务长期配置。 */
internal data class SpecifiedCapabilityChip(
    val id: String,
    val name: String,
    val kind: String,
    val enabled: Boolean = true,
) {
    val kindLabel: String get() = if (kind == "connector") "连接器" else "技能"
}

/** 已连接且可用的连接器才能被指定；不写死具体厂商，按状态字段判断。 */
internal fun specifiableConnectors(items: List<McpConnector>): List<McpConnector> =
    items.filter { it.available && it.status == "connected" }

/** 点一下切换指定状态；再次点同一项恢复为不指定。 */
internal fun toggleSpecified(ids: List<String>, id: String): List<String> =
    if (ids.contains(id)) ids - id else ids + id

/** 技能芯片：名称优先取注册表；缓存里暂时没有时退回 id，不让芯片变成空白。 */
internal fun specifiedSkillChips(
    ids: List<String>,
    skills: List<ImportedCapability>,
): List<SpecifiedCapabilityChip> = ids.map { id ->
    val record = skills.firstOrNull { it.id == id } ?: skills.firstOrNull { it.name == id }
    SpecifiedCapabilityChip(
        id = id,
        name = record?.name?.takeIf { it.isNotBlank() } ?: id,
        kind = "skill",
        enabled = record?.enabled ?: true,
    )
}

internal fun specifiedConnectorChips(
    ids: List<String>,
    connectors: List<McpConnector>,
): List<SpecifiedCapabilityChip> = ids.map { id ->
    val record = connectors.firstOrNull { it.id == id }
    SpecifiedCapabilityChip(
        id = id,
        name = record?.name?.takeIf { it.isNotBlank() } ?: id,
        kind = "connector",
    )
}

/** 技能副行：停用的技能仍可被本次指定，但要说清楚它平时不会自动使用。 */
internal fun skillSubtitle(item: ImportedCapability): String = when {
    !item.enabled -> "已停用 · 本次指定后仍会使用"
    item.description.isNotBlank() -> item.description
    item.source.isNotBlank() -> item.source
    else -> "已启用"
}

/** 连接器副行：复用连接器页的状态文案，不另写一套。 */
internal fun connectorPickSubtitle(item: McpConnector): String =
    if (item.status == "connected") "已连接 · 本次指定后优先使用" else connectorSubtitle(item)

/** 「+」底部面板：保留原有附件入口，并增加技能 / 连接器。 */
@Composable
internal fun ComposerPickerSheet(
    designEnabled: Boolean,
    onCamera: () -> Unit,
    onImages: () -> Unit,
    onFiles: () -> Unit,
    onDesign: () -> Unit,
    onSkills: () -> Unit,
    onConnectors: () -> Unit,
    onDismiss: () -> Unit,
) {
    SheetFrame(onDismiss = onDismiss, maxHeightFraction = .62f, wrapContent = true) {
        SheetHeader(
            title = "添加",
            subtitle = "附件随消息发出；技能与连接器只指定这一次发送",
            onClose = onDismiss,
        )
        Column(
            Modifier
                .weight(1f, fill = false)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PickerRow(Icons.Outlined.PhotoCamera, "相机", "拍一张照片作为附件",
                V2ComposerTags.PICK_CAMERA, onCamera)
            PickerRow(Icons.Outlined.Image, "图片", "从相册选择（最多 5 张）",
                V2ComposerTags.PICK_IMAGE, onImages)
            PickerRow(Icons.Outlined.FolderOpen, "文件", "选择本机文件作为附件",
                V2ComposerTags.PICK_FILE, onFiles)
            if (designEnabled) {
                PickerRow(Icons.Outlined.DesignServices, "设计稿", "导出 SVG 随消息发出",
                    V2ComposerTags.PICK_DESIGN, onDesign)
            }
            PickerRow(Icons.Outlined.Psychology, "技能", "指定本次使用的技能",
                V2ComposerTags.PICK_SKILLS, onSkills)
            PickerRow(Icons.Outlined.Extension, "连接器", "指定本次使用的连接器",
                V2ComposerTags.PICK_CONNECTORS, onConnectors)
        }
    }
}

@Composable
private fun PickerRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    tag: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .testTag(tag)
            .clip(RoundedCornerShape(14.dp))
            .background(V2Colors.surface(.72f))
            .border(1.dp, V2Colors.line, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = V2Colors.cyan700, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Label(title, 12f, V2Colors.ink(.84f), maxLines = 1)
            Label(subtitle, 9f, V2Colors.ink(.38f), maxLines = 1)
        }
    }
}

/** 技能面板：管理 / 添加 / 已有技能列表（点一项 = 指定本次发送）。 */
@Composable
internal fun SkillPickerSheet(
    skills: List<ImportedCapability>,
    loading: Boolean,
    error: String?,
    specifiedIds: List<String>,
    onToggle: (ImportedCapability) -> Unit,
    onManage: () -> Unit,
    onAdd: () -> Unit,
    onDismiss: () -> Unit,
) {
    SheetFrame(onDismiss = onDismiss, maxHeightFraction = .78f) {
        SheetHeader(
            title = "技能",
            subtitle = "点一项指定本次发送使用；不选时已启用技能仍可自动使用",
            onClose = onDismiss,
        )
        CapabilitySheetActions(
            manageLabel = "管理技能",
            addLabel = "添加技能",
            onManage = onManage,
            onAdd = onAdd,
        )
        if (!error.isNullOrBlank()) {
            Label(
                error,
                9.5f,
                V2Colors.rose,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .testTag(V2ComposerTags.SKILL_SHEET)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            when {
                loading && skills.isEmpty() -> HintRow("正在读取技能…")
                skills.isEmpty() -> HintRow("还没有添加技能。可以点「添加技能」扫描本机来源。")
                else -> skills.forEach { item ->
                    CapabilityRow(
                        tag = V2ComposerTags.skillRow(item.name),
                        title = item.name,
                        subtitle = skillSubtitle(item),
                        selected = specifiedIds.contains(item.id) || specifiedIds.contains(item.name),
                        dimmed = !item.enabled,
                        onClick = { onToggle(item) },
                    )
                }
            }
        }
    }
}

/** 连接器面板：管理 / 添加 / 已连接且可用的列表（点一项 = 指定本次发送）。 */
@Composable
internal fun ConnectorPickerSheet(
    connectors: List<McpConnector>,
    loading: Boolean,
    error: String?,
    specifiedIds: List<String>,
    onToggle: (McpConnector) -> Unit,
    onManage: () -> Unit,
    onAdd: () -> Unit,
    onDismiss: () -> Unit,
) {
    val selectable = specifiableConnectors(connectors)
    SheetFrame(onDismiss = onDismiss, maxHeightFraction = .78f) {
        SheetHeader(
            title = "连接器",
            subtitle = "点一项指定本次发送使用；已连接的连接器平时仍可自动使用",
            onClose = onDismiss,
        )
        CapabilitySheetActions(
            manageLabel = "管理连接器",
            addLabel = "添加连接器",
            onManage = onManage,
            onAdd = onAdd,
        )
        if (!error.isNullOrBlank()) {
            Label(
                error,
                9.5f,
                V2Colors.rose,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .testTag(V2ComposerTags.CONNECTOR_SHEET)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            when {
                loading && connectors.isEmpty() -> HintRow("正在读取连接器…")
                selectable.isEmpty() -> HintRow("还没有已连接且可用的连接器，先去「管理连接器」添加。")
                else -> selectable.forEach { item ->
                    CapabilityRow(
                        tag = V2ComposerTags.connectorRow(item.id),
                        title = item.name,
                        subtitle = connectorPickSubtitle(item),
                        selected = specifiedIds.contains(item.id),
                        dimmed = false,
                        onClick = { onToggle(item) },
                    )
                }
            }
        }
    }
}

@Composable
private fun CapabilitySheetActions(
    manageLabel: String,
    addLabel: String,
    onManage: () -> Unit,
    onAdd: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SheetActionChip(manageLabel, onManage)
        SheetActionChip(addLabel, onAdd)
    }
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun SheetActionChip(label: String, onClick: () -> Unit) {
    Label(
        label,
        10.5f,
        V2Colors.cyan700,
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(V2Colors.cyan500.copy(alpha = .10f))
            .border(1.dp, V2Colors.cyan500.copy(alpha = .22f), RoundedCornerShape(9.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 7.dp),
    )
}

@Composable
private fun CapabilityRow(
    tag: String,
    title: String,
    subtitle: String,
    selected: Boolean,
    dimmed: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .testTag(tag)
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) V2Colors.cyan500.copy(alpha = .10f) else V2Colors.surface(.72f))
            .border(
                1.dp,
                if (selected) V2Colors.cyan500.copy(alpha = .34f) else V2Colors.line,
                RoundedCornerShape(14.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Label(
                title,
                11.5f,
                if (dimmed) V2Colors.ink(.52f) else V2Colors.ink(.86f),
                maxLines = 1,
            )
            Label(subtitle, 8.5f, V2Colors.ink(.36f), maxLines = 1)
        }
        if (selected) {
            Icon(
                Icons.Outlined.Check,
                contentDescription = "已指定",
                tint = V2Colors.cyan700,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** 输入区上方的一枚「本次指定」芯片；点 × 恢复为不指定（仍可自动使用）。 */
@Composable
internal fun SpecifiedCapabilityChipView(
    chip: SpecifiedCapabilityChip,
    onRemove: () -> Unit,
) {
    Row(
        Modifier
            .testTag(V2ComposerTags.specifiedChip(chip.kind, chip.id))
            .clip(RoundedCornerShape(10.dp))
            .background(V2Colors.cyan500.copy(alpha = .08f))
            .border(1.dp, V2Colors.cyan500.copy(alpha = .22f), RoundedCornerShape(10.dp))
            .padding(start = 7.dp, end = 4.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (chip.kind == "connector") Icons.Outlined.Extension else Icons.Outlined.Psychology,
            contentDescription = null,
            tint = V2Colors.cyan700.copy(alpha = .8f),
            modifier = Modifier.size(13.dp),
        )
        Spacer(Modifier.width(5.dp))
        Column(Modifier.widthIn(max = 160.dp)) {
            Label(chip.name, 9.5f, V2Colors.ink(.78f), maxLines = 1)
            Label(
                if (chip.enabled) "本次指定 · ${chip.kindLabel}" else "已停用 · 本次指定",
                8f,
                V2Colors.ink(.34f),
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(3.dp))
        Icon(
            Icons.Outlined.Close,
            contentDescription = "取消指定${chip.kindLabel}",
            tint = V2Colors.ink(.38f),
            modifier = Modifier
                .size(14.dp)
                .testTag(V2ComposerTags.specifiedRemove(chip.kind, chip.id))
                .clip(RoundedCornerShape(7.dp))
                .clickable(onClick = onRemove),
        )
    }
}

@Composable
private fun HintRow(text: String) {
    Label(
        text,
        10f,
        V2Colors.ink(.40f),
        modifier = Modifier.padding(vertical = 14.dp),
    )
}
