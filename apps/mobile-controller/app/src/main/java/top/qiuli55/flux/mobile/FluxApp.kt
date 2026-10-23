package top.qiuli55.flux.mobile

import android.app.Application
import top.qiuli55.flux.mobile.data.FluxEnv
import top.qiuli55.flux.mobile.update.UpdateCenter

/**
 * 应用入口。
 *
 * 把连接配置与更新中心挂到应用级单例上。网络客户端仍是惰性创建的——
 * 避免"启动即请求"在还没配置服务器时刷一堆错误。
 */
class FluxApp : Application() {
    override fun onCreate() {
        super.onCreate()
        FluxEnv.install(this)
        UpdateCenter.install(this)
    }
}