package top.qiuli55.flux.mobile.data

import android.content.Context
import top.qiuli55.flux.mobile.ui.v2.PasteTextMode

/**
 * 「粘贴长文本」偏好：只有两项，默认「始终作为文件」。
 *
 * 用 SharedPreferences 同步读写：输入框粘贴是低频、即时的判断，不需要跨进程，
 * 也不为此新做一套账户设置服务。键名与桌面 / Web 的语义保持一致。
 */
object PastePrefs {
    private const val FILE = "flux_paste"
    private const val KEY_MODE = "mode"

    fun mode(context: Context): PasteTextMode = PasteTextMode.fromStorage(
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_MODE, null),
    )

    fun setMode(context: Context, mode: PasteTextMode) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MODE, mode.storageValue)
            .apply()
    }
}
