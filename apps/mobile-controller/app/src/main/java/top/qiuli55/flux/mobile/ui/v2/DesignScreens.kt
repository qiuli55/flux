package top.qiuli55.flux.mobile.ui.v2

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Redo
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Undo
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import top.qiuli55.flux.mobile.data.DesignDocument
import top.qiuli55.flux.mobile.data.DesignNode
import top.qiuli55.flux.mobile.data.DesignPreview
import top.qiuli55.flux.mobile.data.DesignRevision
import top.qiuli55.flux.mobile.data.DesignScreen
import top.qiuli55.flux.mobile.ui.v2.design.DesignHandle
import top.qiuli55.flux.mobile.ui.v2.design.DesignInteract
import top.qiuli55.flux.mobile.ui.v2.design.DesignPainter
import top.qiuli55.flux.mobile.ui.v2.design.DesignResolver
import top.qiuli55.flux.mobile.ui.v2.design.DesignSpec
import top.qiuli55.flux.mobile.ui.v2.design.NodeFrame
import top.qiuli55.flux.mobile.ui.v2.design.ResolvedNode
import top.qiuli55.flux.mobile.ui.v2.design.SnapResult
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// ------------------------------------------------------------------ 列表

@Composable
fun DesignStudioListScreen(
    state: DesignStudioState,
    extraTop: Dp,
    showBack: Boolean,
    onBack: () -> Unit,
    onReload: () -> Unit,
    onOpen: (String) -> Unit,
    onCreate: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onImport: (String, Long, () -> java.io.InputStream) -> Unit,
    onExport: (String, (ByteArray) -> Unit) -> Unit,
    onDismissMessages: () -> Unit,
) {
    var newOpen by remember { mutableStateOf(false) }
    var moreOpen by remember { mutableStateOf(false) }
    var actionTarget by remember { mutableStateOf<top.qiuli55.flux.mobile.data.DesignSummary?>(null) }
    var renameTarget by remember { mutableStateOf<top.qiuli55.flux.mobile.data.DesignSummary?>(null) }
    var deleteTarget by remember { mutableStateOf<top.qiuli55.flux.mobile.data.DesignSummary?>(null) }

    val context = LocalContext.current
    // 导出是异步的：字节回来先存这里，紧接着拉起「另存为」选择器写盘
    var exportPayload by remember { mutableStateOf<Pair<String, ByteArray>?>(null) }

    val importPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            val (name, size) = designFileMeta(context, uri)
            onImport(name, size) {
                context.contentResolver.openInputStream(uri)
                    ?: java.io.ByteArrayInputStream(ByteArray(0))
            }
        }
    }

    val savePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/svg+xml"),
    ) { uri ->
        val payload = exportPayload
        exportPayload = null
        if (uri != null && payload != null) {
            val saved = runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(payload.second) }
                    ?: error("无法写入所选位置")
            }.isSuccess
            Toast.makeText(
                context,
                if (saved) "SVG 已保存" else "保存失败，换个位置再试",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    // 根容器必须是 Box：弹层与内容平级叠放，放进父级 Column 会被内容挤成 0 高度
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            V2PageTitle(
                title = "设计",
                modifier = Modifier.padding(top = extraTop),
                subtitle = when {
                    state.loading && state.designs.isEmpty() -> "正在读取设计稿…"
                    state.designs.isEmpty() -> "还没有设计稿"
                    else -> "${state.designs.size} 份设计稿"
                },
                trailing = {
                    if (showBack) {
                        RoundIconButton(
                            Icons.Outlined.ArrowBack,
                            onBack,
                            size = 34.dp,
                            iconSize = 16.dp,
                            contentDescription = "返回",
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Box {
                        RoundIconButton(
                            Icons.Outlined.MoreHoriz,
                            onClick = { moreOpen = true },
                            size = 34.dp,
                            iconSize = 18.dp,
                            contentDescription = "更多",
                        )
                        DropdownMenu(
                            expanded = moreOpen,
                            onDismissRequest = { moreOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = { Label("导入设计稿", 12f, V2Colors.ink(.88f)) },
                                onClick = {
                                    moreOpen = false
                                    importPicker.launch(arrayOf("*/*"))
                                },
                            )
                            DropdownMenuItem(
                                text = { Label("刷新列表", 12f, V2Colors.ink(.88f)) },
                                onClick = {
                                    moreOpen = false
                                    onReload()
                                },
                            )
                        }
                    }
                },
            )

            DesignFlash(state.error ?: state.notice, state.error != null, onDismissMessages)

            when {
                state.loading && state.designs.isEmpty() -> DesignEmpty("正在读取设计稿…")
                state.designs.isEmpty() -> DesignEmpty("还没有设计稿\n点右下角 + 新建一份")
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 96.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    gridItems(state.designs, key = { it.id }) { design ->
                        DesignGridCard(
                            summary = design,
                            opening = state.openingId == design.id,
                            onClick = { onOpen(design.id) },
                            onLongPress = {
                                actionTarget = design
                                onDismissMessages()
                            },
                        )
                    }
                }
            }
        }

        V2Fab(
            onClick = { newOpen = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = 24.dp),
            contentDescription = "新建设计稿",
        )

        if (newOpen) {
            NewDesignSheet(
                creating = state.creating,
                onDismiss = { newOpen = false },
                onCreate = {
                    onCreate(it)
                    newOpen = false
                },
            )
        }

        actionTarget?.let { target ->
            DesignActionsSheet(
                summary = target,
                exporting = state.exportingId == target.id,
                onRename = {
                    actionTarget = null
                    renameTarget = target
                },
                onExport = {
                    onExport(target.id) { bytes ->
                        val fileName = defaultSvgName(target.name)
                        exportPayload = fileName to bytes
                        savePicker.launch(fileName)
                    }
                },
                onDelete = {
                    actionTarget = null
                    deleteTarget = target
                },
                onDismiss = { actionTarget = null },
            )
        }

        renameTarget?.let { target ->
            DesignRenameSheet(
                summary = target,
                onConfirm = {
                    onRename(target.id, it)
                    renameTarget = null
                },
                onDismiss = { renameTarget = null },
            )
        }

        deleteTarget?.let { target ->
            DesignDeleteSheet(
                summary = target,
                deleting = state.deletingId == target.id,
                error = state.error.takeIf { state.deletingId == null },
                onConfirm = { onDelete(target.id) },
                onDismiss = { deleteTarget = null },
            )
            // 删除成功后列表里没有这条了，面板自动收起
            LaunchedEffect(state.deletingId, state.designs) {
                if (state.deletingId == null && state.designs.none { it.id == target.id }) {
                    deleteTarget = null
                }
            }
        }
    }
}

/** 长按设计卡打开的操作面板：重命名 / 导出 SVG / 删除。 */
@Composable
private fun DesignActionsSheet(
    summary: top.qiuli55.flux.mobile.data.DesignSummary,
    exporting: Boolean,
    onRename: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    SheetFrame(onDismiss = onDismiss, maxHeightFraction = .52f) {
        SheetHeader(summary.name, "长按设计卡可再次打开这个面板", onClose = onDismiss)
        Column(Modifier.padding(horizontal = 20.dp)) {
            DesignMenuRow(
                icon = Icons.Outlined.Edit,
                title = "重命名",
                subtitle = "只改名称，画板内容不变",
                onClick = onRename,
            )
            Spacer(Modifier.height(8.dp))
            DesignMenuRow(
                icon = Icons.Outlined.FileDownload,
                title = if (exporting) "正在导出 SVG…" else "导出 SVG",
                subtitle = "全部画板合成一张，可直接拖进 Figma / Sketch",
                onClick = { if (!exporting) onExport() },
            )
            Spacer(Modifier.height(8.dp))
            DesignMenuRow(
                icon = Icons.Outlined.Delete,
                title = "删除设计稿",
                subtitle = "永久删除，无法恢复",
                danger = true,
                onClick = onDelete,
            )
            Spacer(Modifier.height(12.dp))
            GhostButton("取消", onDismiss, Modifier.fillMaxWidth())
            Spacer(Modifier.height(20.dp))
        }
    }
}

/** 从 SAF Uri 读文件名与大小；取不到就给兜底值，服务端仍按扩展名判类型。 */
private fun designFileMeta(context: Context, uri: Uri): Pair<String, Long> {
    var name = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "import"
    var size = -1L
    runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) {
                    cursor.getString(nameIndex)?.takeIf { it.isNotBlank() }?.let { name = it }
                }
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
            }
        }
    }
    return name to size
}

/** 「另存为」的默认文件名：去掉路径分隔符等非法字符，空名回落到 design.svg。 */
private fun defaultSvgName(raw: String): String {
    val clean = raw.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "-").trim('-').take(60)
    return (clean.ifBlank { "design" }) + ".svg"
}

@Composable
private fun DesignMenuRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val tone = if (danger) V2Colors.rose else V2Colors.cyan700
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(tone.copy(alpha = .07f))
            .border(1.dp, tone.copy(alpha = .16f), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tone, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Label(title, 12.5f, V2Colors.ink(.88f), FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Label(subtitle, 9.5f, V2Colors.ink(.36f), maxLines = 1)
        }
        Icon(
            Icons.Outlined.ChevronRight,
            null,
            tint = V2Colors.ink(.22f),
            modifier = Modifier.size(15.dp),
        )
    }
}

/** 重命名面板；保存时走整份保存的乐观锁。 */
@Composable
private fun DesignRenameSheet(
    summary: top.qiuli55.flux.mobile.data.DesignSummary,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(summary.id) { mutableStateOf(summary.name) }
    val clean = name.trim()
    SheetFrame(onDismiss = onDismiss, maxHeightFraction = .46f) {
        SheetHeader("重命名设计稿", "当前名称「${summary.name}」", onClose = onDismiss)
        Column(Modifier.padding(horizontal = 20.dp).imePadding()) {
            DesignInput(
                value = name,
                placeholder = "设计稿名称",
                onValueChange = { name = it },
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DesignAction("取消", filled = false, onClick = onDismiss)
                DesignAction(
                    "保存名称",
                    filled = true,
                    enabled = clean.isNotEmpty() && clean != summary.name,
                    onClick = { onConfirm(clean) },
                )
            }
            Spacer(Modifier.height(18.dp))
        }
    }
}

/** 删除确认：按住圆环 0.8 秒才真正删除，松手即取消。 */
@Composable
private fun DesignDeleteSheet(
    summary: top.qiuli55.flux.mobile.data.DesignSummary,
    deleting: Boolean,
    error: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    SheetFrame(onDismiss = onDismiss, maxHeightFraction = .58f) {
        SheetHeader(title = "删除设计稿", subtitle = summary.name, onClose = onDismiss)
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(V2Colors.rose.copy(alpha = .07f))
                    .padding(14.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Box(
                    Modifier
                        .padding(top = 4.dp)
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(V2Colors.rose),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Label("删除后不可恢复", 12f, V2Colors.ink(.78f), FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Label(
                        "这份设计稿的 ${summary.screenCount} 块画板、${summary.layerCount} 个图层会一起永久删除。",
                        11f,
                        V2Colors.ink(.48f),
                    )
                }
            }

            if (deleting) {
                Spacer(Modifier.height(28.dp))
                Label("正在删除…", 12f, V2Colors.ink(.55f), FontWeight.Medium)
            } else {
                Spacer(Modifier.height(12.dp))
                HoldToConfirm(
                    durationMs = 800,
                    onConfirmed = onConfirm,
                    icon = Icons.Outlined.Delete,
                    holdLabel = "按住删除",
                    hint = "按住 0.8 秒删除 · 松手即取消",
                )
            }

            if (error != null) {
                Spacer(Modifier.height(10.dp))
                Label(error, 11f, V2Colors.rose, textAlign = TextAlign.Center)
            }

            Spacer(Modifier.height(12.dp))
            GhostButton("取消", onDismiss, Modifier.fillMaxWidth())
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun DesignGridCard(
    summary: top.qiuli55.flux.mobile.data.DesignSummary,
    opening: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    WhiteCard(
        Modifier
            .fillMaxWidth()
            .pointerInput(summary.id) {
                detectTapGestures(
                    onTap = { onClick() },
                    onLongPress = { onLongPress() },
                )
            },
    ) {
        Column(Modifier.padding(8.dp)) {
            DesignPreviewThumb(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(.72f),
                summary = summary,
            )
            Spacer(Modifier.height(8.dp))
            Label(
                summary.name.ifBlank { "未命名设计稿" },
                13.5f,
                V2Colors.ink(.92f),
                FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 2.dp),
                maxLines = 1,
            )
            Spacer(Modifier.height(3.dp))
            Label(
                "${summary.screenCount} 个页面",
                11f,
                V2Colors.ink(.42f),
                modifier = Modifier.padding(horizontal = 2.dp),
                maxLines = 1,
            )
            Label(
                if (opening) "打开中…" else "已保存 · ${designSavedDate(summary.updatedAt)}",
                11f,
                V2Colors.ink(.32f),
                modifier = Modifier.padding(horizontal = 2.dp),
                maxLines = 1,
            )
        }
    }
}

/** 把 ISO 时间裁成「8月28日」；解析失败时退回原始日期片段。 */
private fun designSavedDate(updatedAt: String): String {
    val date = updatedAt.take(10)
    if (date.length < 10) return date.ifBlank { "—" }
    val month = date.substring(5, 7).toIntOrNull()
    val day = date.substring(8, 10).toIntOrNull()
    return if (month != null && day != null) "${month}月${day}日" else date
}

/** 列表里的缩略画布：用列表接口带回的第一屏节点离线渲染。 */
@Composable
private fun DesignPreviewThumb(
    modifier: Modifier,
    summary: top.qiuli55.flux.mobile.data.DesignSummary,
) {
    val preview = summary.preview
    val measurer = rememberTextMeasurer()
    val resolver = rememberDesignResolver(measurer)
    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(designColor(summary.bg, DesignSpec.SHELL_TOP))
            .border(1.dp, V2Colors.lineSoft, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (preview == null || preview.nodes.isEmpty()) {
            DesignGlyph(V2Colors.ink(.22f))
        } else {
            // 解析放在绘制 lambda 外：Canvas 每帧重绘都会执行 lambda，列表滚动时不能反复量字。
            val resolved = remember(preview.nodes) { resolver.resolve(preview.nodes) }
            Canvas(Modifier.fillMaxSize()) {
                val scale = min(size.width / preview.w, size.height / preview.h)
                val origin = Offset(
                    (size.width - preview.w * scale) / 2f,
                    (size.height - preview.h * scale) / 2f,
                )
                translate(origin.x, origin.y) {
                    scale(scale, scale, pivot = Offset.Zero) {
                        with(DesignPainter) {
                            drawScreen(
                                nodes = resolved,
                                width = preview.w,
                                height = preview.h,
                                bg = designColor(preview.bg ?: summary.bg, DesignSpec.SHELL_TOP),
                                device = false,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 对话卡片里的小画布预览：渲染设计稿第一屏的节点子集，与列表缩略图同一条绘制路径。 */
@Composable
internal fun DesignMiniPreview(
    modifier: Modifier,
    preview: DesignPreview?,
    bg: String?,
) {
    val measurer = rememberTextMeasurer()
    val resolver = rememberDesignResolver(measurer)
    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(designColor(bg ?: preview?.bg, DesignSpec.SHELL_TOP))
            .border(1.dp, V2Colors.lineSoft, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (preview == null || preview.nodes.isEmpty()) {
            DesignGlyph(V2Colors.ink(.22f))
        } else {
            // 同上：绘制期只做几何变换，节点解析只在预览数据变化时做一次。
            val resolved = remember(preview.nodes) { resolver.resolve(preview.nodes) }
            Canvas(Modifier.fillMaxSize()) {
                val scale = min(size.width / preview.w, size.height / preview.h)
                val origin = Offset(
                    (size.width - preview.w * scale) / 2f,
                    (size.height - preview.h * scale) / 2f,
                )
                translate(origin.x, origin.y) {
                    scale(scale, scale, pivot = Offset.Zero) {
                        with(DesignPainter) {
                            drawScreen(
                                nodes = resolved,
                                width = preview.w,
                                height = preview.h,
                                bg = designColor(preview.bg ?: bg, DesignSpec.SHELL_TOP),
                                device = false,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DesignGlyph(color: Color) {
    Canvas(Modifier.fillMaxSize().padding(18.dp)) {
        val gap = size.height / 6f
        drawRoundRect(
            color = color,
            topLeft = Offset(size.width * .18f, gap),
            size = Size(size.width * .64f, gap * .9f),
            cornerRadius = CornerRadius(gap * .3f),
        )
        drawRoundRect(
            color = color.copy(alpha = .55f),
            topLeft = Offset(size.width * .12f, gap * 2.5f),
            size = Size(size.width * .76f, gap * 2.2f),
            cornerRadius = CornerRadius(gap * .35f),
        )
        drawRoundRect(
            color = color.copy(alpha = .30f),
            topLeft = Offset(size.width * .12f, gap * 5.1f),
            size = Size(size.width * .50f, gap * .5f),
            cornerRadius = CornerRadius(gap * .25f),
        )
    }
}

@Composable
private fun NewDesignSheet(
    creating: Boolean,
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    SheetFrame(onDismiss = onDismiss, maxHeightFraction = .46f) {
        SheetHeader("新建设计稿", "会创建一块 390 × 844 的空白画板", onClose = onDismiss)
        Column(Modifier.padding(horizontal = 20.dp).imePadding()) {
            DesignInput(
                value = name,
                placeholder = "设计稿名称，例如「任务中心」",
                onValueChange = { name = it },
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DesignAction("取消", filled = false, onClick = onDismiss)
                DesignAction(
                    if (creating) "创建中…" else "创建并打开",
                    filled = true,
                    enabled = !creating,
                    onClick = { onCreate(name) },
                )
            }
            Spacer(Modifier.height(18.dp))
        }
    }
}

@Composable
private fun DesignEmpty(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(V2Colors.accent.copy(alpha = .08f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Tune, null, tint = V2Colors.cyan700, modifier = Modifier.size(26.dp))
            }
            Spacer(Modifier.height(14.dp))
            Label(text, 12f, V2Colors.ink(.42f), textAlign = TextAlign.Center)
        }
    }
}

// ------------------------------------------------------------------ 画布

@Composable
fun DesignCanvasScreen(
    state: DesignStudioState,
    extraTop: Dp,
    showBack: Boolean,
    onBack: () -> Unit,
    onSelectNode: (String?) -> Unit,
    onSelectScreen: (Int) -> Unit,
    onToggleMultiSelect: () -> Unit,
    onSetAiScope: (String) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onBeginDrag: (Set<String>) -> Unit,
    onDrag: (Set<String>, Float, Float) -> Unit,
    onSelectNodes: (Set<String>) -> Unit,
    onTransformNode: (String, Float, Float, Float, Float) -> Unit,
    onRotateNode: (String, Float) -> Unit,
    onMoveLayer: (String, Boolean) -> Unit,
    onDeleteLayer: (String) -> Unit,
    onDuplicateLayer: (String) -> Unit,
    onRenameLayer: (String, String) -> Unit,
    onLoadHistory: () -> Unit,
    onRevert: (Int) -> Unit,
    onSave: () -> Unit,
    onWebPreview: () -> Unit,
    onSetText: (String) -> Unit,
    onSetFill: (String) -> Unit,
    onSetTextColor: (String) -> Unit,
    onSetRadius: (Float) -> Unit,
    onSetOpacity: (Float) -> Unit,
    onAskAi: (String) -> Unit,
    onDismissMessages: () -> Unit,
) {
    val document = state.document
    val screen = state.screen
    val textMeasurer = rememberTextMeasurer()

    var zoom by remember(document?.id, state.screenIndex) { mutableFloatStateOf(1f) }
    var pan by remember(document?.id, state.screenIndex) { mutableStateOf(Offset.Zero) }
    var viewSize by remember { mutableStateOf(Size.Zero) }
    var editOpen by remember { mutableStateOf(false) }
    var aiOpen by remember { mutableStateOf(false) }
    var layersOpen by remember { mutableStateOf(false) }
    var historyOpen by remember { mutableStateOf(false) }
    // 演示预览：干净全屏、可点按钮切屏 / 拖滑杆 / 点开关，不含编辑网格与选中框
    var demoOpen by remember(document?.id) { mutableStateOf(false) }
    var demoIndex by remember(document?.id, state.screenIndex) { mutableIntStateOf(state.screenIndex) }

    // 根容器必须是 Box：弹层与内容平级叠放，放进父级 Column 会被内容挤成 0 高度
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            DesignHeader(
                title = document?.name ?: "正在打开…",
                subtitle = document?.let {
                    "v${it.version} · ${it.screens.size} 画板 · ${it.layerCount} 图层"
                },
                extraTop = extraTop,
                showBack = showBack,
                onBack = onBack,
                actions = {
                    if (document != null) {
                        RoundIconButton(
                            Icons.Outlined.History,
                            onClick = {
                                onLoadHistory()
                                historyOpen = true
                            },
                            size = 34.dp,
                            iconSize = 15.dp,
                            background = V2Colors.surface(.55f),
                            tint = V2Colors.ink(.55f),
                            contentDescription = "历史版本",
                        )
                        Spacer(Modifier.width(8.dp))
                        if (state.previewLoading) {
                            Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(15.dp),
                                    strokeWidth = 1.6.dp,
                                    color = V2Colors.cyan700,
                                )
                            }
                        } else {
                            // 网页预览：把设计稿渲染成真实网页，按钮/开关/滑杆都能点
                            RoundIconButton(
                                Icons.Outlined.Language,
                                onWebPreview,
                                size = 34.dp,
                                iconSize = 15.dp,
                                background = V2Colors.accent.copy(alpha = .10f),
                                tint = V2Colors.cyan800,
                                contentDescription = "网页预览",
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        RoundIconButton(
                            Icons.Outlined.PlayArrow,
                            onClick = { demoIndex = state.screenIndex; demoOpen = true },
                            size = 34.dp,
                            iconSize = 16.dp,
                            background = V2Colors.accent.copy(alpha = .10f),
                            tint = V2Colors.cyan800,
                            contentDescription = "演示预览",
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    if (state.saving) {
                        Label("保存中…", 10f, V2Colors.cyan700)
                    } else {
                        RoundIconButton(
                            Icons.Outlined.Save,
                            onSave,
                            size = 34.dp,
                            iconSize = 15.dp,
                            background = if (state.dirty) {
                                V2Colors.accent
                            } else {
                                V2Colors.surface(.55f)
                            },
                            tint = if (state.dirty) V2Colors.actionInk else V2Colors.ink(.35f),
                            contentDescription = "保存",
                        )
                    }
                },
            )

            if (document != null && screen != null) {
                if (document.screens.size > 1) {
                    LazyRow(
                        Modifier.fillMaxWidth().padding(bottom = 4.dp),
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        itemsIndexed(document.screens) { index, item ->
                            val active = index == state.screenIndex
                            Row(
                                Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(
                                        if (active) {
                                            V2Colors.accent.copy(alpha = .12f)
                                        } else {
                                            V2Colors.surface(.55f)
                                        },
                                    )
                                    .border(
                                        1.dp,
                                        if (active) {
                                            V2Colors.accent.copy(alpha = .35f)
                                        } else {
                                            V2Colors.lineSoft
                                        },
                                        RoundedCornerShape(999.dp),
                                    )
                                    .clickable { onSelectScreen(index) }
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                            ) {
                                Label(
                                    item.name,
                                    10.5f,
                                    if (active) V2Colors.cyan800 else V2Colors.ink(.45f),
                                    if (active) FontWeight.Medium else FontWeight.Normal,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }

                DesignFlash(state.error ?: state.notice, state.error != null, onDismissMessages)

                // 解析一次、绘制与命中测试共用；节点不变时不会重新量字（Resolver 内部还有 LRU）
                val resolver = rememberDesignResolver(textMeasurer)
                val resolved = remember(screen.id, screen.nodes) { resolver.resolve(screen.nodes) }
                val boardBg = designColor(document.bg, DesignSpec.SHELL_TOP)

                Box(Modifier.weight(1f).fillMaxWidth()) {
                    // 手势回调与数据都用 rememberUpdatedState 取最新值：画布自绘不重组节点，
                    // 拖动过程中 document 每帧都在变，闭包里必须读到新节点。
                    val latestScreen = rememberUpdatedState(screen)
                    val latestResolved = rememberUpdatedState(resolved)
                    val latestSelection = rememberUpdatedState(state.selectedNodeIds)
                    val latestMulti = rememberUpdatedState(state.multiSelect)
                    val latestSelect = rememberUpdatedState(onSelectNode)
                    val latestSelectNodes = rememberUpdatedState(onSelectNodes)
                    val latestBeginDrag = rememberUpdatedState(onBeginDrag)
                    val latestDrag = rememberUpdatedState(onDrag)
                    val latestTransform = rememberUpdatedState(onTransformNode)
                    val latestRotate = rememberUpdatedState(onRotateNode)
                    val haptics = LocalV2Haptics.current

                    // 吸附参考线 / 框选矩形：只在手势期间有值，绘制时叠加在画板之上
                    var guides by remember(document.id, state.screenIndex) {
                        mutableStateOf(SnapResult(0f, 0f, emptyList(), emptyList()))
                    }
                    var marquee by remember(document.id, state.screenIndex) {
                        mutableStateOf<Rect?>(null)
                    }

                    Canvas(
                        Modifier
                            .fillMaxSize()
                            .padding(bottom = 76.dp)
                            .onSizeChanged { viewSize = it.toSize() }
                            .pointerInput(document.id, state.screenIndex) {
                                // 双击复位：两次点按的间隔与位移都够小
                                var lastTapTime = 0L
                                var lastTapX = 0f
                                var lastTapY = 0f
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    val drawScreen = latestScreen.value
                                    val view = viewSize
                                    if (view.width <= 0f || view.height <= 0f) return@awaitEachGesture
                                    val slop = viewConfiguration.touchSlop

                                    fun currentScale(): Float = min(
                                        view.width / drawScreen.w,
                                        view.height / drawScreen.h,
                                    ) * .92f * zoom

                                    fun currentOrigin(scale: Float): Offset = Offset(
                                        (view.width - drawScreen.w * scale) / 2f + pan.x,
                                        (view.height - drawScreen.h * scale) / 2f + pan.y,
                                    )

                                    fun toDesign(position: Offset, scale: Float): Offset {
                                        val origin = currentOrigin(scale)
                                        return Offset(
                                            (position.x - origin.x) / scale,
                                            (position.y - origin.y) / scale,
                                        )
                                    }

                                    val scaleAtDown = currentScale()
                                    val downDesign = toDesign(down.position, scaleAtDown)
                                    // 热区按缩放反算，保证屏幕上永远是 44dp 的手指友好尺寸
                                    val hotRadius = 22.dp.toPx() / scaleAtDown
                                    val rotateOffset = 26.dp.toPx() / scaleAtDown
                                    val snapThreshold = 6.dp.toPx() / scaleAtDown

                                    // 手柄只在单选时出现与响应
                                    val selectedNode = latestSelection.value.singleOrNull()
                                        ?.let { findResolved(latestResolved.value, it) }
                                    val handleDown = selectedNode?.let {
                                        DesignInteract.hitHandle(it, downDesign, hotRadius, rotateOffset)
                                    }
                                    val startFrame = selectedNode?.let {
                                        NodeFrame(it.x, it.y, it.w, it.h)
                                    }
                                    val hit = DesignInteract.hitTest(latestResolved.value, downDesign)

                                    // 0 未定；1 拖图层；2 平移画布；3 双指缩放；4 手柄缩放；5 旋转；6 框选
                                    var mode = 0
                                    var dragIds: Set<String> = emptySet()
                                    var dragStartRect: Rect? = null
                                    val dragTargets = mutableListOf<Rect>()
                                    var appliedX = 0f
                                    var appliedY = 0f
                                    var rotateBase = selectedNode?.rotation ?: 0f
                                    var lastX = down.position.x
                                    var lastY = down.position.y
                                    var marqueeStart = downDesign

                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val pressed = event.changes.filter { it.pressed }
                                        if (pressed.isEmpty()) break

                                        if (pressed.size > 1) {
                                            // 双指：缩放 + 平移，按双指中点做锚点
                                            mode = 3
                                            val next = (zoom * event.calculateZoom()).coerceIn(.6f, 4f)
                                            val ratio = next / zoom
                                            val centroid = event.calculateCentroid(useCurrent = true)
                                            val center = Offset(view.width / 2f, view.height / 2f)
                                            pan = (pan + centroid - center) * ratio - (centroid - center) +
                                                event.calculatePan()
                                            zoom = next
                                            pressed.forEach { it.consume() }
                                            continue
                                        }

                                        val change = pressed.first()
                                        val dx = change.position.x - lastX
                                        val dy = change.position.y - lastY
                                        lastX = change.position.x
                                        lastY = change.position.y

                                        if (mode == 0) {
                                            val distance = hypot(
                                                change.position.x - down.position.x,
                                                change.position.y - down.position.y,
                                            )
                                            if (distance <= slop) continue
                                            // 命中优先级：手柄 > 已选中图层拖动 > 空白（多选时框选，否则平移）
                                            mode = when {
                                                handleDown == DesignHandle.Rotate -> 5
                                                handleDown != null && selectedNode != null -> 4
                                                hit != null && hit.id in latestSelection.value -> 1
                                                hit == null && latestMulti.value -> 6
                                                else -> 2
                                            }
                                            when (mode) {
                                                1 -> {
                                                    val selected = latestSelection.value
                                                    dragIds = if (latestMulti.value) {
                                                        selected
                                                    } else {
                                                        setOfNotNull(hit?.id)
                                                    }
                                                    latestBeginDrag.value(dragIds)
                                                    dragStartRect = unionRect(latestResolved.value, dragIds)
                                                    dragTargets.clear()
                                                    dragTargets += flattenResolved(latestResolved.value)
                                                        .filter {
                                                            it.id !in dragIds &&
                                                                it.alpha > .05f && it.type != "decor"
                                                        }
                                                        .map { Rect(it.x, it.y, it.right, it.bottom) }
                                                    haptics?.play(V2HapticLevel.TICK)
                                                }
                                                4, 5 -> {
                                                    latestBeginDrag.value(setOfNotNull(selectedNode?.id))
                                                    rotateBase = selectedNode?.rotation ?: 0f
                                                    haptics?.play(V2HapticLevel.TICK)
                                                }
                                                6 -> marqueeStart = toDesign(down.position, scaleAtDown)
                                            }
                                        }

                                        when (mode) {
                                            1 -> {
                                                val start = dragStartRect
                                                if (start != null && dragIds.isNotEmpty() && scaleAtDown > 0f) {
                                                    val pointer = toDesign(change.position, scaleAtDown)
                                                    val totalX = pointer.x - downDesign.x
                                                    val totalY = pointer.y - downDesign.y
                                                    val moved = Rect(
                                                        start.left + totalX,
                                                        start.top + totalY,
                                                        start.right + totalX,
                                                        start.bottom + totalY,
                                                    )
                                                    val snap = DesignInteract.snapMove(
                                                        moved, dragTargets, snapThreshold,
                                                    )
                                                    val targetX = totalX + snap.dx
                                                    val targetY = totalY + snap.dy
                                                    val stepX = targetX - appliedX
                                                    val stepY = targetY - appliedY
                                                    appliedX = targetX
                                                    appliedY = targetY
                                                    if (stepX != 0f || stepY != 0f) {
                                                        latestDrag.value(dragIds, stepX, stepY)
                                                    }
                                                    guides = snap
                                                }
                                            }
                                            2 -> pan += Offset(dx, dy)
                                            4 -> {
                                                val frame = startFrame
                                                val handle = handleDown
                                                if (selectedNode != null && frame != null &&
                                                    handle != null && scaleAtDown > 0f
                                                ) {
                                                    val pointer = toDesign(change.position, scaleAtDown)
                                                    val next = DesignInteract.resizeFrame(
                                                        frame, selectedNode.rotation, handle, pointer,
                                                    )
                                                    latestTransform.value(
                                                        selectedNode.id, next.x, next.y, next.w, next.h,
                                                    )
                                                }
                                            }
                                            5 -> {
                                                if (selectedNode != null && scaleAtDown > 0f) {
                                                    val pointer = toDesign(change.position, scaleAtDown)
                                                    val delta = DesignInteract.rotationDegrees(
                                                        selectedNode.center, downDesign, pointer,
                                                    )
                                                    latestRotate.value(
                                                        selectedNode.id,
                                                        DesignInteract.snapRotation(rotateBase + delta),
                                                    )
                                                }
                                            }
                                            6 -> {
                                                val pointer = toDesign(change.position, scaleAtDown)
                                                val rect = Rect(
                                                    min(marqueeStart.x, pointer.x),
                                                    min(marqueeStart.y, pointer.y),
                                                    max(marqueeStart.x, pointer.x),
                                                    max(marqueeStart.y, pointer.y),
                                                )
                                                marquee = rect
                                                latestSelectNodes.value(
                                                    DesignInteract.marqueeSelection(
                                                        latestResolved.value, rect,
                                                    ),
                                                )
                                            }
                                        }
                                        change.consume()
                                    }

                                    if (mode == 0) {
                                        // 没拖动 = 点按：短时间内两次点按视为双击，回到适配视图
                                        val now = down.uptimeMillis
                                        val doubleTap = now - lastTapTime < 320L && hypot(
                                            down.position.x - lastTapX,
                                            down.position.y - lastTapY,
                                        ) < 40f
                                        lastTapTime = now
                                        lastTapX = down.position.x
                                        lastTapY = down.position.y
                                        if (doubleTap) {
                                            zoom = 1f
                                            pan = Offset.Zero
                                        } else {
                                            latestSelect.value(hit?.id)
                                        }
                                    }
                                    guides = SnapResult(0f, 0f, emptyList(), emptyList())
                                    marquee = null
                                }
                            },
                    ) {
                        with(DesignPainter) {
                            drawDotGrid(V2Colors.ink(.045f), step = 22.dp.toPx())
                        }
                        val scale = min(size.width / screen.w, size.height / screen.h) * .92f * zoom
                        val origin = Offset(
                            (size.width - screen.w * scale) / 2f + pan.x,
                            (size.height - screen.h * scale) / 2f + pan.y,
                        )
                        translate(origin.x, origin.y) {
                            scale(scale, scale, pivot = Offset.Zero) {
                                with(DesignPainter) {
                                    drawScreen(
                                        nodes = resolved,
                                        width = screen.w,
                                        height = screen.h,
                                        bg = boardBg,
                                        device = true,
                                    )
                                }
                                with(DesignInteract) {
                                    val strokeWidth = 1.5.dp.toPx() / scale
                                    state.selectedNodes.forEach { selected ->
                                        val node = findResolved(resolved, selected.id)
                                            ?: return@forEach
                                        drawSelection(
                                            node = node,
                                            strokeWidth = strokeWidth,
                                            handleRadius = 4.5.dp.toPx() / scale,
                                            rotateOffset = 26.dp.toPx() / scale,
                                            handles = state.selectedNodes.size == 1,
                                        )
                                    }
                                    val snap = guides
                                    if (snap.vertical.isNotEmpty() || snap.horizontal.isNotEmpty()) {
                                        drawSnapGuides(
                                            snap.vertical, snap.horizontal, screen.w, screen.h,
                                        )
                                    }
                                    marquee?.let { drawMarquee(it, strokeWidth) }
                                }
                            }
                        }
                    }

                    val selectedNodes = state.selectedNodes
                    if (selectedNodes.isNotEmpty()) {
                        Row(
                            Modifier
                                .align(Alignment.BottomCenter)
                                .padding(horizontal = 16.dp, vertical = 14.dp)
                                .fillMaxWidth()
                                .shadowedDock()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Label(
                                    if (selectedNodes.size > 1) {
                                        "已选 ${selectedNodes.size} 个图层"
                                    } else {
                                        selectedNodes.first().name.ifBlank { selectedNodes.first().type }
                                    },
                                    12f,
                                    V2Colors.ink(.88f),
                                    FontWeight.Medium,
                                    maxLines = 1,
                                )
                                Spacer(Modifier.height(2.dp))
                                Label(
                                    when {
                                        state.multiSelect -> "多选模式 · 点画布可叠加或取消"
                                        selectedNodes.size > 1 -> "多选 · 拖动已选图层可整体移动"
                                        else -> "${selectedNodes.first().type} · 拖动已选图层移动，拖空白平移"
                                    },
                                    9.5f,
                                    V2Colors.ink(.36f),
                                    maxLines = 1,
                                )
                            }
                            DockButton(
                                if (state.multiSelect) "多选中" else "多选",
                                Icons.Outlined.Checklist,
                            ) { onToggleMultiSelect() }
                            Spacer(Modifier.width(8.dp))
                            DockButton("图层", Icons.Outlined.Layers) { layersOpen = true }
                            if (selectedNodes.size == 1) {
                                Spacer(Modifier.width(8.dp))
                                DockButton("编辑", Icons.Outlined.Tune) { editOpen = true }
                            }
                            Spacer(Modifier.width(8.dp))
                            DockButton("AI 改", Icons.Outlined.AutoAwesome, accent = true) {
                                onSetAiScope("selection")
                                aiOpen = true
                            }
                        }
                    } else {
                        // 什么都没选时也能直接对整屏下指令（加卡片、调整体层次等）
                        Row(
                            Modifier
                                .align(Alignment.BottomCenter)
                                .padding(horizontal = 16.dp, vertical = 14.dp)
                                .shadowedDock()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            DockButton("整屏 AI", Icons.Outlined.AutoAwesome, accent = true) {
                                onSetAiScope("screen")
                                aiOpen = true
                            }
                        }
                    }

                    Column(
                        Modifier.align(Alignment.TopEnd).padding(14.dp),
                        horizontalAlignment = Alignment.End,
                    ) {
                        // 撤销 / 重做放在画布右上角：手指正好在画布附近，改错了能立刻退回
                        if (state.canUndo || state.canRedo) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (state.canUndo) {
                                    RoundIconButton(
                                        Icons.Outlined.Undo,
                                        onClick = onUndo,
                                        size = 30.dp,
                                        iconSize = 13.dp,
                                        background = V2Colors.accent.copy(alpha = .10f),
                                        tint = V2Colors.cyan800,
                                        contentDescription = "撤销上一步",
                                    )
                                }
                                if (state.canRedo) {
                                    RoundIconButton(
                                        Icons.Outlined.Redo,
                                        onClick = onRedo,
                                        size = 30.dp,
                                        iconSize = 13.dp,
                                        background = V2Colors.accent.copy(alpha = .10f),
                                        tint = V2Colors.cyan800,
                                        contentDescription = "重做",
                                    )
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                        }
                        Label("${(zoom * 100).roundToInt()}%", 9.5f, V2Colors.ink(.30f), mono = true)
                        Spacer(Modifier.height(6.dp))
                        RoundIconButton(
                            Icons.Outlined.Refresh,
                            onClick = {
                                zoom = 1f
                                pan = Offset.Zero
                            },
                            size = 30.dp,
                            iconSize = 13.dp,
                        )
                    }
                }
            } else if (state.error == null) {
                DesignEmpty("正在读取画板…")
            } else {
                DesignEmpty("打不开这份设计稿\n${state.error}")
            }
        }

        val node = state.selectedNode
        if (editOpen && node != null) {
            DesignEditSheet(
                node = node,
                onDismiss = { editOpen = false },
                onSetText = onSetText,
                onSetFill = onSetFill,
                onSetTextColor = onSetTextColor,
                onSetRadius = onSetRadius,
                onSetOpacity = onSetOpacity,
            )
        }
        if (aiOpen && screen != null) {
            DesignAiSheet(
                scope = state.aiScope,
                selectedCount = state.selectedNodes.size,
                selectedName = state.selectedNode?.let { it.name.ifBlank { it.type } },
                screenName = screen.name,
                layerCount = screen.nodes.size,
                asking = state.asking,
                reply = state.aiReply,
                canUndo = state.canUndo,
                onScope = onSetAiScope,
                onUndo = onUndo,
                onDismiss = { aiOpen = false },
                onAsk = onAskAi,
            )
        }
        if (historyOpen && document != null) {
            DesignHistorySheet(
                revisions = state.revisions,
                currentVersion = document.version,
                loading = state.revisionsLoading,
                restoringVersion = state.restoringVersion,
                onRevert = onRevert,
                onDismiss = { historyOpen = false },
            )
        }
        if (layersOpen && screen != null) {
            LayerSheet(
                nodes = screen.nodes,
                selectedId = state.selectedNodeId,
                onSelect = { onSelectNode(it) },
                onMove = onMoveLayer,
                onDelete = onDeleteLayer,
                onDuplicate = onDuplicateLayer,
                onRename = onRenameLayer,
                onDismiss = { layersOpen = false },
            )
        }
        // 演示预览盖在编辑态最上层：退出时把编辑器切到刚才演示的那一屏
        if (demoOpen && document != null) {
            DesignDemoPreview(
                document = document,
                index = demoIndex.coerceIn(0, document.screens.lastIndex.coerceAtLeast(0)),
                extraTop = extraTop,
                onIndexChange = { demoIndex = it },
                onWebPreview = {
                    demoOpen = false
                    onWebPreview()
                },
                onExit = {
                    demoOpen = false
                    onSelectScreen(demoIndex)
                },
            )
        }
    }
}

/** 演示预览：全屏干净画布 + 真实交互（按钮切屏 / 开关 / 滑杆 / 底部导航）。 */
@Composable
private fun DesignDemoPreview(
    document: DesignDocument,
    index: Int,
    extraTop: Dp,
    onIndexChange: (Int) -> Unit,
    onWebPreview: () -> Unit,
    onExit: () -> Unit,
) {
    val screens = document.screens
    if (screens.isEmpty()) return
    val screen = screens[index.coerceIn(0, screens.lastIndex)]
    val textMeasurer = rememberTextMeasurer()
    val resolver = rememberDesignResolver(textMeasurer)
    val haptics = LocalV2Haptics.current

    // 演示交互只改画面，不写回设计稿：滑杆值 / 开关态 / 导航选中项各存一份
    var sliders by remember(document.id) { mutableStateOf<Map<String, Float>>(emptyMap()) }
    var switches by remember(document.id) { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var navs by remember(document.id) { mutableStateOf<Map<String, Int>>(emptyMap()) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0E12)),
    ) {
        Column(Modifier.fillMaxSize()) {
            // 顶栏：退出演示 + 当前画板
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = extraTop, start = 18.dp, end = 18.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RoundIconButton(
                    Icons.Outlined.Close,
                    onExit,
                    size = 34.dp,
                    iconSize = 16.dp,
                    background = Color.White.copy(alpha = .12f),
                    tint = Color.White,
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Label("演示预览", 12f, Color.White, FontWeight.Medium)
                    Label(document.name, 9f, Color.White.copy(alpha = .45f), maxLines = 1)
                }
                DemoWebChip(onClick = onWebPreview)
                Spacer(Modifier.width(10.dp))
                Label(
                    "${index + 1} / ${screens.size}",
                    9f,
                    Color.White.copy(alpha = .40f),
                    mono = true,
                )
            }

            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
            BoxWithConstraints(
                Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                // 画板等比缩放后居中；标尺以 dp 计，绘制时再换算成像素
                val scale = min(maxWidth.value / screen.w, maxHeight.value / screen.h) * .98f
                val canvasW = (screen.w * scale).dp
                val canvasH = (screen.h * scale).dp
                val radius = RoundedCornerShape(26.dp)

                // 演示态（滑杆 / 开关 / 导航）先叠回节点树，再解析成可直接绘制的节点
                val demoNodes = remember(screen.id, screen.nodes, sliders, switches, navs) {
                    screen.nodes.map { it.withDemoState(sliders, switches, navs) }
                }
                val demoResolved = remember(demoNodes) { resolver.resolve(demoNodes) }
                val demoBg = designColor(document.bg, DesignSpec.SHELL_TOP)

                Canvas(
                    Modifier
                        .size(canvasW, canvasH)
                        .shadow(26.dp, radius, clip = false)
                        .clip(radius)
                        .pointerInput(document.id, index) {
                            detectTapGestures { position ->
                                val scalePx = size.width.toFloat() / screen.w
                                val point = Offset(position.x / scalePx, position.y / scalePx)
                                val node = hitTestNode(
                                    screen.nodes,
                                    point,
                                    Offset.Zero,
                                    Float.MAX_VALUE,
                                ) ?: return@detectTapGestures
                                when (node.type) {
                                    "button", "pill" -> {
                                        haptics?.play(V2HapticLevel.TICK)
                                        demoNextScreen(node.text ?: node.name, screens, index)
                                            ?.let(onIndexChange)
                                    }
                                    "switch" -> {
                                        haptics?.play(V2HapticLevel.TICK)
                                        val current = switches[node.id] ?: (node.on == true)
                                        switches = switches + (node.id to !current)
                                    }
                                    "slider" -> {
                                        haptics?.play(V2HapticLevel.TICK)
                                        sliders = sliders + (
                                            node.id to sliderValue(node, point.x)
                                            )
                                    }
                                    "nav" -> {
                                        val items = node.items.ifEmpty {
                                            listOf("首页", "任务", "我的")
                                        }
                                        val cell = node.w / items.size.coerceAtLeast(1)
                                        val active = ((point.x - node.x) / cell)
                                            .roundToInt()
                                            .coerceIn(0, items.lastIndex)
                                        haptics?.play(V2HapticLevel.TICK)
                                        navs = navs + (node.id to active)
                                        demoNextScreen(
                                            items[active],
                                            screens,
                                            index,
                                        )?.let(onIndexChange)
                                    }
                                }
                            }
                        }
                        .pointerInput(document.id, index) {
                            var sliderId: String? = null
                            detectDragGestures(
                                onDragStart = { position ->
                                    val scalePx = size.width.toFloat() / screen.w
                                    val point = Offset(position.x / scalePx, position.y / scalePx)
                                    sliderId = hitTestNode(
                                        screen.nodes,
                                        point,
                                        Offset.Zero,
                                        Float.MAX_VALUE,
                                    )?.takeIf { it.type == "slider" }?.id
                                },
                                onDragEnd = { sliderId = null },
                                onDragCancel = { sliderId = null },
                            ) { change, _ ->
                                val id = sliderId ?: return@detectDragGestures
                                val node = demoFindNode(screen.nodes, id) ?: return@detectDragGestures
                                val scalePx = size.width.toFloat() / screen.w
                                val designX = change.position.x / scalePx
                                sliders = sliders + (id to sliderValue(node, designX))
                            }
                        },
                ) {
                    val scalePx = size.width / screen.w
                    scale(scalePx, scalePx, pivot = Offset.Zero) {
                        with(DesignPainter) {
                            drawScreen(
                                nodes = demoResolved,
                                width = screen.w,
                                height = screen.h,
                                bg = demoBg,
                                device = false,
                            )
                        }
                    }
                }

            }

            Spacer(Modifier.height(8.dp))
            Label(
                "点按钮切屏 · 拖动滑杆 · 点开关切换",
                8.5f,
                Color.White.copy(alpha = .28f),
            )
            Spacer(Modifier.height(6.dp))
            // 底栏：上一屏 / 圆点 / 下一屏
            Row(
                Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color.White.copy(alpha = .10f))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                DemoPagerButton(Icons.Outlined.ChevronLeft, enabled = index > 0) {
                    onIndexChange(index - 1)
                }
                screens.forEachIndexed { i, _ ->
                    Box(
                        Modifier
                            .size(if (i == index) 8.dp else 6.dp)
                            .clip(CircleShape)
                            .background(
                                if (i == index) Color.White else Color.White.copy(alpha = .28f),
                            )
                            .clickable { onIndexChange(i) },
                    )
                    Spacer(Modifier.width(2.dp))
                }
                DemoPagerButton(
                    Icons.Outlined.ChevronRight,
                    enabled = index < screens.lastIndex,
                ) {
                    onIndexChange(index + 1)
                }
            }
            Spacer(Modifier.height(10.dp))
            }
        }
    }
}

@Composable
private fun DemoWebChip(onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Color.White.copy(alpha = .12f))
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Language,
            null,
            tint = Color.White.copy(alpha = .82f),
            modifier = Modifier.size(12.dp),
        )
        Spacer(Modifier.width(4.dp))
        Label("网页版", 9f, Color.White.copy(alpha = .82f), mono = true)
    }
}

@Composable
private fun DemoPagerButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(26.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = if (enabled) .14f else .05f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            null,
            tint = Color.White.copy(alpha = if (enabled) .82f else .22f),
            modifier = Modifier.size(15.dp),
        )
    }
}

private fun sliderValue(node: DesignNode, designX: Float): Float =
    ((designX - node.x) / node.w.coerceAtLeast(1f)).coerceIn(0f, 1f)

private fun demoFindNode(nodes: List<DesignNode>, id: String): DesignNode? {
    nodes.forEach { node ->
        if (node.id == id) return node
        demoFindNode(node.children, id)?.let { return it }
    }
    return null
}

/** 把演示态叠回节点树：滑杆值、开关态、导航选中项；其余字段原样不动。 */
private fun DesignNode.withDemoState(
    sliders: Map<String, Float>,
    switches: Map<String, Boolean>,
    navs: Map<String, Int>,
): DesignNode {
    var node = this
    when (type) {
        "slider" -> sliders[id]?.let { node = node.copy(value = it) }
        "switch" -> switches[id]?.let { node = node.copy(on = it) }
        "nav" -> navs[id]?.let { node = node.copy(active = it) }
    }
    if (children.isNotEmpty()) {
        node = node.copy(children = children.map { it.withDemoState(sliders, switches, navs) })
    }
    return node
}

/**
 * 演示里的按钮/底部导航指向哪一屏：
 * 1. 文案命中其他画板名（如「登录」→「01 · 登录」）就跳那一屏；
 * 2. 「返回 / 取消 / 关闭 / 退出」回上一屏；
 * 3. 其余按主行动作处理，顺延到下一屏；到末屏则不动。
 */
private fun demoNextScreen(text: String?, screens: List<DesignScreen>, current: Int): Int? {
    val raw = text?.trim().orEmpty()
    val back = listOf("返回", "取消", "关闭", "退出").any { raw.contains(it) }
    if (back) return (current - 1).takeIf { it >= 0 }
    val norm = demoNorm(raw)
    if (norm.isNotEmpty()) {
        screens.forEachIndexed { i, item ->
            if (i != current) {
                val whole = demoNorm(item.name)
                val tail = demoNorm(item.name.substringAfter("·", item.name))
                val id = demoNorm(item.id)
                if ((tail.isNotEmpty() && (tail.contains(norm) || norm.contains(tail))) ||
                    id.contains(norm) ||
                    whole.contains(norm)
                ) {
                    return i
                }
            }
        }
    }
    return (current + 1).takeIf { it < screens.size }
}

private fun demoNorm(text: String): String =
    text.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")

/** 未保存离开确认：保存并退出 / 放弃修改 / 继续编辑。 */
@Composable
fun DesignExitSheet(
    saving: Boolean,
    onSaveAndExit: () -> Unit,
    onDiscard: () -> Unit,
    onDismiss: () -> Unit,
) {
    SheetFrame(onDismiss = onDismiss, maxHeightFraction = .5f) {
        SheetHeader("还有修改未保存", "离开画布前先决定怎么处理这些改动", onClose = onDismiss)
        Column(Modifier.padding(horizontal = 20.dp).imePadding()) {
            DesignAction(
                if (saving) "正在保存…" else "保存并退出",
                filled = true,
                enabled = !saving,
                modifier = Modifier.fillMaxWidth(),
                onClick = onSaveAndExit,
            )
            Spacer(Modifier.height(8.dp))
            DesignAction(
                "放弃修改并退出",
                filled = false,
                enabled = !saving,
                modifier = Modifier.fillMaxWidth(),
                onClick = onDiscard,
            )
            Spacer(Modifier.height(10.dp))
            GhostButton("继续编辑", onDismiss, Modifier.fillMaxWidth())
            Spacer(Modifier.height(18.dp))
        }
    }
}

@Composable
private fun DockButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accent: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (accent) V2Colors.accent else V2Colors.surface(.72f))
            .border(
                1.dp,
                if (accent) Color.Transparent else V2Colors.lineSoft,
                RoundedCornerShape(999.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (accent) V2Colors.actionInk else V2Colors.ink(.55f), modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(4.dp))
        Label(text, 10f, if (accent) V2Colors.actionInk else V2Colors.ink(.62f), FontWeight.Medium, maxLines = 1)
    }
}

private fun Modifier.shadowedDock(): Modifier = this
    .clip(RoundedCornerShape(18.dp))
    .background(Brush.verticalGradient(listOf(V2Colors.surface(.92f), V2Colors.surface(.80f))))
    .border(1.dp, V2Colors.line, RoundedCornerShape(18.dp))

// ------------------------------------------------------------------ 轻编辑

@Composable
private fun DesignEditSheet(
    node: DesignNode,
    onDismiss: () -> Unit,
    onSetText: (String) -> Unit,
    onSetFill: (String) -> Unit,
    onSetTextColor: (String) -> Unit,
    onSetRadius: (Float) -> Unit,
    onSetOpacity: (Float) -> Unit,
) {
    var text by remember(node.id) { mutableStateOf(node.text.orEmpty()) }
    var radius by remember(node.id) {
        mutableFloatStateOf(node.radius?.tl ?: DesignSpec.radiusOf(node))
    }
    var opacity by remember(node.id) { mutableFloatStateOf(node.opacity ?: 1f) }

    SheetFrame(onDismiss = onDismiss, maxHeightFraction = .78f) {
        SheetHeader(
            title = "轻编辑",
            subtitle = "${node.name.ifBlank { node.type }} · ${node.type}",
            onClose = onDismiss,
        )
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .imePadding(),
        ) {
            if (!node.text.isNullOrEmpty() || node.type == "text") {
                DesignFieldTitle("文案")
                DesignInput(
                    value = text,
                    placeholder = "组件文案",
                    onValueChange = {
                        text = it
                        onSetText(it)
                    },
                )
                Spacer(Modifier.height(14.dp))
            }

            if (node.type in fillTypes) {
                DesignFieldTitle("填充")
                ColorRow(
                    selected = node.fill,
                    options = fillSwatches,
                    onPick = onSetFill,
                )
                Spacer(Modifier.height(14.dp))
            }

            if (!node.text.isNullOrEmpty() || node.color != null) {
                DesignFieldTitle("文字颜色")
                ColorRow(
                    selected = node.color,
                    options = textSwatches,
                    onPick = onSetTextColor,
                )
                Spacer(Modifier.height(14.dp))
            }

            if (node.type in fillTypes) {
                DesignFieldTitle("圆角  ${radius.roundToInt()}px")
                Slider(
                    value = radius,
                    onValueChange = {
                        radius = it
                        onSetRadius(it)
                    },
                    valueRange = 0f..80f,
                    colors = SliderDefaults.colors(
                        thumbColor = V2Colors.accent,
                        activeTrackColor = V2Colors.accent.copy(alpha = .55f),
                        inactiveTrackColor = V2Colors.line,
                    ),
                )
                Spacer(Modifier.height(6.dp))
            }

            DesignFieldTitle("不透明度  ${(opacity * 100).roundToInt()}%")
            Slider(
                value = opacity,
                onValueChange = {
                    opacity = it
                    onSetOpacity(it)
                },
                valueRange = .1f..1f,
                colors = SliderDefaults.colors(
                    thumbColor = V2Colors.accent,
                    activeTrackColor = V2Colors.accent.copy(alpha = .55f),
                    inactiveTrackColor = V2Colors.line,
                ),
            )
            Spacer(Modifier.height(10.dp))
            DesignAction("完成", filled = true, onClick = onDismiss, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(18.dp))
        }
    }
}

private val fillTypes = setOf("button", "card", "pill", "input", "rect", "circle", "decor", "nav", "switch", "slider")

private val fillSwatches = listOf(
    "#FFFFFF", "#F8FAFC", "#0891B2", "#22D3EE", "#0F172A",
    "#10B981", "#F59E0B", "#E11D48", "#EEF2FF",
)

private val textSwatches = listOf(
    "#0F172A", "#FFFFFF", "#64748B", "#0891B2", "#10B981", "#D97706", "#E11D48",
)

@Composable
private fun ColorRow(
    selected: String?,
    options: List<String>,
    onPick: (String) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(options) { hex ->
            val color = designColor(hex, Color.Transparent)
            val active = selected?.equals(hex, ignoreCase = true) == true ||
                (selected != null && designColor(selected, Color.Transparent) == color)
            Box(
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(color)
                    .border(
                        if (active) 2.dp else 1.dp,
                        if (active) V2Colors.accent else V2Colors.ink(.12f),
                        CircleShape,
                    )
                    .clickable { onPick(hex) },
                contentAlignment = Alignment.Center,
            ) {
                if (active) {
                    Icon(
                        Icons.Outlined.Check,
                        null,
                        tint = if (color.luminance() > .55f) V2Colors.ink else Color.White,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
        }
    }
}

private fun Color.luminance(): Float = .299f * red + .587f * green + .114f * blue

@Composable
private fun DesignFieldTitle(text: String) {
    Label(text, 10f, V2Colors.ink(.42f), FontWeight.Medium)
    Spacer(Modifier.height(7.dp))
}

// ------------------------------------------------------------------ AI

@Composable
private fun DesignAiSheet(
    scope: String,
    selectedCount: Int,
    selectedName: String?,
    screenName: String,
    layerCount: Int,
    asking: Boolean,
    reply: String?,
    canUndo: Boolean,
    onScope: (String) -> Unit,
    onUndo: () -> Unit,
    onDismiss: () -> Unit,
    onAsk: (String) -> Unit,
) {
    var prompt by remember { mutableStateOf("") }
    val screenScope = scope == "screen"
    val suggestions = if (screenScope) {
        listOf("整体更有层次", "加一张价格卡", "主按钮更醒目", "间距再松一点")
    } else {
        listOf("圆角再大一点", "主色改亮一点", "文字再大一号", "整体更通透")
    }
    val subtitle = when {
        screenScope -> "作用于「$screenName」整屏（$layerCount 个顶层图层），可新增、删除、调层序"
        selectedCount > 1 -> "作用于已选的 $selectedCount 个图层"
        selectedCount == 1 -> "只作用于「${selectedName ?: "当前组件"}」，不会动其他图层"
        else -> "先选组件，或切到整屏"
    }

    SheetFrame(onDismiss = onDismiss, maxHeightFraction = .78f) {
        SheetHeader(
            title = "让 AI 改画布",
            subtitle = subtitle,
            onClose = onDismiss,
        )
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .imePadding(),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DesignScopeChip(
                    text = if (selectedCount > 0) "选中 $selectedCount" else "选中组件",
                    active = !screenScope,
                    enabled = selectedCount > 0,
                    onClick = { onScope("selection") },
                )
                DesignScopeChip(
                    text = "整屏",
                    active = screenScope,
                    enabled = true,
                    onClick = { onScope("screen") },
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                suggestions.take(2).forEach { item ->
                    DesignSuggestion(item) { prompt = item }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                suggestions.drop(2).forEach { item ->
                    DesignSuggestion(item) { prompt = item }
                }
            }
            Spacer(Modifier.height(14.dp))
            DesignInput(
                value = prompt,
                placeholder = if (screenScope) {
                    "例如：在内容卡下面加一张价格卡"
                } else {
                    "描述要怎么改，例如「按钮再高一点」"
                },
                onValueChange = { prompt = it },
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                DesignAction(
                    if (asking) "正在思考…" else "让 AI 修改",
                    filled = true,
                    enabled = !asking && prompt.isNotBlank() && (screenScope || selectedCount > 0),
                    onClick = { onAsk(prompt) },
                )
                Spacer(Modifier.width(12.dp))
                if (asking) {
                    Label(
                        if (screenScope) "正在按整屏理解…" else "只返回选中图层的修改",
                        9.5f,
                        V2Colors.ink(.35f),
                        maxLines = 1,
                    )
                }
            }
            if (reply != null) {
                Spacer(Modifier.height(14.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(V2Colors.accent.copy(alpha = .08f))
                        .border(1.dp, V2Colors.accent.copy(alpha = .18f), RoundedCornerShape(14.dp))
                        .padding(12.dp),
                ) {
                    Icon(Icons.Outlined.AutoAwesome, null, tint = V2Colors.cyan700, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(8.dp))
                    Label(reply, 11f, V2Colors.ink(.72f), modifier = Modifier.weight(1f))
                }
            }
            if (canUndo) {
                Spacer(Modifier.height(12.dp))
                DesignAction(
                    "撤销上一步修改",
                    filled = false,
                    enabled = !asking,
                    onClick = onUndo,
                )
            }
            Spacer(Modifier.height(18.dp))
        }
    }
}

@Composable
private fun DesignScopeChip(
    text: String,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (active) V2Colors.accent.copy(alpha = .14f) else V2Colors.surface(.72f))
            .border(
                1.dp,
                if (active) V2Colors.accent.copy(alpha = .38f) else V2Colors.lineSoft,
                RoundedCornerShape(999.dp),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Label(
            text,
            10.5f,
            when {
                !enabled -> V2Colors.ink(.26f)
                active -> V2Colors.cyan800
                else -> V2Colors.ink(.58f)
            },
            if (active) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

// ------------------------------------------------------------------ 历史版本

@Composable
private fun DesignHistorySheet(
    revisions: List<DesignRevision>,
    currentVersion: Int,
    loading: Boolean,
    restoringVersion: Int?,
    onRevert: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    SheetFrame(onDismiss = onDismiss, maxHeightFraction = .72f) {
        SheetHeader(
            title = "历史版本",
            subtitle = "当前 v$currentVersion · 每次保存前自动留档，保留最近 20 版",
            onClose = onDismiss,
        )
        when {
            loading && revisions.isEmpty() -> Box(
                Modifier.fillMaxWidth().height(120.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 1.8.dp,
                    color = V2Colors.cyan700,
                )
            }
            revisions.isEmpty() -> Box(
                Modifier.fillMaxWidth().padding(vertical = 34.dp),
                contentAlignment = Alignment.Center,
            ) {
                Label("还没有历史版本，保存一次后就会留档", 10.5f, V2Colors.ink(.42f))
            }
            else -> LazyColumn(Modifier.fillMaxWidth()) {
                items(revisions, key = { it.version }) { item ->
                    val restoring = restoringVersion == item.version
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Label(
                                "v${item.version} · ${item.layerCount} 图层",
                                12f,
                                V2Colors.ink(.82f),
                                FontWeight.Medium,
                                maxLines = 1,
                            )
                            Spacer(Modifier.height(2.dp))
                            Label(
                                "${item.screenCount} 画板 · ${formatRevisionTime(item.updatedAt)}",
                                9.5f,
                                V2Colors.ink(.36f),
                                maxLines = 1,
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        DesignAction(
                            if (restoring) "恢复中…" else "恢复",
                            filled = false,
                            enabled = restoringVersion == null && !loading,
                            onClick = { onRevert(item.version) },
                        )
                    }
                }
            }
        }
        if (loading && revisions.isNotEmpty()) {
            Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    modifier = Modifier.size(15.dp),
                    strokeWidth = 1.6.dp,
                    color = V2Colors.cyan700,
                )
            }
        }
        Spacer(Modifier.height(18.dp))
    }
}

/** 把 ISO 时间压成「10-27 18:20」；格式不符就原样截断，不抛异常。 */
private fun formatRevisionTime(raw: String): String {
    if (raw.length < 16) return raw.ifBlank { "时间未知" }
    return "${raw.substring(5, 10)} ${raw.substring(11, 16)}"
}

@Composable
private fun DesignSuggestion(text: String, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(V2Colors.surface(.72f))
            .border(1.dp, V2Colors.lineSoft, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Label(text, 10f, V2Colors.ink(.60f), maxLines = 1)
    }
}

// ------------------------------------------------------------------ 图层

@Composable
private fun LayerSheet(
    nodes: List<DesignNode>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onMove: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit,
    onDuplicate: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    val rows = remember(nodes) { flattenLayers(nodes) }
    val selectedRow = rows.firstOrNull { it.node.id == selectedId }
    var renamingId by remember { mutableStateOf<String?>(null) }
    var renameText by remember { mutableStateOf("") }
    val targetId = renamingId

    SheetFrame(onDismiss = onDismiss, maxHeightFraction = .72f) {
        SheetHeader("图层", "${rows.size} 个图层 · 越靠上越顶层", onClose = onDismiss)

        when {
            targetId != null -> Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                DesignFieldTitle("重命名图层")
                DesignInput(
                    value = renameText,
                    placeholder = "图层名称",
                    onValueChange = { renameText = it },
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DesignAction("取消", filled = false) { renamingId = null }
                    DesignAction(
                        "确定",
                        filled = true,
                        enabled = renameText.isNotBlank(),
                    ) {
                        onRename(targetId, renameText)
                        renamingId = null
                    }
                }
                Spacer(Modifier.height(6.dp))
            }

            selectedRow != null -> {
                val node = selectedRow.node
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Label(
                        node.name.ifBlank { node.type },
                        11f,
                        V2Colors.ink(.55f),
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    RoundIconButton(
                        Icons.Outlined.KeyboardArrowUp,
                        { onMove(node.id, true) },
                        size = 30.dp,
                        iconSize = 15.dp,
                        contentDescription = "上移一层",
                    )
                    Spacer(Modifier.width(6.dp))
                    RoundIconButton(
                        Icons.Outlined.KeyboardArrowDown,
                        { onMove(node.id, false) },
                        size = 30.dp,
                        iconSize = 15.dp,
                        contentDescription = "下移一层",
                    )
                    Spacer(Modifier.width(6.dp))
                    RoundIconButton(
                        Icons.Outlined.ContentCopy,
                        { onDuplicate(node.id) },
                        size = 30.dp,
                        iconSize = 14.dp,
                        contentDescription = "复制图层",
                    )
                    Spacer(Modifier.width(6.dp))
                    RoundIconButton(
                        Icons.Outlined.Edit,
                        {
                            renameText = node.name
                            renamingId = node.id
                        },
                        size = 30.dp,
                        iconSize = 14.dp,
                        contentDescription = "重命名",
                    )
                    Spacer(Modifier.width(6.dp))
                    RoundIconButton(
                        Icons.Outlined.Delete,
                        { onDelete(node.id) },
                        size = 30.dp,
                        iconSize = 14.dp,
                        background = V2Colors.rose.copy(alpha = .10f),
                        tint = V2Colors.rose700,
                        contentDescription = "删除图层",
                    )
                }
                Spacer(Modifier.height(4.dp))
            }
        }

        LazyColumn(Modifier.fillMaxWidth()) {
            items(rows, key = { it.node.id }) { row ->
                val active = row.node.id == selectedId
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(row.node.id) }
                        .background(if (active) V2Colors.accent.copy(alpha = .07f) else Color.Transparent)
                        .padding(
                            start = 20.dp + (row.depth * 14).dp,
                            end = 20.dp,
                            top = 10.dp,
                            bottom = 10.dp,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(if (active) V2Colors.accent else V2Colors.ink(.18f)),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Label(
                            row.node.name.ifBlank { row.node.type },
                            12f,
                            if (active) V2Colors.cyan800 else V2Colors.ink(.78f),
                            if (active) FontWeight.Medium else FontWeight.Normal,
                            maxLines = 1,
                        )
                        Label(
                            "${row.node.type} · ${row.node.w.roundToInt()}×${row.node.h.roundToInt()}",
                            9f,
                            V2Colors.ink(.30f),
                            mono = true,
                            maxLines = 1,
                        )
                    }
                    if (active) {
                        Icon(Icons.Outlined.Check, null, tint = V2Colors.accent, modifier = Modifier.size(14.dp))
                    }
                }
            }
            item { Spacer(Modifier.height(18.dp)) }
        }
    }
}

private data class LayerRow(val node: DesignNode, val depth: Int)

/** 顶层优先展示：nodes 列表后写的在上层，所以反向展开。 */
private fun flattenLayers(nodes: List<DesignNode>, depth: Int = 0): List<LayerRow> =
    nodes.asReversed().flatMap { node ->
        listOf(LayerRow(node, depth)) + flattenLayers(node.children, depth + 1)
    }

// ------------------------------------------------------------------ 通用

@Composable
private fun DesignHeader(
    title: String,
    subtitle: String?,
    extraTop: Dp,
    showBack: Boolean,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp + extraTop, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showBack) {
            RoundIconButton(
                Icons.Outlined.ArrowBack,
                onBack,
                size = 32.dp,
                iconSize = 16.dp,
                contentDescription = "返回",
            )
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Label(title, 20f, V2Colors.ink(.95f), FontWeight.SemiBold, maxLines = 1)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Label(subtitle, 9.5f, V2Colors.ink(.36f), maxLines = 1, mono = true)
            }
        }
        actions()
    }
}

@Composable
private fun DesignFlash(text: String?, error: Boolean, onDismiss: () -> Unit) {
    if (text == null) return
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (error) V2Colors.rose.copy(alpha = .09f) else V2Colors.accent.copy(alpha = .08f))
            .border(
                1.dp,
                if (error) V2Colors.rose.copy(alpha = .22f) else V2Colors.accent.copy(alpha = .18f),
                RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onDismiss)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Label(text, 10.5f, if (error) V2Colors.rose700 else V2Colors.cyan800, modifier = Modifier.weight(1f))
        Icon(Icons.Outlined.Close, null, tint = V2Colors.ink(.30f), modifier = Modifier.size(12.dp))
    }
}

@Composable
private fun DesignInput(
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 46.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(V2Colors.surface(.82f))
            .border(1.dp, V2Colors.lineSoft, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isBlank()) Label(placeholder, 11f, V2Colors.ink(.28f), maxLines = 1)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            textStyle = TextStyle(fontSize = 12.sp, color = V2Colors.ink(.88f)),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(V2Colors.accent),
            keyboardOptions = KeyboardOptions.Default,
            maxLines = 4,
        )
    }
}

@Composable
private fun DesignAction(
    text: String,
    filled: Boolean,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(
                when {
                    !enabled -> V2Colors.ink(.08f)
                    filled -> V2Colors.accent
                    else -> V2Colors.surface(.72f)
                },
            )
            .border(
                1.dp,
                if (filled && enabled) Color.Transparent else V2Colors.lineSoft,
                RoundedCornerShape(14.dp),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Label(
            text,
            11f,
            when {
                !enabled -> V2Colors.ink(.32f)
                filled -> V2Colors.actionInk
                else -> V2Colors.ink(.62f)
            },
            FontWeight.Medium,
            maxLines = 1,
        )
    }
}

// ------------------------------------------------------------------ 渲染

/**
 * 跨帧复用的解析器：文本排版带 LRU 缓存，拖动 / 缩放时不会反复重新量字。
 * 具体规则全部在 [DesignSpec] / [DesignResolver] / [DesignPainter] 里，与网页预览一一对应。
 */
@Composable
private fun rememberDesignResolver(measurer: TextMeasurer): DesignResolver {
    val density = LocalDensity.current
    val densityValue = density.density
    val fontScale = density.fontScale
    return remember(measurer, densityValue, fontScale) {
        DesignResolver(measurer, densityValue, fontScale)
    }
}

/** 在解析后的树里按 id 找节点（含子节点）。 */
private fun findResolved(nodes: List<ResolvedNode>, id: String): ResolvedNode? {
    nodes.forEach { node ->
        if (node.id == id) return node
        findResolved(node.children, id)?.let { return it }
    }
    return null
}

/** 展平所有层级（框选、吸附参考线都用它）。 */
private fun flattenResolved(nodes: List<ResolvedNode>): List<ResolvedNode> {
    val out = ArrayList<ResolvedNode>(nodes.size * 2)
    fun walk(list: List<ResolvedNode>) {
        list.forEach { node ->
            out.add(node)
            walk(node.children)
        }
    }
    walk(nodes)
    return out
}

/** 一组节点在画板坐标下的包围盒。 */
private fun unionRect(nodes: List<ResolvedNode>, ids: Set<String>): Rect? {
    var rect: Rect? = null
    flattenResolved(nodes).forEach { node ->
        if (node.id !in ids) return@forEach
        val item = Rect(node.x, node.y, node.right, node.bottom)
        rect = rect?.let {
            Rect(
                min(it.left, item.left),
                min(it.top, item.top),
                max(it.right, item.right),
                max(it.bottom, item.bottom),
            )
        } ?: item
    }
    return rect
}

/** 从上往下命中最近绘制的节点；子节点坐标相对父节点。 */
private fun hitTestNode(
    nodes: List<DesignNode>,
    point: Offset,
    origin: Offset,
    parentAlpha: Float,
): DesignNode? {
    for (node in nodes.asReversed()) {
        val at = origin + Offset(node.x, node.y)
        val alpha = parentAlpha * (node.opacity ?: 1f)
        val child = hitTestNode(node.children, point, at, alpha)
        if (child != null) return child
        if (alpha > .05f && node.type != "decor" &&
            point.x in at.x..(at.x + node.w) && point.y in at.y..(at.y + node.h)
        ) {
            return node
        }
    }
    return null
}

/** 颜色解析统一走 [DesignSpec]，与网页预览 / 桌面端保持同一套语义。 */
private fun designColor(raw: String?, fallback: Color): Color = DesignSpec.color(raw, fallback)
