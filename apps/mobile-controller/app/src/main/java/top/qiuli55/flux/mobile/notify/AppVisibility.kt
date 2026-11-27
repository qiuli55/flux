package top.qiuli55.flux.mobile.notify

/**
 * 进程内的「用户此刻在看什么」。
 *
 * 前台服务与 Compose 在同一个进程里，用这个轻量对象判断：如果用户正盯着
 * 这条 TaskChat，就不要再弹系统通知打扰自己；退到后台或别的页面则照常发。
 */
object AppVisibility {
    @Volatile
    var foreground: Boolean = true

    /** 当前正在看的对话 id（仅 TaskChat 有意义）。 */
    @Volatile
    var viewedTaskId: String? = null
}
