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

    /** GitHub OAuth 完成后浏览器回跳 flux://auth?code=...，把授权码交给登录模块。 */
    private fun handleAuthRedirect(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "flux" && data.host == "auth") {
            CloudAuthBus.publish(data.getQueryParameter("code").orEmpty())
        }
    }
}
