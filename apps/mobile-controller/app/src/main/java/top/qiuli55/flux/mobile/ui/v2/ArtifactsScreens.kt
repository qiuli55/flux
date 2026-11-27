package top.qiuli55.flux.mobile.ui.v2

import android.annotation.SuppressLint
import android.content.Intent
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.DesignServices
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import top.qiuli55.flux.mobile.data.Artifact

private const val FILTER_ALL = ""
private const val FILTER_DESIGN = "design"
private const val FILTER_PAGE = "page"
private const val FILTER_DOC = "doc"

private fun artifactTypeLabel(type: String): String = when (type) {
    "design" -> "设计"
    "doc" -> "文档"
    else -> "页面"
}

private fun artifactTypeIcon(type: String): ImageVector = when (type) {
    "design" -> Icons.Outlined.Layers
    "doc" -> Icons.Outlined.Description
    else -> Icons.Outlined.Language
}

/** ISO 时间串只展示到分钟；不认识的格式原样返回。 */
private fun prettyTime(raw: String): String {
    if (raw.isBlank()) return ""
    return raw.replace('T', ' ').take(16)
}

/**
 * Artifacts 一级页面：设计稿、页面、文档放在同一个卡片网格里。
 *
 * 设计卡点开进原生画布；页面 / 文档点开详情预览与编辑。「设计工作室」作为设计类
 * 的二级管理页（新建 / 导入 / 重命名 / 删除 / 导出），从筛选行右侧进入。
 */
@Composable
fun ArtifactsScreen(
    state: ArtifactsState,
    extraTop: Dp,
    showBack: Boolean,
    onBack: () -> Unit,
    onReload: () -> Unit,
    onFilter: (String) -> Unit,
    onOpenArtifact: (String) -> Unit,
    onOpenDesign: (String) -> Unit,
    onCreate: (String, String) -> Unit,
    onOpenDesignStudio: () -> Unit,
    onDismissMessages: () -> Unit,
) {
    var newOpen by remember { mutableStateOf(false) }
    val visible = state.visible

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            V2PageTitle(
                title = "Artifacts",
                modifier = Modifier.padding(top = extraTop),
                subtitle = when {
                    state.loading && state.artifacts.isEmpty() -> "正在读取产物…"
                    state.artifacts.isEmpty() -> "对话里产出的成品会出现在这里"
                    else -> "${state.artifacts.size} 个产物 · 设计、页面与文档"
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
                    RoundIconButton(
                        Icons.Outlined.DesignServices,
                        onClick = onOpenDesignStudio,
                        size = 34.dp,
                        iconSize = 17.dp,
                        contentDescription = "设计工作室",
                    )
                },
            )

            ArtifactFlash(state.error ?: state.notice, state.error != null, onDismissMessages)

            LazyRow(
                Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    ArtifactFilterChip(
                        "全部",
                        state.countOf(FILTER_ALL),
                        state.filter == FILTER_ALL,
                    ) { onFilter(FILTER_ALL) }
                }
                item {
                    ArtifactFilterChip(
                        "设计",
                        state.countOf(FILTER_DESIGN),
                        state.filter == FILTER_DESIGN,
                    ) { onFilter(FILTER_DESIGN) }
                }
                item {
                    ArtifactFilterChip(
                        "页面",
                        state.countOf(FILTER_PAGE),
                        state.filter == FILTER_PAGE,
                    ) { onFilter(FILTER_PAGE) }
                }
                item {
                    ArtifactFilterChip(
                        "文档",
                        state.countOf(FILTER_DOC),
                        state.filter == FILTER_DOC,
                    ) { onFilter(FILTER_DOC) }
                }
            }

            when {
                state.loading && state.artifacts.isEmpty() ->
                    ArtifactEmpty("正在读取产物…") {}
                visible.isEmpty() -> ArtifactEmpty(
                    when (state.filter) {
                        FILTER_DESIGN -> "还没有设计稿\n进设计工作室新建或导入一份"
                        FILTER_PAGE -> "还没有页面\n点右下角 + 新建一个"
                        FILTER_DOC -> "还没有文档\n点右下角 + 新建一篇"
                        else -> "还没有产物\n点右下角 + 新建页面或文档"
                    },
                    onReload,
                )
                else -> V2PullRefresh(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    onRefresh = {
                        onReload()
                        kotlinx.coroutines.delay(500)
                    },
                ) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (state.filter == FILTER_DESIGN) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                DesignStudioEntry(onClick = onOpenDesignStudio)
                            }
                        }
                        items(visible, key = { it.id }) { artifact ->
                            ArtifactGridCard(artifact) {
                                val designId = artifact.designId
                                if (designId != null) onOpenDesign(designId)
                                else onOpenArtifact(artifact.id)
                            }
                        }
                    }
                }
            }
        }

        V2Fab(
            onClick = { newOpen = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = 24.dp),
            contentDescription = "新建产物",
        )

        if (newOpen) {
            ArtifactCreateSheet(
                creating = state.creating,
                onDismiss = { newOpen = false },
                onCreate = { type, title ->
                    onCreate(type, title)
                    newOpen = false
                },
            )
        }
    }
}

@Composable
private fun ArtifactFilterChip(text: String, count: Int, active: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(999.dp)
    Row(
        Modifier
            .clip(shape)
            .background(if (active) V2Colors.accent else V2Colors.surface)
            .border(1.dp, if (active) Color.Transparent else V2Colors.lineSoft, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Label(
            text,
            11.5f,
            if (active) V2Colors.actionInk else V2Colors.ink(.66f),
            if (active) FontWeight.SemiBold else FontWeight.Medium,
        )
        Spacer(Modifier.width(5.dp))
        Label(
            "$count",
            10f,
            if (active) V2Colors.actionInk.copy(alpha = .62f) else V2Colors.ink(.34f),
        )
    }
}

@Composable
private fun ArtifactGridCard(artifact: Artifact, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    WhiteCard(
        Modifier
            .clip(shape)
            .clickable(onClick = onClick),
    ) {
        ArtifactCover(artifact)
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Label(
                artifact.title.ifBlank { "未命名产物" },
                12.5f,
                V2Colors.ink(.92f),
                FontWeight.SemiBold,
                maxLines = 2,
            )
            Spacer(Modifier.height(4.dp))
            Label(artifactMeta(artifact), 9f, V2Colors.ink(.38f), maxLines = 1)
        }
    }
}

@Composable
private fun ArtifactCover(artifact: Artifact) {
    val tint = V2Colors.ink(.86f)
    val type = artifact.type
    Box(
        Modifier
            .fillMaxWidth()
            .height(112.dp)
            .background(
                Brush.linearGradient(
                    listOf(
                        V2Colors.ink(.055f),
                        V2Colors.ink(.015f),
                    ),
                ),
            )
            .padding(12.dp),
    ) {
        Icon(
            artifactTypeIcon(type),
            contentDescription = null,
            tint = tint.copy(alpha = .34f),
            modifier = Modifier.size(18.dp).align(Alignment.TopStart),
        )
        when (type) {
            "design" -> Label(
                "Aa", 26f, tint.copy(alpha = .82f), FontWeight.Bold,
                modifier = Modifier.align(Alignment.Center),
            )
            "doc" -> Label(
                "#", 30f, tint.copy(alpha = .82f), FontWeight.Bold,
                modifier = Modifier.align(Alignment.Center),
            )
            else -> Label(
                "</>", 20f, tint.copy(alpha = .82f), FontWeight.Bold,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        Label(
            if (type == "design") "${artifact.screenCount} 屏" else artifactTypeLabel(type),
            9f,
            V2Colors.ink(.38f),
            modifier = Modifier.align(Alignment.BottomEnd),
        )
    }
}

private fun artifactMeta(artifact: Artifact): String {
    val type = artifactTypeLabel(artifact.type)
    val time = prettyTime(artifact.updatedAt)
    return when {
        artifact.type == "design" && time.isNotBlank() -> "$type · $time"
        artifact.type == "design" -> "$type · v${artifact.version}"
        time.isNotBlank() -> "$type · $time"
        else -> "$type · v${artifact.version}"
    }
}

@Composable
private fun ArtifactEmpty(text: String, onReload: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Label(text, 12.5f, V2Colors.ink(.40f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(14.dp))
        GhostButton("刷新", onReload)
        Spacer(Modifier.height(90.dp))
    }
}

@Composable
private fun DesignStudioEntry(onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(V2Colors.surface)
            .border(1.dp, V2Colors.lineSoft, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.DesignServices, null, tint = V2Colors.ink(.62f), modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Label("设计工作室", 12.5f, V2Colors.ink(.88f), FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Label("新建、导入、导出与 AI 改稿", 9.5f, V2Colors.ink(.38f), maxLines = 1)
        }
        Icon(Icons.Outlined.Add, null, tint = V2Colors.ink(.32f), modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun ArtifactCreateSheet(
    creating: Boolean,
    onDismiss: () -> Unit,
    onCreate: (String, String) -> Unit,
) {
    var type by remember { mutableStateOf("page") }
    var title by remember { mutableStateOf("") }
    SheetFrame(onDismiss = onDismiss, maxHeightFraction = .58f) {
        SheetHeader(
            "新建产物",
            "页面是单文件 HTML；文档是 Markdown。设计稿请进设计工作室。",
            onClose = onDismiss,
        )
        Column(Modifier.padding(horizontal = 20.dp).imePadding()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ArtifactTypeOption(
                    "页面", Icons.Outlined.Language, type == "page", Modifier.weight(1f),
                ) { type = "page" }
                ArtifactTypeOption(
                    "文档", Icons.Outlined.Description, type == "doc", Modifier.weight(1f),
                ) { type = "doc" }
            }
            Spacer(Modifier.height(14.dp))
            Label("标题", 10f, V2Colors.ink(.42f), FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(V2Colors.ink(.05f))
                    .padding(horizontal = 12.dp, vertical = 12.dp),
            ) {
                BasicTextField(
                    value = title,
                    onValueChange = { if (it.length <= 60) title = it },
                    singleLine = true,
                    textStyle = TextStyle(
                        color = V2Colors.ink(.92f),
                        fontSize = 13.sp,
                    ),
                    cursorBrush = SolidColor(V2Colors.ink(.72f)),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner ->
                        if (title.isEmpty()) {
                            Label("例如：产品落地页 / 发布说明", 12.5f, V2Colors.ink(.28f))
                        }
                        inner()
                    },
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GhostButton("取消", onDismiss, Modifier.weight(1f))
                Box(Modifier.weight(1f)) {
                    AccentButton(
                        text = if (creating) "正在创建…" else "创建",
                        enabled = !creating && title.isNotBlank(),
                        onClick = { onCreate(type, title.trim()) },
                    )
                }
            }
            Spacer(Modifier.height(22.dp))
        }
    }
}

@Composable
private fun ArtifactTypeOption(
    text: String,
    icon: ImageVector,
    active: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier
            .clip(shape)
            .background(if (active) V2Colors.ink(.07f) else V2Colors.ink(.03f))
            .border(1.dp, if (active) V2Colors.ink(.22f) else Color.Transparent, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = V2Colors.ink(if (active) .82f else .48f), modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Label(text, 12f, V2Colors.ink(if (active) .88f else .56f), FontWeight.Medium)
    }
}

/**
 * 产物详情：页面用 WebView 预览 HTML，文档用简易 Markdown 渲染，源码模式可直接改。
 *
 * 设计稿不会进这里——点设计卡直接进原生画布。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ArtifactDetailScreen(
    artifactId: String,
    state: ArtifactsState,
    extraTop: Dp,
    showBack: Boolean,
    onBack: () -> Unit,
    onSave: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onOpenSourceTask: (String) -> Unit = {},
    onDismissMessages: () -> Unit,
) {
    val artifact = state.detail?.takeIf { it.id == artifactId }
    var title by remember(artifactId) { mutableStateOf("") }
    var content by remember(artifactId) { mutableStateOf("") }
    var editing by remember(artifactId) { mutableStateOf(false) }
    var deleteOpen by remember(artifactId) { mutableStateOf(false) }
    val context = LocalContext.current

    // 只按产物 id 初始化本地草稿：保存冲突后刷新版本号不能把用户正在改的内容冲掉。
    LaunchedEffect(artifact?.id) {
        val loaded = artifact ?: return@LaunchedEffect
        title = loaded.title
        content = loaded.content.orEmpty()
    }

    fun shareArtifact() {
        val current = artifact ?: return
        val doc = current.type == "doc"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = if (doc) "text/markdown" else "text/html"
            putExtra(Intent.EXTRA_SUBJECT, title.ifBlank { current.title })
            putExtra(Intent.EXTRA_TEXT, content)
        }
        context.startActivity(
            Intent.createChooser(intent, if (doc) "导出 Markdown" else "导出 HTML"),
        )
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            V2PageTitle(
                title = artifact?.title?.ifBlank { "未命名产物" } ?: "产物详情",
                modifier = Modifier.padding(top = extraTop),
                subtitle = artifact?.let {
                    "${artifactTypeLabel(it.type)} · v${it.version}"
                } ?: "正在读取…",
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
                    if (artifact != null) {
                        RoundIconButton(
                            Icons.Outlined.Share,
                            onClick = { shareArtifact() },
                            size = 34.dp,
                            iconSize = 16.dp,
                            contentDescription = if (artifact.type == "doc") {
                                "导出 Markdown"
                            } else {
                                "导出 HTML"
                            },
                        )
                        Spacer(Modifier.width(8.dp))
                        RoundIconButton(
                            Icons.Outlined.Delete,
                            onClick = { deleteOpen = true },
                            size = 34.dp,
                            iconSize = 16.dp,
                            contentDescription = "删除",
                        )
                        Spacer(Modifier.width(8.dp))
                        RoundIconButton(
                            Icons.Outlined.Save,
                            onClick = { onSave(title, content) },
                            size = 34.dp,
                            iconSize = 16.dp,
                            contentDescription = if (state.saving) "正在保存" else "保存",
                        )
                    }
                },
            )

            ArtifactFlash(state.error ?: state.notice, state.error != null, onDismissMessages)

            when {
                artifact == null && state.detailLoading ->
                    ArtifactEmpty("正在读取产物…") {}
                artifact == null -> ArtifactEmpty("产物不存在或已被删除", {})
                else -> Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                    ArtifactModeSwitch(
                        typeLabel = artifactTypeLabel(artifact.type),
                        editing = editing,
                        onToggle = { editing = !editing },
                    )
                    if (state.error != null) {
                        // 保存失败 / 版本冲突后保留本地草稿，给一个明确的“再试一次”入口。
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Label("这次修改还没有保存", 10.5f, V2Colors.rose, FontWeight.Medium)
                            Spacer(Modifier.weight(1f))
                            GhostButton(
                                if (state.saving) "正在重试…" else "重试保存",
                                onClick = { if (!state.saving) onSave(title, content) },
                            )
                        }
                    }
                    artifact.sourceTaskId?.let { taskId ->
                        Spacer(Modifier.height(8.dp))
                        SourceConversationRow(
                            taskId = taskId,
                            onOpen = { onOpenSourceTask(taskId) },
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    if (editing) {
                        ArtifactEditor(
                            title = title,
                            content = content,
                            onTitleChange = { title = it.take(60) },
                            onContentChange = { content = it },
                            modifier = Modifier.weight(1f).imePadding(),
                        )
                    } else if (artifact.type == "page") {
                        ArtifactHtmlPreview(
                            html = content,
                            versionKey = "${artifact.id}:${artifact.version}",
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        ArtifactDocPreview(content, Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(14.dp))
                }
            }
        }

        if (deleteOpen && artifact != null) {
            SheetFrame(onDismiss = { deleteOpen = false }, maxHeightFraction = .44f) {
                SheetHeader(
                    "删除「${artifact.title}」？",
                    "删除后无法恢复，设计稿的删除仍在设计工作室里。",
                    onClose = { deleteOpen = false },
                )
                Column(Modifier.padding(horizontal = 20.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GhostButton("取消", { deleteOpen = false }, Modifier.weight(1f))
                        Box(Modifier.weight(1f)) {
                            AccentButton(
                                text = "删除",
                                enabled = state.deletingId == null,
                                onClick = {
                                    deleteOpen = false
                                    onDelete(artifact.id)
                                },
                            )
                        }
                    }
                    Spacer(Modifier.height(22.dp))
                }
            }
        }
    }
}

@Composable
private fun ArtifactModeSwitch(typeLabel: String, editing: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(V2Colors.ink(.04f))
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Label(typeLabel, 10f, V2Colors.ink(.36f), modifier = Modifier.padding(horizontal = 8.dp))
        Spacer(Modifier.weight(1f))
        ArtifactModeButton("预览", !editing, onToggle)
        Spacer(Modifier.width(4.dp))
        ArtifactModeButton("源码", editing, onToggle)
    }
}

@Composable
private fun ArtifactModeButton(text: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(if (active) V2Colors.surface else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Label(
            text,
            10.5f,
            if (active) V2Colors.ink(.88f) else V2Colors.ink(.44f),
            if (active) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}

@Composable
private fun ArtifactHtmlPreview(html: String, versionKey: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(V2Colors.surface)
            .border(1.dp, V2Colors.lineSoft, RoundedCornerShape(16.dp)),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        loadWithOverviewMode = true
                        useWideViewPort = true
                        setSupportZoom(true)
                        builtInZoomControls = true
                        displayZoomControls = false
                        mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    }
                }
            },
            update = { web ->
                if (web.tag != versionKey) {
                    web.tag = versionKey
                    web.loadDataWithBaseURL(
                        "https://flux-artifact.local/",
                        html.ifBlank { "<html><body></body></html>" },
                        "text/html",
                        "utf-8",
                        null,
                    )
                }
            },
        )
    }
}

/**
 * 文档预览：覆盖标题、有序 / 无序列表、代码块、引用、链接与粗体 / 斜体。
 *
 * 不引入第三方 Markdown 渲染器；解析器只做常用语法的只读展示，源码模式仍可编辑。
 */
@Composable
private fun ArtifactDocPreview(content: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(V2Colors.surface)
            .border(1.dp, V2Colors.lineSoft, RoundedCornerShape(16.dp))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        val lines = content.split('\n')
        var index = 0
        while (index < lines.size) {
            val raw = lines[index].trimEnd()
            val trimmed = raw.trimStart()
            if (trimmed.startsWith("```")) {
                val code = mutableListOf<String>()
                index += 1
                while (index < lines.size && !lines[index].trimStart().startsWith("```")) {
                    code.add(lines[index])
                    index += 1
                }
                if (index < lines.size) index += 1 // 跳过收尾的 ```
                CodeBlock(code.joinToString("\n"))
                continue
            }
            when {
                raw.isBlank() -> Spacer(Modifier.height(8.dp))
                Regex("^#{1,6}\\s+").containsMatchIn(trimmed) -> {
                    val level = trimmed.takeWhile { it == '#' }.length
                    val size = when (level) {
                        1 -> 19f
                        2 -> 16f
                        3 -> 14f
                        4 -> 13f
                        else -> 12f
                    }
                    MarkdownInlineText(
                        text = trimmed.drop(level).trimStart(),
                        size = size,
                        color = V2Colors.ink(.95f),
                        weight = if (level <= 2) FontWeight.Bold else FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(5.dp))
                }
                Regex("^[-*+]\\s+").containsMatchIn(trimmed) -> {
                    MarkdownListRow(
                        marker = "•",
                        text = trimmed.drop(2),
                    )
                }
                Regex("^\\d+[.)]\\s+").containsMatchIn(trimmed) -> {
                    val marker = trimmed.substringBefore(' ').trimEnd('.', ')')
                    MarkdownListRow(marker = "$marker.", text = trimmed.substringAfter(' ').trimStart())
                }
                trimmed.startsWith("> ") || trimmed == ">" -> {
                    Row(Modifier.fillMaxWidth()) {
                        Box(
                            Modifier
                                .width(2.dp)
                                .heightIn(min = 18.dp)
                                .background(V2Colors.ink(.18f)),
                        )
                        Spacer(Modifier.width(10.dp))
                        MarkdownInlineText(
                            text = trimmed.removePrefix(">").trimStart(),
                            size = 12.5f,
                            color = V2Colors.ink(.55f),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                }
                Regex("^-{3,}$").matches(trimmed) -> {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                            .height(1.dp)
                            .background(V2Colors.ink(.08f)),
                    )
                }
                else -> {
                    MarkdownInlineText(raw, 12.5f, V2Colors.ink(.78f))
                    Spacer(Modifier.height(3.dp))
                }
            }
            index += 1
        }
        if (content.isBlank()) {
            Label("还没有内容，切到「源码」写点什么。", 12f, V2Colors.ink(.35f))
        }
    }
}

@Composable
private fun MarkdownListRow(marker: String, text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Label(marker, 12f, V2Colors.ink(.5f), modifier = Modifier.width(20.dp))
        MarkdownInlineText(text, 12.5f, V2Colors.ink(.78f), modifier = Modifier.weight(1f))
    }
}

@Composable
private fun CodeBlock(code: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(V2Colors.ink(.05f))
            .border(1.dp, V2Colors.ink(.06f), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Label(code.ifBlank { " " }, 11f, V2Colors.ink(.82f), mono = true)
    }
}

@Composable
private fun MarkdownInlineText(
    text: String,
    size: Float,
    color: Color,
    weight: FontWeight = FontWeight.Normal,
    modifier: Modifier = Modifier,
) {
    Text(
        text = inlineMarkdown(text),
        modifier = modifier,
        color = color,
        fontSize = size.sp,
        fontWeight = weight,
        lineHeight = (size * 1.55f).sp,
    )
}

/**
 * 行内 Markdown：`**粗体**`、`*斜体*`、`_斜体_`、`` `代码` ``、`[文字](链接)`。
 *
 * 链接以「文字 (URL)」展示，保证在不能跳转的预览里也能读到地址。
 */
internal fun inlineMarkdown(text: String): AnnotatedString = buildAnnotatedString {
    val pattern = Regex(
        """\*\*(.+?)\*\*|(?<!\*)\*(?!\*)(.+?)(?<!\*)\*(?!\*)|(?<![\w])_(.+?)_(?![\w])|`(.+?)`|\[([^\]]+)\]\(([^)]+)\)""",
    )
    var last = 0
    for (match in pattern.findAll(text)) {
        if (match.range.first > last) append(text.substring(last, match.range.first))
        val values = match.groupValues
        when {
            values[1].isNotEmpty() ->
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(values[1]) }
            values[2].isNotEmpty() ->
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(values[2]) }
            values[3].isNotEmpty() ->
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(values[3]) }
            values[4].isNotEmpty() ->
                withStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = V2Colors.ink(.06f),
                    ),
                ) { append(values[4]) }
            values[5].isNotEmpty() -> {
                withStyle(
                    SpanStyle(
                        textDecoration = TextDecoration.Underline,
                        fontWeight = FontWeight.Medium,
                    ),
                ) { append(values[5]) }
                withStyle(SpanStyle(color = V2Colors.ink(.42f))) {
                    append(" (${values[6]})")
                }
            }
            else -> append(match.value)
        }
        last = match.range.last + 1
    }
    if (last < text.length) append(text.substring(last))
}

@Composable
private fun SourceConversationRow(taskId: String, onOpen: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(V2Colors.ink(.04f))
            .border(1.dp, V2Colors.ink(.06f), RoundedCornerShape(12.dp))
            .clickable(onClick = onOpen)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Chat,
            contentDescription = null,
            tint = V2Colors.ink(.55f),
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Label("打开来源对话", 12f, V2Colors.ink(.82f), FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Label("这段产物由对话发布 · ${taskId.take(8)}", 9.5f, V2Colors.ink(.38f), maxLines = 1)
        }
        Icon(
            Icons.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = V2Colors.ink(.32f),
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun ArtifactEditor(
    title: String,
    content: String,
    onTitleChange: (String) -> Unit,
    onContentChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(V2Colors.ink(.045f))
                .padding(horizontal = 12.dp, vertical = 11.dp),
        ) {
            BasicTextField(
                value = title,
                onValueChange = onTitleChange,
                singleLine = true,
                textStyle = TextStyle(color = V2Colors.ink(.92f), fontSize = 13.sp),
                cursorBrush = SolidColor(V2Colors.ink(.72f)),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    if (title.isEmpty()) Label("标题", 12.5f, V2Colors.ink(.28f))
                    inner()
                },
            )
        }
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(V2Colors.surface)
                .border(1.dp, V2Colors.lineSoft, RoundedCornerShape(16.dp))
                .padding(12.dp),
        ) {
            BasicTextField(
                value = content,
                onValueChange = onContentChange,
                textStyle = TextStyle(
                    color = V2Colors.ink(.80f),
                    fontSize = 11.5.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 17.sp,
                ),
                cursorBrush = SolidColor(V2Colors.ink(.72f)),
                modifier = Modifier.fillMaxSize(),
                decorationBox = { inner ->
                    if (content.isEmpty()) {
                        Label("在这里粘贴 HTML 或 Markdown", 11.5f, V2Colors.ink(.28f), mono = true)
                    }
                    inner()
                },
            )
        }
    }
}

/** 页面提示条：错误红、提示绿，与全局 flash 的语义一致。 */
@Composable
private fun ArtifactFlash(message: String?, isError: Boolean, onDismiss: () -> Unit) {
    if (message.isNullOrBlank()) return
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background((if (isError) V2Colors.rose else V2Colors.emerald).copy(alpha = .10f))
            .clickable(onClick = onDismiss)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Label(
            message,
            10.5f,
            if (isError) V2Colors.rose else V2Colors.emerald700,
            FontWeight.Medium,
            maxLines = 2,
        )
    }
}

@Composable
private fun AccentButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (enabled) V2Colors.accent else V2Colors.ink(.12f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Label(
            text,
            12.5f,
            if (enabled) V2Colors.actionInk else V2Colors.ink(.38f),
            FontWeight.SemiBold,
        )
    }
}
