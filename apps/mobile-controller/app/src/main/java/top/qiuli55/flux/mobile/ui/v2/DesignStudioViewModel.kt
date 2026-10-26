package top.qiuli55.flux.mobile.ui.v2

import android.util.Log
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import top.qiuli55.flux.mobile.data.DesignAiOp
import top.qiuli55.flux.mobile.data.DesignDocument
import top.qiuli55.flux.mobile.data.DesignNode
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
    val dirty: Boolean = false,
    val saving: Boolean = false,
    val creating: Boolean = false,
    val renamingId: String? = null,
    val deletingId: String? = null,
    val asking: Boolean = false,
    val aiReply: String? = null,
    val error: String? = null,
    val notice: String? = null,
    /** 网页预览：服务端渲染出的单文件 HTML。 */
    val webPreview: DesignWebPreview? = null,
    val previewLoading: Boolean = false,
) {
    val screen get() = document?.screens?.getOrNull(screenIndex)
    val selectedNode: DesignNode?
        get() {
            val nodeId = selectedNodeId ?: return null
            val nodes = screen?.nodes ?: return null
            return findDesignNode(nodes, nodeId)
        }
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

/**
 * 手机端设计工作室：列表、打开画布、轻编辑与 AI 改组件。
 *
 * 所有修改只落在本地 [DesignStudioState.document]，用户点保存才提交；服务端按 version
 * 做乐观锁，409 时不覆盖别人的改动，直接拉最新版本并提示。
 */
class DesignStudioViewModel : FluxViewModel() {

    private companion object {
        const val TAG = "DesignStudio"
    }

    private val _state = MutableStateFlow(DesignStudioState())
    val state = _state.asStateFlow()

    private var listJob: Job? = null
    private var openJob: Job? = null
    private var saveJob: Job? = null
    private var askJob: Job? = null
    private var previewJob: Job? = null

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
                _state.value = _state.value.copy(
                    openingId = null,
                    document = document,
                    screenIndex = 0,
                    selectedNodeId = null,
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
        _state.value = _state.value.copy(
            document = null,
            screenIndex = 0,
            selectedNodeId = null,
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
            aiReply = null,
        )
    }

    fun selectNode(nodeId: String?) {
        _state.value = _state.value.copy(selectedNodeId = nodeId, aiReply = null)
    }

    // ------------------------------------------------------------ 轻编辑

    private fun editSelected(transform: (DesignNode) -> DesignNode) {
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
        _state.value = current.copy(document = updated, dirty = true, notice = null)
    }

    fun setNodeText(text: String) = editSelected { it.copy(text = text) }

    fun setNodeFill(color: String) = editSelected { it.copy(fill = color) }

    fun setNodeTextColor(color: String) = editSelected { it.copy(color = color) }

    fun setNodeRadius(radius: Float) = editSelected { it.copy(radius = radius) }

    fun setNodeOpacity(opacity: Float) = editSelected {
        it.copy(opacity = opacity.coerceIn(0.1f, 1f))
    }

    // ------------------------------------------------------------ AI

    fun askAi(prompt: String) {
        val current = _state.value
        val document = current.document ?: return
        val screen = current.screen ?: return
        val node = current.selectedNode ?: return
        val text = prompt.trim()
        if (text.isEmpty() || askJob?.isActive == true) return
        askJob = viewModelScope.launch {
            _state.value = _state.value.copy(asking = true, error = null, aiReply = null)
            try {
                val result = api().askDesignAi(document.id, node, screen, text)
                val applied = applyOps(result.ops, node.id)
                Log.d(
                    TAG,
                    "askAi ok design=${document.id} node=${node.id} ops=${result.ops.size} applied=$applied",
                )
                _state.value = _state.value.copy(
                    asking = false,
                    dirty = _state.value.dirty || applied > 0,
                    aiReply = result.reply.ifBlank { "已按你的要求调整该组件" },
                    notice = if (applied > 0) "AI 已应用 $applied 项修改，记得保存" else null,
                )
            } catch (e: Exception) {
                Log.w(TAG, "askAi failed design=${document.id} node=${node.id}", e)
                _state.value = _state.value.copy(asking = false, error = readable(e))
            }
        }
    }

    /** 把补丁按顺序套到选中节点；返回实际应用的条数。 */
    private fun applyOps(ops: List<DesignAiOp>, nodeId: String): Int {
        var applied = 0
        ops.forEach { op ->
            val patch = op.set
            if (patch.isEmpty()) return@forEach
            editSelected { node ->
                applied += 1
                node.copy(
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
                )
            }
        }
        return applied
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.float(key: String): Float? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toFloatOrNull()

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
                _state.value = _state.value.copy(
                    saving = false,
                    document = saved,
                    dirty = false,
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
            current.copy(
                saving = false,
                document = latest,
                screenIndex = current.screenIndex.coerceIn(0, latest.screens.lastIndex.coerceAtLeast(0)),
                selectedNodeId = null,
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
