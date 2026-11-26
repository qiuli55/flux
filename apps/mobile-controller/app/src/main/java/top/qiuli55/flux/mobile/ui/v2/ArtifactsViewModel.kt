package top.qiuli55.flux.mobile.ui.v2

import android.util.Log
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import top.qiuli55.flux.mobile.data.Artifact
import top.qiuli55.flux.mobile.data.FluxApiException
import top.qiuli55.flux.mobile.ui.FluxViewModel

data class ArtifactsState(
    val artifacts: List<Artifact> = emptyList(),
    val loading: Boolean = false,
    /** 空串 = 全部；design / page / doc。 */
    val filter: String = "",
    val detail: Artifact? = null,
    val detailLoading: Boolean = false,
    val saving: Boolean = false,
    val creating: Boolean = false,
    val deletingId: String? = null,
    val error: String? = null,
    val notice: String? = null,
) {
    val visible: List<Artifact>
        get() = if (filter.isBlank()) artifacts else artifacts.filter { it.type == filter }

    val countOf: (String) -> Int
        get() = { type -> if (type.isBlank()) artifacts.size else artifacts.count { it.type == type } }
}

/**
 * Artifacts 一级页面：页面 / 文档的增删改，以及设计稿投影的只读列表。
 *
 * 设计稿的编辑仍由 [DesignStudioViewModel] 负责；这里点开设计卡只回调导航，
 * 不在本地复制设计数据。
 */
class ArtifactsViewModel : FluxViewModel() {

    private companion object {
        const val TAG = "Artifacts"

        const val PAGE_TEMPLATE = """<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>新页面</title>
  <style>
    body { margin: 0; font-family: system-ui, -apple-system, sans-serif; background: #f6f7f9; color: #1d1f23; }
    main { max-width: 720px; margin: 0 auto; padding: 48px 24px; }
    h1 { font-size: 28px; letter-spacing: -0.02em; }
    p { line-height: 1.7; color: #52565e; }
  </style>
</head>
<body>
  <main>
    <h1>新页面</h1>
    <p>在右侧源码里直接改 HTML，保存后即成为可分享的产物。</p>
  </main>
</body>
</html>
"""

        const val DOC_TEMPLATE = "# 新文档\n\n"
    }

    private val _state = MutableStateFlow(ArtifactsState())
    val state = _state.asStateFlow()

    private var listJob: Job? = null
    private var detailJob: Job? = null

    fun load(quiet: Boolean = false) {
        if (listJob?.isActive == true) return
        listJob = viewModelScope.launch {
            if (!quiet) _state.value = _state.value.copy(loading = true, error = null)
            try {
                val items = api().listArtifacts()
                Log.d(TAG, "load ok count=${items.size}")
                _state.value = _state.value.copy(artifacts = items, loading = false, error = null)
            } catch (e: Exception) {
                Log.w(TAG, "load failed", e)
                _state.value = _state.value.copy(loading = false, error = readable(e))
            }
        }
    }

    fun setFilter(filter: String) {
        if (_state.value.filter == filter) return
        _state.value = _state.value.copy(filter = filter)
    }

    fun open(artifactId: String) {
        detailJob?.cancel()
        detailJob = viewModelScope.launch {
            _state.value = _state.value.copy(detailLoading = true, error = null, notice = null)
            try {
                val artifact = api().getArtifact(artifactId)
                _state.value = _state.value.copy(detail = artifact, detailLoading = false)
            } catch (e: Exception) {
                Log.w(TAG, "open failed", e)
                _state.value = _state.value.copy(detailLoading = false, error = readable(e))
            }
        }
    }

    fun closeDetail() {
        detailJob?.cancel()
        _state.value = _state.value.copy(detail = null, detailLoading = false)
    }

    /** 新建页面 / 文档；成功后刷新列表并回调新 id，由页面决定跳去详情还是留在列表。 */
    fun create(type: String, title: String, onCreated: (String) -> Unit = {}) {
        if (_state.value.creating) return
        _state.value = _state.value.copy(creating = true, error = null, notice = null)
        viewModelScope.launch {
            try {
                val content = when (type) {
                    "doc" -> DOC_TEMPLATE
                    else -> PAGE_TEMPLATE
                }
                val artifact = api().createArtifact(type, title.ifBlank { "未命名产物" }, content)
                _state.value = _state.value.copy(creating = false, notice = "已创建")
                load(quiet = true)
                onCreated(artifact.id)
            } catch (e: Exception) {
                Log.w(TAG, "create failed", e)
                _state.value = _state.value.copy(creating = false, error = readable(e))
            }
        }
    }

    /** 保存详情页的标题与正文；乐观锁冲突时拉一次最新内容，避免覆盖别人的修改。 */
    fun save(title: String, content: String) {
        val detail = _state.value.detail ?: return
        if (_state.value.saving) return
        _state.value = _state.value.copy(saving = true, error = null, notice = null)
        viewModelScope.launch {
            try {
                val saved = api().updateArtifact(
                    detail.id,
                    title = title,
                    content = content,
                    baseVersion = detail.version,
                )
                _state.value = _state.value.copy(detail = saved, saving = false, notice = "已保存")
                load(quiet = true)
            } catch (e: FluxApiException) {
                Log.w(TAG, "save failed", e)
                _state.value = _state.value.copy(saving = false, error = readable(e))
                if (e.code == "artifact_conflict") refreshDetail(detail.id)
            } catch (e: Exception) {
                Log.w(TAG, "save failed", e)
                _state.value = _state.value.copy(saving = false, error = readable(e))
            }
        }
    }

    private fun refreshDetail(artifactId: String) {
        viewModelScope.launch {
            runCatching { api().getArtifact(artifactId) }
                .onSuccess { fresh ->
                    _state.value = _state.value.copy(detail = fresh)
                }
        }
    }

    fun delete(artifactId: String, onDeleted: () -> Unit = {}) {
        if (_state.value.deletingId != null) return
        _state.value = _state.value.copy(deletingId = artifactId, error = null, notice = null)
        viewModelScope.launch {
            try {
                api().deleteArtifact(artifactId)
                _state.value = _state.value.copy(
                    deletingId = null,
                    detail = _state.value.detail?.takeUnless { it.id == artifactId },
                    notice = "已删除",
                )
                load(quiet = true)
                onDeleted()
            } catch (e: Exception) {
                Log.w(TAG, "delete failed", e)
                _state.value = _state.value.copy(deletingId = null, error = readable(e))
            }
        }
    }

    fun clearMessages() {
        _state.value = _state.value.copy(error = null, notice = null)
    }

    override fun onCleared() {
        listJob?.cancel()
        detailJob?.cancel()
    }
}
