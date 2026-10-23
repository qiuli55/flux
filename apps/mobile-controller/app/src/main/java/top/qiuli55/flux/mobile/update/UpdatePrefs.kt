package top.qiuli55.flux.mobile.update

import android.content.Context

/**
 * 更新提示的本地小状态：跳过到哪个版本、最近一次自动弹层是哪天。
 *
 * 用 SharedPreferences 而不是 DataStore：这两个值只在启动检查时同步读一次，
 * 不涉及令牌或隐私，没必要为此多起一个 DataStore 实例。
 */
class UpdatePrefs(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("flux_update", Context.MODE_PRIVATE)

    fun skippedCode(): Int = prefs.getInt(KEY_SKIPPED, 0)

    fun lastPromptDay(): String = prefs.getString(KEY_PROMPT_DAY, "").orEmpty()

    /** 记下「今天已经因为哪个版本弹过」，保证同一天重启也不再打扰。 */
    fun markPrompted(day: String, versionCode: Int) {
        prefs.edit().putString(KEY_PROMPT_DAY, day).putInt(KEY_PROMPT_CODE, versionCode).apply()
    }

    fun skip(versionCode: Int) {
        prefs.edit().putInt(KEY_SKIPPED, versionCode).apply()
    }

    /** 手动检查发现跳过之后又发了新版本时，允许重新提示。 */
    fun clearSkip() {
        prefs.edit().remove(KEY_SKIPPED).apply()
    }

    /** 记录正在进行的系统下载任务，进程被杀后回到应用还能接管进度。 */
    fun savePendingDownload(downloadId: Long, manifestJson: String) {
        prefs.edit()
            .putLong(KEY_DOWNLOAD_ID, downloadId)
            .putString(KEY_MANIFEST, manifestJson)
            .apply()
    }

    fun pendingDownloadId(): Long = prefs.getLong(KEY_DOWNLOAD_ID, 0L)

    fun pendingManifest(): String? = prefs.getString(KEY_MANIFEST, null)

    fun clearPendingDownload() {
        prefs.edit().remove(KEY_DOWNLOAD_ID).remove(KEY_MANIFEST).apply()
    }

    companion object {
        private const val KEY_SKIPPED = "skipped_version_code"
        private const val KEY_PROMPT_DAY = "last_prompt_day"
        private const val KEY_PROMPT_CODE = "last_prompt_code"
        private const val KEY_DOWNLOAD_ID = "pending_download_id"
        private const val KEY_MANIFEST = "pending_manifest"
    }
}
