package top.qiuli55.flux.mobile.ui.v2

import android.util.Log
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import top.qiuli55.flux.mobile.data.Artifact
import top.qiuli55.flux.mobile.data.ArtifactAsset
import top.qiuli55.flux.mobile.data.ArtifactVersion
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
    /** 版本历史；详情打开后才按需拉取。 */
    val versions: List<ArtifactVersion> = emptyList(),
    val versionsLoading: Boolean = false,
    /** 正在预览的历史版本（带正文）。 */
    val versionPreview: ArtifactVersion? = null,
    val versionPreviewLoading: Int? = null,
    val restoringVersion: Int? = null,
    /** 产物资产：Markdown 图片与网页相对路径都靠它回源。 */
    val assets: List<ArtifactAsset> = emptyList(),
    val assetsLoading: Boolean = false,
    val pinningId: String? = null,
    /** 保存时遇到乐观锁冲突：草稿保留，界面提示重试。 */
    val saveConflict: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
) {
    /** 钉选的排在前面；其余保持接口返回的更新时间倒序。 */
    val visible: List<Artifact>
        get() {
            val filtered = if (filter.isBlank()) artifacts else artifacts.filter { it.type == filter }
            return filtered.sortedByDescending { it.pinned }
        }

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
    <p>在右侧源码里直接改 HTML，保存后即成为可分享的创作。</p>
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
    private var versionsJob: Job? = null
    private var versionJob: Job? = null
    private var assetsJob: Job? = null
    private var pinJob: Job? = null

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
            _state.value = _state.value.copy(
                detailLoading = true,
                error = null,
                notice = null,
                saveConflict = false,
                versions = emptyList(),
                versionPreview = null,
                assets = emptyList(),
            )
            try {
                val artifact = api().getArtifact(artifactId)
                _state.value = _state.value.copy(detail = artifact, detailLoading = false)
                // 资产与网页预览同级：打开详情就顺手拉一次，失败不打断正文。
                loadAssets(artifactId, quiet = true)
            } catch (e: Exception) {
                Log.w(TAG, "open failed", e)
                _state.value = _state.value.copy(detailLoading = false, error = readable(e))
            }
        }
    }

    fun closeDetail() {
        detailJob?.cancel()
        versionsJob?.cancel()
        versionJob?.cancel()
        assetsJob?.cancel()
        _state.value = _state.value.copy(
            detail = null,
            detailLoading = false,
            versions = emptyList(),
            versionsLoading = false,
            versionPreview = null,
            versionPreviewLoading = null,
            restoringVersion = null,
            assets = emptyList(),
            assetsLoading = false,
            saveConflict = false,
        )
    }

    fun loadAssets(artifactId: String, quiet: Boolean = false) {
        if (assetsJob?.isActive == true) return
        assetsJob = viewModelScope.launch {
            if (!quiet) _state.value = _state.value.copy(assetsLoading = true)
            try {
                val list = api().listArtifactAssets(artifactId)
                _state.value = _state.value.copy(assets = list.assets, assetsLoading = false)
            } catch (e: Exception) {
                Log.w(TAG, "loadAssets failed id=$artifactId", e)
                _state.value = _state.value.copy(assetsLoading = false)
                if (!quiet) _state.value = _state.value.copy(error = readable(e))
            }
        }
    }

    /** 供预览层读取资产字节；失败返回 null，由界面显示图片缺失占位。 */
    suspend fun downloadAsset(assetId: String): ByteArray? {
        val artifactId = _state.value.detail?.id ?: return null
        return runCatching { api().downloadArtifactAsset(artifactId, assetId) }.getOrNull()
    }

    fun loadVersions() {
        val artifactId = _state.value.detail?.id ?: return
        if (versionsJob?.isActive == true) return
        versionsJob = viewModelScope.launch {
            _state.value = _state.value.copy(versionsLoading = true, error = null)
            try {
                val versions = api().listArtifactVersions(artifactId)
                _state.value = _state.value.copy(versions = versions, versionsLoading = false)
            } catch (e: Exception) {
                Log.w(TAG, "loadVersions failed id=$artifactId", e)
                _state.value = _state.value.copy(versionsLoading = false, error = readable(e))
            }
        }
    }

    fun previewVersion(version: Int) {
        val artifactId = _state.value.detail?.id ?: return
        if (versionJob?.isActive == true) return
        versionJob = viewModelScope.launch {
            _state.value = _state.value.copy(versionPreviewLoading = version, error = null)
            try {
                val item = api().getArtifactVersion(artifactId, version)
                _state.value = _state.value.copy(
                    versionPreview = item,
                    versionPreviewLoading = null,
                )
            } catch (e: Exception) {
                Log.w(TAG, "previewVersion failed id=$artifactId v=$version", e)
                _state.value = _state.value.copy(
                    versionPreviewLoading = null,
                    error = readable(e),
                )
            }
        }
    }

    fun closeVersionPreview() {
        versionJob?.cancel()
        _state.value = _state.value.copy(versionPreview = null, versionPreviewLoading = null)
    }

    /**
     * 用历史版本覆盖保存：带上当前版本做乐观锁，生成一个新版本，不偷偷 force。
     * 冲突时保留用户当前草稿并拉最新版本，让用户确认后再试。
     */
    fun restoreVersion(item: ArtifactVersion) {
        val detail = _state.value.detail ?: return
        if (_state.value.restoringVersion != null) return
        _state.value = _state.value.copy(
            restoringVersion = item.version,
            error = null,
            notice = null,
        )
        viewModelScope.launch {
            try {
                val saved = api().updateArtifact(
                    detail.id,
                    title = item.title,
                    content = item.content.orEmpty(),
                    baseVersion = detail.version,
                )
                _state.value = _state.value.copy(
                    restoringVersion = null,
                    detail = saved,
                    versionPreview = null,
                    notice = "已用 v${item.version} 覆盖保存，生成 v${saved.version}",
                )
                load(quiet = true)
                loadVersions()
            } catch (e: FluxApiException) {
                Log.w(TAG, "restoreVersion failed id=${detail.id} v=${item.version}", e)
                _state.value = _state.value.copy(
                    restoringVersion = null,
                    error = if (e.code == "artifact_conflict") {
                        "创作已在别处更新，已载入最新版本；请确认后再恢复"
                    } else {
                        readable(e)
                    },
                    saveConflict = e.code == "artifact_conflict",
                )
                if (e.code == "artifact_conflict") refreshDetail(detail.id)
            } catch (e: Exception) {
                Log.w(TAG, "restoreVersion failed id=${detail.id} v=${item.version}", e)
                _state.value = _state.value.copy(
                    restoringVersion = null,
                    error = readable(e),
                )
            }
        }
    }

    /** 钉选 / 取消钉选；只改变自己的排序，不动可见性。 */
    fun togglePin(artifact: Artifact) {
        if (pinJob?.isActive == true) return
        pinJob = viewModelScope.launch {
            _state.value = _state.value.copy(pinningId = artifact.id, error = null, notice = null)
            try {
                if (artifact.pinned) api().unpinArtifact(artifact.id) else api().pinArtifact(artifact.id)
                val pinned = !artifact.pinned
                _state.value = _state.value.copy(
                    pinningId = null,
                    artifacts = _state.value.artifacts.map {
                        if (it.id == artifact.id) it.copy(pinned = pinned) else it
                    },
                    detail = _state.value.detail?.let {
                        if (it.id == artifact.id) it.copy(pinned = pinned) else it
                    },
                    notice = if (pinned) "已钉选「${artifact.title}」" else "已取消钉选",
                )
                load(quiet = true)
            } catch (e: Exception) {
                Log.w(TAG, "togglePin failed id=${artifact.id}", e)
                _state.value = _state.value.copy(pinningId = null, error = readable(e))
            }
        }
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
                val artifact = api().createArtifact(type, title.ifBlank { "未命名创作" }, content)
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
                _state.value = _state.value.copy(
                    detail = saved,
                    saving = false,
                    notice = "已保存 · v${saved.version}",
                    saveConflict = false,
                )
                load(quiet = true)
            } catch (e: FluxApiException) {
                Log.w(TAG, "save failed", e)
                _state.value = _state.value.copy(
                    saving = false,
                    error = if (e.code == "artifact_conflict") {
                        "创作已在别处更新。你的草稿还在，已载入最新版本，确认后可重试保存"
                    } else {
                        readable(e)
                    },
                    saveConflict = e.code == "artifact_conflict",
                )
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
                    versions = emptyList(),
                    versionPreview = null,
                    assets = emptyList(),
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
        versionsJob?.cancel()
        versionJob?.cancel()
        assetsJob?.cancel()
        pinJob?.cancel()
    }
}
