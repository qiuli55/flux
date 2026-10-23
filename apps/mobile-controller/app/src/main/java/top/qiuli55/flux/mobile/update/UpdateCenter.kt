package top.qiuli55.flux.mobile.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import top.qiuli55.flux.mobile.BuildConfig
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate
import java.util.concurrent.TimeUnit

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
)

/**
 * 应用级更新中心。
 *
 * 生命周期跟进程走（而不是 Composable 或 Activity），所以转屏、退到后台都不会打断下载。
 * 只做三件事：查清单、下载到应用私有目录、交给系统安装器；所有异常都落日志并转成可读提示。
 */
object UpdateCenter {
    private const val TAG = "FluxUpdate"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(UpdateState())
    val state = _state.asStateFlow()

    private val checker = UpdateChecker()
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private lateinit var appContext: Context
    private var prefs: UpdatePrefs? = null
    private var downloadJob: Job? = null
    private var lastCheckAt = 0L

    fun install(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        prefs = UpdatePrefs(appContext)
    }

    // ------------------------------------------------------------ 检查

    /** 冷启动与回前台：先补完上次的安装授权，再安静地查一次版本。 */
    fun onForeground() {
        if (!::appContext.isInitialized) return
        val current = _state.value
        if (current.kind == UpdateKind.PERMISSION && canInstall()) {
            installApk()
            return
        }
        check(auto = true)
    }

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

    fun download() {
        if (!::appContext.isInitialized) return
        val manifest = _state.value.manifest ?: return
        if (downloadJob?.isActive == true) return
        val target = apkFile(manifest)
        downloadJob = scope.launch {
            _state.update {
                it.copy(
                    kind = UpdateKind.DOWNLOADING,
                    progress = 0,
                    downloaded = 0L,
                    total = manifest.sizeBytes,
                    error = null,
                    hint = "正在下载 v${manifest.versionName}",
                )
            }
            val result = runCatching { fetchToFile(manifest, target) }
            result.onSuccess {
                Log.i(TAG, "更新包下载完成: ${target.name} (${target.length()} bytes)")
                _state.update { it.copy(kind = UpdateKind.READY, progress = 100, hint = "待安装") }
                installApk()
            }.onFailure { error ->
                target.delete()
                if (error is CancellationException) {
                    Log.i(TAG, "下载已取消")
                    return@onFailure
                }
                Log.w(TAG, "更新包下载失败: ${error.message}")
                _state.update {
                    it.copy(
                        kind = UpdateKind.FAILED,
                        error = error.message ?: "下载失败",
                        hint = "下载失败",
                    )
                }
            }
        }
    }

    /** 边下边算 SHA-256，落盘后与清单比对；不一致直接删掉重来。 */
    private fun fetchToFile(manifest: UpdateManifest, target: File) {
        val request = Request.Builder().url(manifest.apkUrl).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("下载失败 HTTP ${response.code}")
            val body = response.body ?: error("下载响应为空")
            val total = body.contentLength().takeIf { it > 0 } ?: manifest.sizeBytes
            val digest = MessageDigest.getInstance("SHA-256")
            var done = 0L
            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        done += read
                        val percent = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 99) else 0
                        _state.update {
                            it.copy(kind = UpdateKind.DOWNLOADING, progress = percent, downloaded = done, total = total)
                        }
                    }
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (manifest.sha256.isNotBlank() && !actual.equals(manifest.sha256, ignoreCase = true)) {
                error("安装包校验失败，请重新下载")
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        val manifest = _state.value.manifest
        if (manifest != null) runCatching { apkFile(manifest).delete() }
        _state.update {
            it.copy(kind = if (it.manifest != null) UpdateKind.AVAILABLE else UpdateKind.HIDDEN, progress = 0)
        }
        Log.i(TAG, "用户取消下载")
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
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            appContext.startActivity(intent)
            Log.i(TAG, "已调起系统安装器: v${manifest.versionName}")
            _state.update { it.copy(kind = UpdateKind.READY, hint = "待安装") }
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

    /** 稍后：本次收起，明天（或下次启动）再提醒。 */
    fun later() {
        _state.update { it.copy(kind = UpdateKind.HIDDEN) }
    }

    /** 跳过此版本：记下版本号，之后同版本不再自动弹。 */
    fun skipVersion() {
        val manifest = _state.value.manifest
        if (manifest != null) {
            prefs?.skip(manifest.versionCode)
            Log.i(TAG, "用户跳过 v${manifest.versionName}")
        }
        _state.update { it.copy(kind = UpdateKind.HIDDEN, hint = "已跳过 v${manifest?.versionName.orEmpty()}") }
    }

    fun dismiss() {
        _state.update { it.copy(kind = UpdateKind.HIDDEN) }
    }
}
