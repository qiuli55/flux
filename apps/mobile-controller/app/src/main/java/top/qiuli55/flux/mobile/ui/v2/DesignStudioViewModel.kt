package top.qiuli55.flux.mobile.ui.v2

import android.util.Log
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import top.qiuli55.flux.mobile.data.DesignAiOp
import top.qiuli55.flux.mobile.data.DesignDocument
import top.qiuli55.flux.mobile.data.DesignNode
import top.qiuli55.flux.mobile.data.DesignRevision
import top.qiuli55.flux.mobile.data.DesignScreen
import top.qiuli55.flux.mobile.data.DesignSummary
import top.qiuli55.flux.mobile.data.DesignWebPreview
import top.qiuli55.flux.mobile.data.FluxApi
import top.qiuli55.flux.mobile.data.FluxApiException
import top.qiuli55.flux.mobile.ui.FluxViewModel

data class DesignStudioState(
    val designs: List<DesignSummary> = emptyList(),
    val loading: Boolean = false,
    val openingId: String? = null,
    val document: DesignDocument? = null,
    val screenIndex: Int = 0,
    val selectedNodeId: String? = null,
    /** 多选：AI 可以一次改多个图层；单选时只有一项。 */
    val selectedNodeIds: Set<String> = emptySet(),
    val multiSelect: Boolean = false,
    /** AI 作用范围：selection（选中的组件）或 screen（整个画板）。 */
    val aiScope: String = "selection",
    val dirty: Boolean = false,
    val saving: Boolean = false,
    val creating: Boolean = false,
    val renamingId: String? = null,
    val deletingId: String? = null,
    val asking: Boolean = false,
    val aiReply: String? = null,
    val error: String? = null,
    val notice: String? = null,
    /** 本地撤销栈：每次手工编辑 / AI 修改前的画板快照（最多 30 步）。 */
    val undoStack: List<DesignDocument> = emptyList(),
    val revisions: List<DesignRevision> = emptyList(),
    val revisionsLoading: Boolean = false,
    val restoringVersion: Int? = null,
    /** 网页预览：服务端渲染出的单文件 HTML。 */
    val webPreview: DesignWebPreview? = null,
    val previewLoading: Boolean = false,
    /** 导入中（.fig / .sketch / .svg 上传并解析）。 */
    val importing: Boolean = false,
    /** 正在导出 SVG 的设计稿 id。 */
    val exportingId: String? = null,
) {
    val screen get() = document?.screens?.getOrNull(screenIndex)
    val selectedNode: DesignNode?
        get() {
            val nodeId = selectedNodeId ?: return null
            val nodes = screen?.nodes ?: return null
            return findDesignNode(nodes, nodeId)
        }

    /** 当前选中的所有图层（按画板顺序），供多选与整屏 AI 使用。 */
    val selectedNodes: List<DesignNode>
        get() {
            val nodes = screen?.nodes ?: return emptyList()
            if (selectedNodeIds.isEmpty()) {
                val single = selectedNode ?: return emptyList()
                return listOf(single)
            }
            return selectedNodeIds.mapNotNull { findDesignNode(nodes, it) }
        }

    val canUndo: Boolean get() = undoStack.isNotEmpty()
}

private fun findDesignNode(nodes: List<DesignNode>, id: String): DesignNode? {
    for (node in nodes) {
        if (node.id == id) return node
        findDesignNode(node.children, id)?.let { return it }
    }
    return null
}

private fun mapDesignNode(
    nodes: List<DesignNode>,
    id: String,
    transform: (DesignNode) -> DesignNode,
): List<DesignNode> = nodes.map { node ->
    when {
        node.id == id -> transform(node)
        node.children.isNotEmpty() -> node.copy(children = mapDesignNode(node.children, id, transform))
        else -> node
    }
}

/** 批量修改多个图层（拖拽多选时用）。 */
private fun mapDesignNodes(
    nodes: List<DesignNode>,
    ids: Set<String>,
    transform: (DesignNode) -> DesignNode,
): List<DesignNode> = nodes.map { node ->
    when {
        node.id in ids -> transform(node)
        node.children.isNotEmpty() -> node.copy(children = mapDesignNodes(node.children, ids, transform))
        else -> node
    }
}

/**
 * 手机端设计工作室：列表、打开画布、轻编辑与 AI 改组件。
 *
 * 所有修改只落在本地 [DesignStudioState.document]，用户点保存才提交；服务端按 version
 * 做乐观锁，409 时不覆盖别人的改动，直接拉最新版本并提示。
 */
class DesignStudioViewModel : FluxViewModel() {

    private companion object {
        const val TAG = "DesignStudio"

        /** 撤销栈上限：手机内存有限，只留最近 30 步。 */
        const val UNDO_LIMIT = 30

        /** 同一字段的连续输入（打字、拖滑杆）合并成一次撤销，避免每帧压栈。 */
        const val EDIT_COALESCE_MS = 1200L
    }

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = true
    }

    private val _state = MutableStateFlow(DesignStudioState())
    val state = _state.asStateFlow()

    /** 最近一次保存 / 打开时的画板对象，仅用于引用比较判断 dirty，不做深比较。 */
    private var baseline: DesignDocument? = null
    private var lastEditKey: String? = null
    private var lastEditAt: Long = 0L

    private var listJob: Job? = null
    private var openJob: Job? = null
    private var saveJob: Job? = null
    private var askJob: Job? = null
    private var previewJob: Job? = null
    private var revisionsJob: Job? = null
    private var revertJob: Job? = null

    // ------------------------------------------------------------ 列表

    fun loadDesigns(quiet: Boolean = false) {
        if (listJob?.isActive == true) return
        listJob = viewModelScope.launch {
            if (!quiet) _state.value = _state.value.copy(loading = true, error = null)
            try {
                val designs = api().listDesigns()
                Log.d(TAG, "loadDesigns ok count=${designs.size}")
                _state.value = _state.value.copy(designs = designs, loading = false, error = null)
            } catch (e: Exception) {
                Log.w(TAG, "loadDesigns failed", e)
                _state.value = _state.value.copy(loading = false, error = readable(e))
            }
        }
    }

    fun createDesign(name: String, onCreated: (String) -> Unit = {}) {
        if (_state.value.creating) return
        viewModelScope.launch {
            _state.value = _state.value.copy(creating = true, error = null, notice = null)
            try {
                val created = api().createDesign(name.ifBlank { "未命名设计" })
                Log.d(TAG, "createDesign ok id=${created.id}")
                _state.value = _state.value.copy(creating = false, notice = "已创建空白设计")
                loadDesigns(quiet = true)
                openDesign(created.id)
                onCreated(created.id)
            } catch (e: Exception) {
                Log.w(TAG, "createDesign failed name=$name", e)
                _state.value = _state.value.copy(creating = false, error = readable(e))
            }
        }
    }

    /** 从列表重命名：先取最新版本再整份保存，避免覆盖别人刚改的内容。 */
    fun renameDesign(designId: String, name: String) {
        val clean = name.trim()
        if (clean.isEmpty() || _state.value.renamingId != null) return
        viewModelScope.launch {
            _state.value = _state.value.copy(renamingId = designId, error = null, notice = null)
            try {
                val latest = api().getDesign(designId)
                val saved = api().saveDesign(latest.copy(name = clean))
                Log.d(TAG, "renameDesign ok id=$designId version=${saved.version}")
                _state.value = _state.value.copy(
                    renamingId = null,
                    designs = _state.value.designs.map { if (it.id == designId) it.copy(name = saved.name) else it },
                    notice = "已重命名为「${saved.name}」",
                )
                loadDesigns(quiet = true)
            } catch (e: FluxApiException) {
                Log.w(TAG, "renameDesign failed id=$designId code=${e.code}", e)
                _state.value = _state.value.copy(
                    renamingId = null,
                    error = if (e.code == "design_conflict") {
                        "设计稿刚在别处更新，请刷新后重新改名"
                    } else {
                        readable(e)
                    },
                )
                if (e.code == "design_conflict") loadDesigns(quiet = true)
            } catch (e: Exception) {
                Log.w(TAG, "renameDesign failed id=$designId", e)
                _state.value = _state.value.copy(renamingId = null, error = readable(e))
            }
        }
    }

    /** 删除设计稿：服务端已永久删除，列表就地移除。 */
    fun deleteDesign(designId: String) {
        if (_state.value.deletingId != null) return
        viewModelScope.launch {
            _state.value = _state.value.copy(deletingId = designId, error = null, notice = null)
            try {
                api().deleteDesign(designId)
                Log.d(TAG, "deleteDesign ok id=$designId")
                if (_state.value.document?.id == designId) closeDesign()
                _state.value = _state.value.copy(
                    deletingId = null,
                    designs = _state.value.designs.filterNot { it.id == designId },
                    notice = "设计稿已删除",
                )
            } catch (e: Exception) {
                Log.w(TAG, "deleteDesign failed id=$designId", e)
                _state.value = _state.value.copy(deletingId = null, error = readable(e))
            }
        }
    }

    /** 导入 Figma(.fig) / Sketch(.sketch) / SVG：服务端解析成 Luma 节点后直接打开。 */
    fun importDesign(
        name: String,
        size: Long,
        openStream: () -> java.io.InputStream,
        onImported: (String) -> Unit = {},
    ) {
        if (_state.value.importing) return
        viewModelScope.launch {
            _state.value = _state.value.copy(importing = true, error = null, notice = null)
            try {
                val imported = api().importDesign(name, size, openStream = openStream)
                val screens = imported.screens.size
                Log.d(TAG, "importDesign ok id=${imported.id} screens=$screens file=$name")
                _state.value = _state.value.copy(
                    importing = false,
                    notice = "已导入「${imported.name}」· $screens 个画板 / ${imported.layerCount} 层",
                )
                loadDesigns(quiet = true)
                openDesign(imported.id)
                onImported(imported.id)
            } catch (e: Exception) {
                Log.w(TAG, "importDesign failed file=$name", e)
                _state.value = _state.value.copy(importing = false, error = readable(e))
            }
        }
    }

    /** 导出 SVG：字节交给界面用系统文件选择器另存，VM 不碰 ContentResolver。 */
    fun exportDesignSvg(
        designId: String,
        screen: String = "all",
        onReady: (ByteArray) -> Unit,
    ) {
        if (_state.value.exportingId != null) return
        viewModelScope.launch {
            _state.value = _state.value.copy(exportingId = designId, error = null)
            try {
                val bytes = api().exportDesignSvg(designId, screen)
                Log.d(TAG, "exportDesignSvg ok id=$designId screen=$screen bytes=${bytes.size}")
                _state.value = _state.value.copy(exportingId = null)
                onReady(bytes)
            } catch (e: Exception) {
                Log.w(TAG, "exportDesignSvg failed id=$designId", e)
                _state.value = _state.value.copy(exportingId = null, error = readable(e))
            }
        }
    }

    // ------------------------------------------------------------ 打开 / 关闭

    fun openDesign(designId: String) {
        if (openJob?.isActive == true) return
        openJob = viewModelScope.launch {
            _state.value = _state.value.copy(openingId = designId, error = null, notice = null)
            try {
                val document = api().getDesign(designId)
                Log.d(
                    TAG,
                    "openDesign ok id=$designId screens=${document.screens.size} layers=${document.layerCount}",
                )
                baseline = document
                lastEditKey = null
                _state.value = _state.value.copy(
                    openingId = null,
                    document = document,
                    screenIndex = 0,
                    selectedNodeId = null,
                    selectedNodeIds = emptySet(),
                    aiScope = "selection",
                    undoStack = emptyList(),
                    dirty = false,
                    aiReply = null,
                )
            } catch (e: Exception) {
                Log.w(TAG, "openDesign failed id=$designId", e)
                _state.value = _state.value.copy(openingId = null, error = readable(e))
            }
        }
    }

    fun closeDesign() {
        saveJob?.cancel()
        askJob?.cancel()
        previewJob?.cancel()
        revisionsJob?.cancel()
        revertJob?.cancel()
        baseline = null
        lastEditKey = null
        _state.value = _state.value.copy(
            document = null,
            screenIndex = 0,
            selectedNodeId = null,
            selectedNodeIds = emptySet(),
            multiSelect = false,
            aiScope = "selection",
            undoStack = emptyList(),
            revisions = emptyList(),
            revisionsLoading = false,
            restoringVersion = null,
            dirty = false,
            saving = false,
            asking = false,
            aiReply = null,
            previewLoading = false,
            webPreview = null,
        )
    }

    fun selectScreen(index: Int) {
        val document = _state.value.document ?: return
        if (index !in document.screens.indices) return
        _state.value = _state.value.copy(
            screenIndex = index,
            selectedNodeId = null,
            selectedNodeIds = emptySet(),
            aiScope = "selection",
            aiReply = null,
        )
    }

    fun selectNode(nodeId: String?) {
        val current = _state.value
        if (nodeId == null) {
            _state.value = current.copy(
                selectedNodeId = null,
                selectedNodeIds = emptySet(),
                aiReply = null,
            )
            return
        }
        val ids = if (current.multiSelect) {
            if (nodeId in current.selectedNodeIds) current.selectedNodeIds - nodeId
            else current.selectedNodeIds + nodeId
        } else {
            setOf(nodeId)
        }
        _state.value = current.copy(
            selectedNodeId = if (nodeId in ids) nodeId else ids.lastOrNull(),
            selectedNodeIds = ids,
            aiScope = if (ids.isEmpty()) current.aiScope else "selection",
            aiReply = null,
        )
    }

    /** 多选开关：开启后点画布是叠加选择，关闭后只保留当前主选中项。 */
    fun toggleMultiSelect() {
        val current = _state.value
        val next = !current.multiSelect
        val ids = if (next) {
            current.selectedNodeIds.ifEmpty { setOfNotNull(current.selectedNodeId) }
        } else {
            setOfNotNull(current.selectedNodeId)
        }
        _state.value = current.copy(
            multiSelect = next,
            selectedNodeIds = ids,
            selectedNodeId = ids.lastOrNull(),
            aiReply = null,
        )
    }

    fun setAiScope(scope: String) {
        if (scope !in setOf("selection", "screen")) return
        _state.value = _state.value.copy(aiScope = scope, aiReply = null)
    }

    // ------------------------------------------------------------ 轻编辑

    /**
     * 修改当前选中图层。同一个字段的连续输入（打字、拖圆角滑杆）在 [EDIT_COALESCE_MS]
     * 内合并为一次撤销，避免每敲一个字就压一层栈。
     */
    private fun editSelected(key: String, transform: (DesignNode) -> DesignNode) {
        val current = _state.value
        val nodeId = current.selectedNodeId ?: return
        val document = current.document ?: return
        val screenIndex = current.screenIndex
        val screen = document.screens.getOrNull(screenIndex) ?: return
        val updated = document.copy(
            screens = document.screens.toMutableList().apply {
                this[screenIndex] = screen.copy(nodes = mapDesignNode(screen.nodes, nodeId, transform))
            },
        )
        val fullKey = "$key:$nodeId"
        val now = System.currentTimeMillis()
        val coalesce = fullKey == lastEditKey && now - lastEditAt < EDIT_COALESCE_MS
        lastEditKey = fullKey
        lastEditAt = now
        _state.value = current.copy(
            document = updated,
            dirty = true,
            notice = null,
            undoStack = if (coalesce) current.undoStack else pushUndoStack(current.undoStack, document),
        )
    }

    fun setNodeText(text: String) = editSelected("text") { it.copy(text = text) }

    fun setNodeFill(color: String) = editSelected("fill") { it.copy(fill = color) }

    fun setNodeTextColor(color: String) = editSelected("color") { it.copy(color = color) }

    fun setNodeRadius(radius: Float) = editSelected("radius") { it.copy(radius = radius) }

    fun setNodeOpacity(opacity: Float) = editSelected("opacity") {
        it.copy(opacity = opacity.coerceIn(0.1f, 1f))
    }

    // ------------------------------------------------------------ 画布拖拽

    /** 手势开始时调用：记录这次拖动的图层，并把拖动前的画板压入撤销栈（一次手势只压一次）。 */
    fun beginNodeDrag(ids: Set<String>) {
        val current = _state.value
        val document = current.document ?: return
        if (ids.isEmpty()) return
        lastEditKey = null
        _state.value = current.copy(
            undoStack = pushUndoStack(current.undoStack, document),
            notice = null,
        )
    }

    /** 拖动中：按增量平移选中图层，并限制在画板内。 */
    fun dragNodes(ids: Set<String>, dx: Float, dy: Float) {
        if (ids.isEmpty() || (dx == 0f && dy == 0f)) return
        val current = _state.value
        val document = current.document ?: return
        val screenIndex = current.screenIndex
        val screen = document.screens.getOrNull(screenIndex) ?: return
        var changed = false
        val nodes = mapDesignNodes(screen.nodes, ids) { node ->
            val maxX = (screen.w - node.w).coerceAtLeast(0f)
            val maxY = (screen.h - node.h).coerceAtLeast(0f)
            val nextX = (node.x + dx).coerceIn(0f, maxX)
            val nextY = (node.y + dy).coerceIn(0f, maxY)
            if (nextX != node.x || nextY != node.y) {
                changed = true
                node.copy(x = nextX, y = nextY)
            } else {
                node
            }
        }
        if (!changed) return
        val screens = document.screens.toMutableList()
        screens[screenIndex] = screen.copy(nodes = nodes)
        _state.value = current.copy(document = document.copy(screens = screens), dirty = true)
    }

    // ------------------------------------------------------------ 图层编辑

    /** 图层上移 / 下移：后者在上，所以「上移」= 在列表里往后挪一位。 */
    fun moveLayer(id: String, up: Boolean) {
        val current = _state.value
        val document = current.document ?: return
        val screenIndex = current.screenIndex
        val screen = document.screens.getOrNull(screenIndex) ?: return
        val (nodes, moved) = moveSibling(screen.nodes, id, up)
        if (!moved) return
        commitScreen(current, document, screenIndex, screen, nodes, if (up) "已上移图层" else "已下移图层")
    }

    fun deleteLayer(id: String) {
        val current = _state.value
        val document = current.document ?: return
        val screenIndex = current.screenIndex
        val screen = document.screens.getOrNull(screenIndex) ?: return
        val (nodes, removed) = removeDesignNode(screen.nodes, id)
        if (!removed) return
        val screens = document.screens.toMutableList()
        screens[screenIndex] = screen.copy(nodes = nodes)
        lastEditKey = null
        _state.value = current.copy(
            document = document.copy(screens = screens),
            dirty = true,
            notice = "已删除图层",
            undoStack = pushUndoStack(current.undoStack, document),
            selectedNodeId = current.selectedNodeId?.takeIf { it != id && findDesignNode(nodes, it) != null },
            selectedNodeIds = current.selectedNodeIds.filterTo(mutableSetOf()) {
                it != id && findDesignNode(nodes, it) != null
            },
        )
    }

    /** 复制图层：深拷贝换新 id，插在原图层之后并选中副本。 */
    fun duplicateLayer(id: String) {
        val current = _state.value
        val document = current.document ?: return
        val screenIndex = current.screenIndex
        val screen = document.screens.getOrNull(screenIndex) ?: return
        val source = findDesignNode(screen.nodes, id) ?: return
        val existing = mutableSetOf<String>()
        collectNodeIds(screen.nodes, existing)
        var seq = 1
        var newId = "${source.id}-copy"
        while (newId in existing) {
            seq += 1
            newId = "${source.id}-copy-$seq"
        }
        var childSeq = 1
        val copy = cloneLayer(source, newId) { ++childSeq }
        val (nodes, inserted) = insertAfter(screen.nodes, id, copy)
        if (!inserted) return
        val screens = document.screens.toMutableList()
        screens[screenIndex] = screen.copy(nodes = nodes)
        lastEditKey = null
        _state.value = current.copy(
            document = document.copy(screens = screens),
            dirty = true,
            notice = "已复制图层",
            undoStack = pushUndoStack(current.undoStack, document),
            selectedNodeId = copy.id,
            selectedNodeIds = setOf(copy.id),
            aiScope = "selection",
        )
    }

    fun renameLayer(id: String, name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        val current = _state.value
        val document = current.document ?: return
        val screenIndex = current.screenIndex
        val screen = document.screens.getOrNull(screenIndex) ?: return
        val node = findDesignNode(screen.nodes, id) ?: return
        if (node.name == clean) return
        val nodes = mapDesignNode(screen.nodes, id) { it.copy(name = clean) }
        commitScreen(current, document, screenIndex, screen, nodes, "已重命名图层")
    }

    // ------------------------------------------------------------ 撤销

    /** 撤销上一步（手工编辑、拖动、图层操作或 AI 修改）。 */
    fun undo() {
        val current = _state.value
        val snapshot = current.undoStack.lastOrNull() ?: return
        lastEditKey = null
        val stillDirty = snapshot !== baseline
        _state.value = current.copy(
            document = snapshot,
            undoStack = current.undoStack.dropLast(1),
            dirty = stillDirty,
            selectedNodeId = null,
            selectedNodeIds = emptySet(),
            aiReply = null,
            notice = if (stillDirty) "已撤销上一步修改（画布仍未保存）" else "已撤销到已保存版本",
        )
    }

    private fun pushUndoStack(stack: List<DesignDocument>, document: DesignDocument): List<DesignDocument> =
        (stack + document).takeLast(UNDO_LIMIT)

    private fun commitScreen(
        current: DesignStudioState,
        document: DesignDocument,
        screenIndex: Int,
        screen: DesignScreen,
        nodes: List<DesignNode>,
        notice: String,
    ) {
        val screens = document.screens.toMutableList()
        screens[screenIndex] = screen.copy(nodes = nodes)
        lastEditKey = null
        _state.value = current.copy(
            document = document.copy(screens = screens),
            dirty = true,
            notice = notice,
            undoStack = pushUndoStack(current.undoStack, document),
        )
    }

    private fun moveSibling(
        nodes: List<DesignNode>,
        id: String,
        up: Boolean,
    ): Pair<List<DesignNode>, Boolean> {
        val at = nodes.indexOfFirst { it.id == id }
        if (at >= 0) {
            val target = if (up) at + 1 else at - 1
            if (target !in nodes.indices) return nodes to false
            val list = nodes.toMutableList()
            val node = list.removeAt(at)
            list.add(target, node)
            return list to true
        }
        var moved = false
        val next = nodes.map { node ->
            if (!moved && node.children.isNotEmpty()) {
                val (children, hit) = moveSibling(node.children, id, up)
                if (hit) {
                    moved = true
                    node.copy(children = children)
                } else {
                    node
                }
            } else {
                node
            }
        }
        return next to moved
    }

    private fun insertAfter(
        nodes: List<DesignNode>,
        id: String,
        node: DesignNode,
    ): Pair<List<DesignNode>, Boolean> {
        val at = nodes.indexOfFirst { it.id == id }
        if (at >= 0) {
            val list = nodes.toMutableList()
            list.add(at + 1, node)
            return list to true
        }
        var inserted = false
        val next = nodes.map { parent ->
            if (!inserted && parent.children.isNotEmpty()) {
                val (children, hit) = insertAfter(parent.children, id, node)
                if (hit) {
                    inserted = true
                    parent.copy(children = children)
                } else {
                    parent
                }
            } else {
                parent
            }
        }
        return next to inserted
    }

    /** 深拷贝图层并生成不重复的 id（父子一起换，避免与原图层冲突）。 */
    private fun cloneLayer(
        node: DesignNode,
        rootId: String,
        nextSeq: () -> Int,
    ): DesignNode = node.copy(
        id = rootId,
        children = node.children.map { child ->
            cloneLayer(child, "${rootId}-${nextSeq()}", nextSeq)
        },
    )

    private fun collectNodeIds(nodes: List<DesignNode>, out: MutableSet<String>) {
        nodes.forEach { node ->
            out.add(node.id)
            collectNodeIds(node.children, out)
        }
    }

    // ------------------------------------------------------------ AI

    fun askAi(prompt: String) {
        val current = _state.value
        val document = current.document ?: return
        val screen = current.screen ?: return
        val text = prompt.trim()
        if (text.isEmpty() || askJob?.isActive == true) return
        val targets = current.selectedNodes
        val scope = when {
            current.aiScope == "screen" -> "screen"
            targets.isEmpty() -> return
            targets.size > 1 -> "multi"
            else -> "node"
        }
        val screenIndex = current.screenIndex
        val targetIds = targets.map { it.id }.toSet()
        askJob = viewModelScope.launch {
            _state.value = _state.value.copy(asking = true, error = null, aiReply = null)
            try {
                val result = api().askDesignAi(
                    designId = document.id,
                    nodes = targets.map { nodeContext(it) },
                    screen = screenContext(screen),
                    mode = scope,
                    prompt = text,
                )
                val latest = _state.value
                val baseDoc = latest.document ?: return@launch
                val (updated, applied) = applyOps(baseDoc, screenIndex, targetIds, result.ops)
                Log.d(
                    TAG,
                    "askAi ok design=${document.id} scope=$scope targets=${targetIds.size} " +
                        "ops=${result.ops.size} applied=$applied",
                )
                if (applied > 0) lastEditKey = null
                _state.value = latest.copy(
                    asking = false,
                    document = if (applied > 0) updated else latest.document,
                    undoStack = if (applied > 0) {
                        pushUndoStack(latest.undoStack, baseDoc)
                    } else {
                        latest.undoStack
                    },
                    dirty = latest.dirty || applied > 0,
                    aiReply = result.reply.ifBlank { "已按你的要求调整画布" },
                    notice = if (applied > 0) "AI 已应用 $applied 项修改，记得保存" else null,
                )
            } catch (e: Exception) {
                Log.w(TAG, "askAi failed design=${document.id}", e)
                _state.value = _state.value.copy(asking = false, error = readable(e))
            }
        }
    }

    /** 把 op 序列套到指定画板；返回新设计稿与实际生效条数。 */
    private fun applyOps(
        document: DesignDocument,
        screenIndex: Int,
        targets: Set<String>,
        ops: List<DesignAiOp>,
    ): Pair<DesignDocument, Int> {
        val screens = document.screens.toMutableList()
        var screen = screens.getOrNull(screenIndex) ?: return document to 0
        var nodes = screen.nodes
        var applied = 0
        ops.forEach { op ->
            when (op.op.ifBlank { "set" }.lowercase()) {
                "set" -> {
                    val target = op.target?.takeIf { it.isNotBlank() } ?: targets.firstOrNull()
                    if (target.isNullOrBlank() || op.set.isEmpty()) return@forEach
                    var hit = false
                    nodes = mapDesignNode(nodes, target) { node ->
                        hit = true
                        patchNode(node, op.set)
                    }
                    if (hit) applied += 1
                }
                "add" -> {
                    val payload = op.node ?: return@forEach
                    val node = decodeNode(payload) ?: return@forEach
                    if (findDesignNode(nodes, node.id) != null) return@forEach
                    val index = op.index ?: Int.MAX_VALUE
                    val target = op.target
                    if (target.isNullOrBlank() || target == "@screen") {
                        nodes = insertNode(nodes, index, node)
                        applied += 1
                    } else {
                        var added = false
                        nodes = mapDesignNode(nodes, target) { parent ->
                            added = true
                            parent.copy(children = insertNode(parent.children, index, node))
                        }
                        if (added) applied += 1
                    }
                }
                "remove" -> {
                    val target = op.target ?: return@forEach
                    val (updated, removed) = removeDesignNode(nodes, target)
                    if (removed) {
                        nodes = updated
                        applied += 1
                    }
                }
                "reorder" -> {
                    val target = op.target ?: return@forEach
                    val (updated, moved) = reorderDesignNode(nodes, target, op.index ?: Int.MAX_VALUE)
                    if (moved) {
                        nodes = updated
                        applied += 1
                    }
                }
            }
        }
        if (applied == 0) return document to 0
        screen = screen.copy(nodes = nodes)
        screens[screenIndex] = screen
        return document.copy(screens = screens) to applied
    }

    private fun insertNode(nodes: List<DesignNode>, index: Int, node: DesignNode): List<DesignNode> {
        val list = nodes.toMutableList()
        list.add(index.coerceIn(0, list.size), node)
        return list
    }

    private fun removeDesignNode(nodes: List<DesignNode>, id: String): Pair<List<DesignNode>, Boolean> {
        var removed = false
        val next = nodes.mapNotNull { node ->
            when {
                node.id == id -> {
                    removed = true
                    null
                }
                node.children.isNotEmpty() -> {
                    val (children, hit) = removeDesignNode(node.children, id)
                    if (hit) {
                        removed = true
                        node.copy(children = children)
                    } else {
                        node
                    }
                }
                else -> node
            }
        }
        return next to removed
    }

    private fun reorderDesignNode(
        nodes: List<DesignNode>,
        id: String,
        index: Int,
    ): Pair<List<DesignNode>, Boolean> {
        val mutable = nodes.toMutableList()
        val at = mutable.indexOfFirst { it.id == id }
        if (at >= 0) {
            val node = mutable.removeAt(at)
            mutable.add(index.coerceIn(0, mutable.size), node)
            return mutable to true
        }
        var moved = false
        val next = mutable.map { node ->
            if (!moved && node.children.isNotEmpty()) {
                val (children, hit) = reorderDesignNode(node.children, id, index)
                if (hit) {
                    moved = true
                    node.copy(children = children)
                } else {
                    node
                }
            } else {
                node
            }
        }
        return next to moved
    }

    private fun decodeNode(payload: JsonObject): DesignNode? =
        runCatching { json.decodeFromJsonElement(DesignNode.serializer(), payload) }
            .getOrNull()
            ?.takeIf { it.id.isNotBlank() && it.type.isNotBlank() }

    private fun patchNode(node: DesignNode, patch: JsonObject): DesignNode = node.copy(
        x = patch.float("x") ?: node.x,
        y = patch.float("y") ?: node.y,
        w = patch.float("w") ?: node.w,
        h = patch.float("h") ?: node.h,
        radius = patch.float("radius") ?: node.radius,
        fill = patch.string("fill") ?: node.fill,
        color = patch.string("color") ?: node.color,
        text = patch.string("text") ?: node.text,
        fontSize = patch.float("fontSize") ?: node.fontSize,
        fontWeight = patch.float("fontWeight")?.toInt() ?: node.fontWeight,
        opacity = patch.float("opacity") ?: node.opacity,
        letterSpacing = patch.float("letterSpacing") ?: node.letterSpacing,
        stroke = patch.string("stroke") ?: node.stroke,
        lineHeight = patch.float("lineHeight") ?: node.lineHeight,
        align = patch.string("align") ?: node.align,
        blur = patch.float("blur") ?: node.blur,
        padLeft = patch.float("padLeft") ?: node.padLeft,
        value = patch.float("value") ?: node.value,
        track = patch.string("track") ?: node.track,
        on = patch.bool("on") ?: node.on,
        items = patch.strings("items") ?: node.items,
        active = patch.int("active") ?: node.active,
    )

    private fun nodeContext(node: DesignNode, depth: Int = 0): JsonObject = buildJsonObject {
        put("id", node.id)
        put("type", node.type)
        put("name", node.name)
        put("x", node.x)
        put("y", node.y)
        put("w", node.w)
        put("h", node.h)
        if (!node.text.isNullOrBlank()) put("text", node.text.take(60))
        if (depth < 2 && node.children.isNotEmpty()) {
            put("children", JsonArray(node.children.take(80).map { nodeContext(it, depth + 1) }))
        }
    }

    private fun screenContext(screen: DesignScreen): JsonObject = buildJsonObject {
        put("id", screen.id)
        put("name", screen.name)
        put("w", screen.w)
        put("h", screen.h)
        put("nodes", JsonArray(screen.nodes.take(400).map { nodeContext(it) }))
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.float(key: String): Float? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toFloatOrNull()

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()

    private fun JsonObject.bool(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()

    private fun JsonObject.strings(key: String): List<String>? {
        val array = this[key] as? JsonArray ?: return null
        return array.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    }

    // ------------------------------------------------------------ 历史版本

    fun loadRevisions() {
        val designId = _state.value.document?.id ?: return
        if (revisionsJob?.isActive == true) return
        revisionsJob = viewModelScope.launch {
            _state.value = _state.value.copy(revisionsLoading = true, error = null)
            try {
                val revisions = api().listDesignRevisions(designId)
                Log.d(TAG, "loadRevisions ok id=$designId count=${revisions.size}")
                _state.value = _state.value.copy(revisions = revisions, revisionsLoading = false)
            } catch (e: Exception) {
                Log.w(TAG, "loadRevisions failed id=$designId", e)
                _state.value = _state.value.copy(revisionsLoading = false, error = readable(e))
            }
        }
    }

    /** 恢复历史版本；有未保存改动时先挡住，避免把本地修改一起覆盖掉。 */
    fun revertTo(version: Int) {
        val current = _state.value
        val document = current.document ?: return
        if (current.dirty) {
            _state.value = current.copy(error = "有未保存的修改，先保存或撤销后再恢复历史版本")
            return
        }
        if (revertJob?.isActive == true) return
        revertJob = viewModelScope.launch {
            _state.value = _state.value.copy(restoringVersion = version, error = null, notice = null)
            try {
                val restored = api().revertDesign(document.id, version, document.version)
                Log.d(TAG, "revertTo ok id=${document.id} from=$version version=${restored.version}")
                baseline = restored
                lastEditKey = null
                _state.value = _state.value.copy(
                    restoringVersion = null,
                    document = restored,
                    screenIndex = _state.value.screenIndex.coerceIn(
                        0,
                        restored.screens.lastIndex.coerceAtLeast(0),
                    ),
                    selectedNodeId = null,
                    selectedNodeIds = emptySet(),
                    undoStack = emptyList(),
                    dirty = false,
                    notice = "已恢复到 v$version，生成了 v${restored.version}",
                )
                loadDesigns(quiet = true)
                loadRevisions()
            } catch (e: FluxApiException) {
                Log.w(TAG, "revertTo failed id=${document.id} version=$version code=${e.code}", e)
                _state.value = _state.value.copy(
                    restoringVersion = null,
                    error = if (e.code == "design_conflict") {
                        "设计稿刚在别处更新，已载入最新版本，请重新选择历史版本"
                    } else {
                        readable(e)
                    },
                )
                if (e.code == "design_conflict") reloadAfterConflict()
            } catch (e: Exception) {
                Log.w(TAG, "revertTo failed id=${document.id} version=$version", e)
                _state.value = _state.value.copy(restoringVersion = null, error = readable(e))
            }
        }
    }

    // ------------------------------------------------------------ 保存

    fun save(onSaved: (() -> Unit)? = null) {
        val current = _state.value
        val document = current.document ?: return
        if (saveJob?.isActive == true || !current.dirty) {
            // 没有改动时也允许「保存并退出」直接走人
            if (onSaved != null && saveJob?.isActive != true) onSaved()
            return
        }
        saveJob = viewModelScope.launch {
            _state.value = _state.value.copy(saving = true, error = null, notice = null)
            try {
                val saved = api().saveDesign(document)
                Log.d(TAG, "save ok design=${document.id} version=${saved.version}")
                baseline = saved
                lastEditKey = null
                _state.value = _state.value.copy(
                    saving = false,
                    document = saved,
                    dirty = false,
                    undoStack = emptyList(),
                    notice = "设计稿已保存",
                )
                loadDesigns(quiet = true)
                onSaved?.invoke()
            } catch (e: FluxApiException) {
                if (e.code == "design_conflict") {
                    Log.w(TAG, "save conflict design=${document.id} version=${document.version}")
                    reloadAfterConflict()
                } else {
                    Log.w(TAG, "save failed design=${document.id} code=${e.code}", e)
                    _state.value = _state.value.copy(saving = false, error = readable(e))
                }
            } catch (e: Exception) {
                Log.w(TAG, "save failed design=${document.id}", e)
                _state.value = _state.value.copy(saving = false, error = readable(e))
            }
        }
    }

    private suspend fun reloadAfterConflict() {
        val current = _state.value
        val designId = current.document?.id ?: return
        val latest = runCatching { api().getDesign(designId) }.getOrNull()
        _state.value = if (latest != null) {
            baseline = latest
            lastEditKey = null
            current.copy(
                saving = false,
                document = latest,
                screenIndex = current.screenIndex.coerceIn(0, latest.screens.lastIndex.coerceAtLeast(0)),
                selectedNodeId = null,
                selectedNodeIds = emptySet(),
                undoStack = emptyList(),
                dirty = false,
                notice = "设计稿已在别处更新，已载入最新版本",
            )
        } else {
            current.copy(saving = false, error = "设计稿已在别处更新，请返回列表重新打开")
        }
    }

    // ------------------------------------------------------------ 网页预览

    /**
     * 拉取设计稿渲染成的单文件 HTML，成功后交给页面层在内嵌浏览器里离线渲染。
     * 有未保存的改动时先保存，保证看到的就是当前设计稿。
     */
    fun loadWebPreview(designId: String, onReady: (DesignWebPreview) -> Unit = {}) {
        if (_state.value.dirty && _state.value.document?.id == designId) {
            save { loadWebPreview(designId, onReady) }
            return
        }
        if (previewJob?.isActive == true) return
        previewJob = viewModelScope.launch {
            _state.value = _state.value.copy(previewLoading = true, error = null, notice = null)
            try {
                val preview = api().designWebPreview(designId)
                Log.d(TAG, "loadWebPreview ok design=$designId bytes=${preview.sizeBytes}")
                _state.value = _state.value.copy(previewLoading = false, webPreview = preview)
                onReady(preview)
            } catch (e: Exception) {
                Log.w(TAG, "loadWebPreview failed design=$designId", e)
                _state.value = _state.value.copy(previewLoading = false, error = readable(e))
            }
        }
    }

    fun clearMessages() {
        _state.value = _state.value.copy(error = null, notice = null)
    }

    override fun onCleared() {
        listJob?.cancel()
        openJob?.cancel()
        saveJob?.cancel()
        askJob?.cancel()
        previewJob?.cancel()
        super.onCleared()
    }
}
