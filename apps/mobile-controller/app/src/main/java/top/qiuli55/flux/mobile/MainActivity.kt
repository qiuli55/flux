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
import top.qiuli55.flux.mobile.data.ShareIntakeRecovery
import top.qiuli55.flux.mobile.data.SharedIntakeStorage
import top.qiuli55.flux.mobile.data.SharedIntakeStore
import top.qiuli55.flux.mobile.data.materializeSharedAttachments
import top.qiuli55.flux.mobile.data.parseShareIntent
import top.qiuli55.flux.mobile.ui.v2.FluxV2Root
import top.qiuli55.flux.mobile.ui.v2.FluxV2Theme
import top.qiuli55.flux.mobile.ui.v2.V2Colors

/** 唯一 Activity：v2 浅色移动控制台。 */
class MainActivity : ComponentActivity() {
    private val shareStore: SharedIntakeStore by lazy { SharedIntakeStorage.of(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        handleAuthRedirect(intent)
        // 冷启动 / 进程重建：先把磁盘上还没消费的分享恢复出来（OAuth 回跳后进程被杀也靠它）。
        // 恢复与 intent 解析互不依赖——回来时 intent 往往只剩 flux://auth 回跳。
        val recreate = savedInstanceState != null
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { ShareIntakeRecovery.restore(shareStore) }
            // 进程重建时系统可能把旧的分享 intent 再带回来；不重新解析，避免重复落草稿。
            if (!recreate) handleShareIntent(intent)
        }
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
        handleAuthRedirect(intent)
        // singleTask：应用已在前台时再分享，只会走这里，不会重新 onCreate。
        handleShareIntent(intent)
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
        // 解析失败（畸形分享 / 非法 HTML）时既不能崩溃，也不能清掉无关 intent：
        // 只有解析出有效分享后才消费这次 Intent。
        val parsed = runCatching { parseShareIntent(target) }.getOrNull() ?: return
        target.action = null
        target.removeExtra(Intent.EXTRA_TEXT)
        target.removeExtra(Intent.EXTRA_SUBJECT)
        target.removeExtra(Intent.EXTRA_HTML_TEXT)
        target.removeExtra(Intent.EXTRA_STREAM)
        target.clipData = null

        val appContext = applicationContext
        lifecycleScope.launch {
            val prepared = withContext(Dispatchers.IO) {
                runCatching { materializeSharedAttachments(appContext, parsed) }
                    .getOrElse {
                        parsed.copy(errors = parsed.errors + "分享内容处理失败，请重试")
                    }
            }
            // 先落盘再投递：登录墙 / 选设备期间进程被杀，回来时还能恢复。
            val persisted = withContext(Dispatchers.IO) {
                ShareIntakeRecovery.persist(shareStore, prepared)
            }
            ShareIntakeBus.publish(persisted)
        }
    }

    /**
     * OAuth 回跳分流：
     * - `flux://auth?code=...`：Flux 云账号登录（复用 B 机账号服务，保持原样）；
     * - `flux://github-link?code=&state=`：给 Agent 链接 GitHub 仓库权限，交给设置页换票。
     */
    private fun handleAuthRedirect(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "flux") return
        when (data.host) {
            "auth" -> CloudAuthBus.publish(data.getQueryParameter("code").orEmpty())
            "github-link" -> GitHubLinkBus.publish(
                code = data.getQueryParameter("code").orEmpty(),
                state = data.getQueryParameter("state").orEmpty(),
            )
        }
    }
}
