package top.qiuli55.flux.mobile.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import top.qiuli55.flux.mobile.BuildConfig
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate

/** 更新弹层当前所处的阶段。 */
enum class UpdateKind { HIDDEN, AVAILABLE, DOWNLOADING, PERMISSION, READY, FAILED }

data class UpdateState(
    val kind: UpdateKind = UpdateKind.HIDDEN,
    val manifest: UpdateManifest? = null,
    /** 低于最低支持版本：弹层里不给「稍后」。 */
    val forced: Boolean = false,
    /** true = 启动/回前台自动弹的；false = 用户在设置里手动查的。 */
    val auto: Boolean = false,
    val progress: Int = 0,
    val downloaded: Long = 0L,
    val total: Long = 0L,
    val error: String? = null,
    val checking: Boolean = false,
    /** 设置页「检查更新」行的状态文案。 */
    val hint: String = "",
    /**
     * 下载完成后是否直接调起安装器。
     * 用户中途点了「稍后」说明不想被打断，此时只把弹层停在待安装。
     */
    val installOnReady: Boolean = true,
    /** 已经请求过一次安装，避免用户取消安装后每次回前台又被弹起来。 */
    val installRequested: Boolean = false,
)

private data class DownloadInfo(val status: Int, val done: Long, val total: Long)

/**
 * 应用级更新中心。
 *
 * 下载交给系统 DownloadManager：任务在系统进程里跑，退到后台、划掉应用都不会中断，
 * 通知栏会显示进度；应用回到前台时按保存的 downloadId 接管进度并继续校验与安装。
 * 只做四件事：查清单、提交/接管下载、SHA-256 校验、交给系统安装器；异常都落日志并转成可读提示。
 */
object UpdateCenter {
    private const val TAG = "FluxUpdate"
    private const val APK_MIME = "application/vnd.android.package-archive"

    /** 下载任务超过该时长仍未结束即判失败，避免轮询协程无限挂着。 */
    private const val MAX_DOWNLOAD_MS = 15 * 60 * 1000L
    private const val POLL_INTERVAL_MS = 700L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(UpdateState())
    val state = _state.asStateFlow()

    private val checker = UpdateChecker()

    private lateinit var appContext: Context
    private var prefs: UpdatePrefs? = null
    private var pollJob: Job? = null
    private var downloadId = 0L
    private var foreground = false
    private var lastCheckAt = 0L

    private val manager: DownloadManager
        get() = appContext.getSystemService(DownloadManager::class.java)

    fun install(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        prefs = UpdatePrefs(appContext)
    }

    // ------------------------------------------------------------ 前后台

    /** 冷启动与回前台：先补完上次的安装授权或下载，再安静地查一次版本。 */
    fun onForeground() {
        if (!::appContext.isInitialized) return
        foreground = true
        val current = _state.value
        when (current.kind) {
            UpdateKind.READY -> {
                if (current.installOnReady && !current.installRequested) installApk()
                return
            }
            UpdateKind.PERMISSION -> {
                if (canInstall()) installApk()
                return
            }
            UpdateKind.DOWNLOADING -> return
            else -> Unit
        }
        // 下载仍在本次进程里跟进时（用户只是点了「稍后」收起弹层），不再重复接管或检查。
        if (pollJob?.isActive == true) return
        if (resumePending()) return
        check(auto = true)
    }

    /** 退到后台只记状态：下载由系统继续，完成时不从后台拉起安装器。 */
    fun onBackground() {
        foreground = false
    }

    // ------------------------------------------------------------ 检查

    /** 手动检查：不管跳过与每日限制，有新版本就打开弹层。 */
    fun checkManually() {
        check(auto = false)
    }

    private fun check(auto: Boolean) {
        if (!::appContext.isInitialized) return
        val current = _state.value
        if (current.checking) return
        if (auto && current.kind != UpdateKind.HIDDEN) return
        // 冷启动的 boot 回调与 ON_RESUME 几乎同时到达，30 秒内只查一次。
        val now = System.currentTimeMillis()
        if (auto && now - lastCheckAt < 30_000L) return
        lastCheckAt = now
        scope.launch {
            _state.update { it.copy(checking = true) }
            checker.fetch()
                .onSuccess { manifest -> handleManifest(manifest, auto) }
                .onFailure { error ->
                    Log.w(TAG, "检查更新失败: ${error.message}")
                    _state.update {
                        it.copy(checking = false, hint = if (auto) it.hint else "检查失败，请稍后再试")
                    }
                }
        }
    }

    private fun handleManifest(manifest: UpdateManifest, auto: Boolean) {
        val currentCode = BuildConfig.VERSION_CODE
        if (!manifest.isNewerThan(currentCode)) {
            Log.d(TAG, "已是最新版本 v${BuildConfig.VERSION_NAME}")
            _state.value = UpdateState(checking = false, hint = if (auto) "" else "已是最新版本")
            return
        }
        val today = LocalDate.now().toString()
        val store = prefs
        val due = !auto || shouldAutoPrompt(
            manifest = manifest,
            currentCode = currentCode,
            skippedCode = store?.skippedCode() ?: 0,
            lastPromptDay = store?.lastPromptDay().orEmpty(),
            today = today,
        )
        val hint = "发现 v${manifest.versionName}"
        if (!due) {
            Log.d(TAG, "自动提示已跳过（跳过版本或今日已提示过）")
            _state.value = UpdateState(checking = false, hint = hint)
            return
        }
        if (auto) store?.markPrompted(today, manifest.versionCode)
        Log.i(TAG, "发现新版本 v${manifest.versionName} (vc${manifest.versionCode})，自动=$auto")
        _state.value = UpdateState(
            kind = UpdateKind.AVAILABLE,
            manifest = manifest,
            forced = manifest.isForcedFor(currentCode),
            auto = auto,
            checking = false,
            hint = hint,
        )
    }

    // ------------------------------------------------------------ 下载

    /** 提交系统下载任务；任务本身在系统进程里执行，应用退到后台不影响。 */
    fun download() {
        if (!::appContext.isInitialized) return
        val manifest = _state.value.manifest ?: return
        if (pollJob?.isActive == true) return
        val target = apkFile(manifest)
        runCatching { target.delete() }
        val request = DownloadManager.Request(Uri.parse(manifest.apkUrl))
            .setTitle("Flux Mobile ${manifest.versionName}")
            .setDescription("Flux 更新包")
            .setMimeType(APK_MIME)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(appContext, Environment.DIRECTORY_DOWNLOADS, target.name)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
        val id = runCatching { manager.enqueue(request) }.getOrElse { error ->
            Log.w(TAG, "提交下载任务失败: ${error.message}")
            _state.update {
                it.copy(kind = UpdateKind.FAILED, error = "无法开始下载", hint = "下载失败")
            }
            return
        }
        downloadId = id
        prefs?.savePendingDownload(id, UpdateJson.encodeToString(UpdateManifest.serializer(), manifest))
        Log.i(TAG, "下载任务已提交: id=$id ${manifest.apkUrl}")
        _state.update {
            it.copy(
                kind = UpdateKind.DOWNLOADING,
                progress = 0,
                downloaded = 0L,
                total = manifest.sizeBytes,
                error = null,
                hint = "正在后台下载 v${manifest.versionName}",
                installOnReady = true,
                installRequested = false,
            )
        }
        startPolling(manifest)
    }

    /**
     * 进程被杀后重新进入应用时接管上次的系统下载。
     * 返回 true 表示已有下载在跟进，本次不再检查新版本。
     */
    private fun resumePending(): Boolean {
        val store = prefs ?: return false
        val id = store.pendingDownloadId()
        if (id <= 0) return false
        val manifest = store.pendingManifest()?.let { raw ->
            runCatching { UpdateJson.decodeFromString<UpdateManifest>(raw) }.getOrNull()
        }
        if (manifest == null) {
            Log.w(TAG, "上次下载的清单无法解析，放弃接管")
            store.clearPendingDownload()
            return false
        }
        downloadId = id
        Log.i(TAG, "接管上次未完成的下载: id=$id")
        _state.update {
            it.copy(
                kind = UpdateKind.DOWNLOADING,
                manifest = manifest,
                forced = manifest.isForcedFor(BuildConfig.VERSION_CODE),
                progress = 0,
                downloaded = 0L,
                total = manifest.sizeBytes,
                error = null,
                hint = "正在后台下载 v${manifest.versionName}",
            )
        }
        startPolling(manifest)
        return true
    }

    /** 轮询系统下载进度直到成功或失败；超过 MAX_DOWNLOAD_MS 主动取消。 */
    private fun startPolling(manifest: UpdateManifest) {
        pollJob?.cancel()
        pollJob = scope.launch {
            val deadline = System.currentTimeMillis() + MAX_DOWNLOAD_MS
            while (isActive) {
                val info = query(downloadId)
                if (info == null) {
                    onDownloadFailed("下载任务丢失，请重试")
                    return@launch
                }
                when (info.status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        finishDownload(manifest)
                        return@launch
                    }
                    DownloadManager.STATUS_FAILED -> {
                        onDownloadFailed(failureReason(downloadId))
                        return@launch
                    }
                    else -> {
                        val total = info.total.takeIf { it > 0 } ?: manifest.sizeBytes
                        _state.update {
                            it.copy(
                                kind = UpdateKind.DOWNLOADING,
                                progress = downloadPercent(info.done, total),
                                downloaded = info.done.coerceAtLeast(0L),
                                total = total,
                            )
                        }
                    }
                }
                if (System.currentTimeMillis() > deadline) {
                    cancelDownload()
                    onDownloadFailed("下载超时，请重试")
                    return@launch
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun query(id: Long): DownloadInfo? {
        val cursor = runCatching {
            manager.query(DownloadManager.Query().setFilterById(id))
        }.getOrNull() ?: return null
        cursor.use {
            if (!it.moveToFirst()) return null
            return DownloadInfo(
                status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                done = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                total = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
            )
        }
    }

    private fun failureReason(id: Long): String {
        val cursor = runCatching {
            manager.query(DownloadManager.Query().setFilterById(id))
        }.getOrNull() ?: return "下载失败，请重试"
        cursor.use {
            if (!it.moveToFirst()) return "下载任务已丢失"
            return when (it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))) {
                DownloadManager.ERROR_INSUFFICIENT_SPACE -> "手机存储空间不足"
                DownloadManager.ERROR_FILE_ERROR -> "写入安装包失败"
                DownloadManager.ERROR_HTTP_DATA_ERROR,
                DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "下载服务器返回异常"
                DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "下载地址跳转异常"
                DownloadManager.ERROR_CANNOT_RESUME -> "网络中断，请重试"
                else -> "下载失败，请重试"
            }
        }
    }

    private fun finishDownload(manifest: UpdateManifest) {
        val file = apkFile(manifest)
        if (!file.exists() || !matchesSha(file, manifest.sha256)) {
            Log.w(TAG, "下载完成但校验失败: ${file.absolutePath}")
            runCatching { file.delete() }
            onDownloadFailed("安装包校验失败，请重新下载")
            return
        }
        prefs?.clearPendingDownload()
        downloadId = 0L
        Log.i(TAG, "后台下载完成并校验通过: ${file.name} (${file.length()} bytes)")
        _state.update { it.copy(kind = UpdateKind.READY, progress = 100, downloaded = file.length(), hint = "待安装") }
        if (foreground && _state.value.installOnReady) {
            installApk()
        } else {
            Log.i(TAG, "应用不在前台，回到应用后再调起安装")
        }
    }

    private fun onDownloadFailed(message: String) {
        Log.w(TAG, "更新下载失败: $message")
        prefs?.clearPendingDownload()
        downloadId = 0L
        _state.update {
            it.copy(kind = UpdateKind.FAILED, error = message, hint = "下载失败")
        }
    }

    /** 取消并清掉系统下载任务与半截安装包。 */
    fun cancelDownload() {
        val id = downloadId.takeIf { it > 0 } ?: prefs?.pendingDownloadId() ?: 0L
        if (id > 0) runCatching { manager.remove(id) }
        pollJob?.cancel()
        pollJob = null
        downloadId = 0L
        prefs?.clearPendingDownload()
        _state.value.manifest?.let { runCatching { apkFile(it).delete() } }
        _state.update {
            it.copy(kind = if (it.manifest != null) UpdateKind.AVAILABLE else UpdateKind.HIDDEN, progress = 0, downloaded = 0L)
        }
        Log.i(TAG, "用户取消下载")
    }

    private fun matchesSha(file: File, expected: String): Boolean {
        if (expected.isBlank()) return true
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
                .equals(expected, ignoreCase = true)
        }.getOrElse { error ->
            Log.w(TAG, "计算安装包摘要失败: ${error.message}")
            false
        }
    }

    // ------------------------------------------------------------ 安装

    fun installApk() {
        if (!::appContext.isInitialized) return
        val manifest = _state.value.manifest ?: return
        val apk = apkFile(manifest)
        if (!apk.exists()) {
            _state.update { it.copy(kind = UpdateKind.FAILED, error = "安装包不存在，请重新下载") }
            return
        }
        if (!canInstall()) {
            Log.i(TAG, "缺少未知来源安装授权，引导用户去设置")
            _state.update { it.copy(kind = UpdateKind.PERMISSION, hint = "等待安装授权") }
            return
        }
        runCatching {
            val uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", apk)
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, APK_MIME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            appContext.startActivity(intent)
            Log.i(TAG, "已调起系统安装器: v${manifest.versionName}")
            _state.update { it.copy(kind = UpdateKind.READY, hint = "待安装", installRequested = true) }
        }.onFailure { error ->
            Log.w(TAG, "调起安装器失败: ${error.message}")
            _state.update {
                it.copy(kind = UpdateKind.FAILED, error = "无法调起安装器，请检查系统权限", hint = "安装失败")
            }
        }
    }

    fun openInstallPermission() {
        if (!::appContext.isInitialized) return
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(Uri.parse("package:${appContext.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { appContext.startActivity(intent) }
            .onFailure { Log.w(TAG, "打开安装授权页失败: ${it.message}") }
    }

    private fun canInstall(): Boolean =
        appContext.packageManager.canRequestPackageInstalls()

    private fun apkFile(manifest: UpdateManifest): File {
        val dir = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: appContext.filesDir
        return File(dir, "flux-mobile-${manifest.versionName}-vc${manifest.versionCode}.apk")
    }

    // ------------------------------------------------------------ 用户选择

    /** 稍后：收起弹层；若正在下载则继续在后台跑，完成后只停在待安装。 */
    fun later() {
        _state.update {
            it.copy(kind = UpdateKind.HIDDEN, installOnReady = it.kind != UpdateKind.DOWNLOADING && it.installOnReady)
        }
    }

    /** 跳过此版本：记下版本号，同时停掉正在进行的下载。 */
    fun skipVersion() {
        val manifest = _state.value.manifest
        if (manifest != null) {
            prefs?.skip(manifest.versionCode)
            Log.i(TAG, "用户跳过 v${manifest.versionName}")
        }
        if (_state.value.kind == UpdateKind.DOWNLOADING) cancelDownload()
        _state.update { it.copy(kind = UpdateKind.HIDDEN, hint = "已跳过 v${manifest?.versionName.orEmpty()}") }
    }

    fun dismiss() {
        _state.update {
            it.copy(kind = UpdateKind.HIDDEN, installOnReady = it.kind != UpdateKind.DOWNLOADING && it.installOnReady)
        }
    }
}
