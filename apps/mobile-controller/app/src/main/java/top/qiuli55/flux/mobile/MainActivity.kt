package top.qiuli55.flux.mobile

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.qiuli55.flux.mobile.data.CloudAuthBus
import top.qiuli55.flux.mobile.data.GitHubLinkBus
import top.qiuli55.flux.mobile.data.ShareIntakeBus
import top.qiuli55.flux.mobile.data.materializeSharedAttachments
import top.qiuli55.flux.mobile.data.parseShareIntent
import top.qiuli55.flux.mobile.notify.FluxLaunch
import top.qiuli55.flux.mobile.notify.OpenTaskBus
import top.qiuli55.flux.mobile.notify.clearOpenTaskExtras
import top.qiuli55.flux.mobile.notify.parseFluxLaunch
import top.qiuli55.flux.mobile.ui.v2.FluxV2Root
import top.qiuli55.flux.mobile.ui.v2.FluxV2Theme
import top.qiuli55.flux.mobile.ui.v2.V2Colors

/** 唯一 Activity：v2 浅色移动控制台。 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        // 进程重建时系统可能把旧的分享 / 通知 intent 再带回来；只在首次创建时消费，
        // 避免重复落草稿或重复跳转。OAuth 回跳仍然每次都处理（凭据不能丢）。
        handleIncomingIntent(intent, consumeColdStartPayload = savedInstanceState == null)
        setContent {
            FluxV2Theme {
                Box(Modifier.fillMaxSize().background(V2Colors.bg)) {
                    FluxV2Root()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // singleTask：应用已在前台时再分享 / 点通知，只会走这里，不会重新 onCreate。
        handleIncomingIntent(intent, consumeColdStartPayload = true)
    }

    /**
     * 启动 Intent 分流：通知点击 / OAuth 回跳 / 系统分享互不干扰。
     *
     * 通知点击消费后立刻清掉 extra，旋转或重建不会重复跳转；分享的消费与清理由
     * [handleShareIntent] 完成。
     */
    private fun handleIncomingIntent(intent: Intent?, consumeColdStartPayload: Boolean) {
        when (val launch = parseFluxLaunch(intent)) {
            is FluxLaunch.OpenTask -> {
                if (!consumeColdStartPayload) return
                clearOpenTaskExtras(intent)
                OpenTaskBus.publish(launch.taskId, launch.reason)
            }
            is FluxLaunch.OAuth -> when (launch.host) {
                // flux://auth?code=...：Flux 云账号登录（复用 B 机账号服务，保持原样）
                "auth" -> CloudAuthBus.publish(launch.code)
                // flux://github-link?code=&state=：给 Agent 链接 GitHub 仓库权限
                "github-link" -> GitHubLinkBus.publish(code = launch.code, state = launch.state)
            }
            FluxLaunch.Share -> if (consumeColdStartPayload) handleShareIntent(intent)
            FluxLaunch.None -> Unit
        }
    }

    /**
     * 系统分享（SEND / SEND_MULTIPLE）落地。
     *
     * 这里只做解析与本地固化：content Uri 的读权限很短，先复制到 cache，
     * 再投递给根容器；登录墙 / 选设备期间由根容器暂存，进入主界面后只应用一次。
     * 处理完立刻清掉 intent 的分享动作，避免旋转 / 回前台重复落草稿。
     */
    private fun handleShareIntent(intent: Intent?) {
        val target = intent ?: return
        val parsed = parseShareIntent(target) ?: return
        // 消费掉这次分享：同一份 Intent 不再参与后续重建。
        target.action = null
        target.removeExtra(Intent.EXTRA_TEXT)
        target.removeExtra(Intent.EXTRA_SUBJECT)
        target.removeExtra(Intent.EXTRA_HTML_TEXT)
        target.removeExtra(Intent.EXTRA_STREAM)
        target.clipData = null

        val appContext = applicationContext
        lifecycleScope.launch {
            val prepared = withContext(Dispatchers.IO) {
                materializeSharedAttachments(appContext, parsed)
            }
            ShareIntakeBus.publish(prepared)
        }
    }
}
