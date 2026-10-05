package top.qiuli55.flux.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import top.qiuli55.flux.mobile.ui.FluxRoot
import top.qiuli55.flux.mobile.ui.theme.FluxColors
import top.qiuli55.flux.mobile.ui.theme.FluxTheme

/** 唯一 Activity：所有页面都是 Compose 路由（见 ui/AppNav.kt）。 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FluxTheme {
                Surface(color = FluxColors.bg, modifier = Modifier.fillMaxSize()) {
                    FluxRoot()
                }
            }
        }
    }
}