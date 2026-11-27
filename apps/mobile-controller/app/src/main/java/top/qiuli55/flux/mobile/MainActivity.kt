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
import top.qiuli55.flux.mobile.data.CloudAuthBus
import top.qiuli55.flux.mobile.data.GitHubLinkBus
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
        handleAuthRedirect(intent)
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
