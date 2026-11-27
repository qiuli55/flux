package top.qiuli55.flux.mobile.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * 通知渠道（Android 8+ 必须）。
 *
 * 文案全部 zh-CN，并写清每个渠道会在什么时候响：
 * - 决策到达：高重要性，会响铃 / 弹横幅；
 * - 任务完成：默认重要性，不打断当前操作；
 * - 任务关注服务：低重要性、无声音，只说明「Flux 正在后台关注任务」。
 */
object NotificationChannels {
    const val DECISION = "flux_decisions"
    const val FINISHED = "flux_task_finished"
    const val WATCH = "flux_task_watch"

    fun ensure(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(DECISION, "决策提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "任务需要你确认时提醒；点击回到对应的对话。"
                enableVibration(true)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(FINISHED, "任务完成提醒", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "任务完成、执行失败或被取消时提醒。"
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(WATCH, "任务关注服务", NotificationManager.IMPORTANCE_LOW).apply {
                description = "有任务在执行或等待决策时显示，用来保持本机提醒；不会发出声音。"
                setShowBadge(false)
                enableVibration(false)
            },
        )
    }
}

// ------------------------------------------------------------------ 权限 / 渠道状态

/**
 * 本机通知是否真的能送达。
 *
 * 权限、应用级通知开关、单个渠道的开关分开记录：设置页要能说清楚到底是哪一层
 * 被关掉了，而不是只显示一句「提醒已开启」。
 */
data class NotificationStatus(
    val permissionGranted: Boolean = true,
    val appNotificationsEnabled: Boolean = true,
    val decisionChannelEnabled: Boolean = true,
    val finishedChannelEnabled: Boolean = true,
) {
    /** 权限与总开关都可用，才可能发出任何通知。 */
    val ready: Boolean get() = permissionGranted && appNotificationsEnabled

    fun canDeliver(kind: AlertKind): Boolean = when (kind) {
        AlertKind.DECISION -> ready && decisionChannelEnabled
        AlertKind.FINISHED -> ready && finishedChannelEnabled
    }
}

/** 读取当前权限 / 渠道状态；调用前请先 [NotificationChannels.ensure]。 */
fun readNotificationStatus(context: Context): NotificationStatus {
    val permissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    val compat = NotificationManagerCompat.from(context)
    val manager = context.getSystemService(NotificationManager::class.java)
    fun channelEnabled(id: String): Boolean =
        manager?.getNotificationChannel(id)?.importance != NotificationManager.IMPORTANCE_NONE
    return NotificationStatus(
        permissionGranted = permissionGranted,
        appNotificationsEnabled = compat.areNotificationsEnabled(),
        decisionChannelEnabled = channelEnabled(NotificationChannels.DECISION),
        finishedChannelEnabled = channelEnabled(NotificationChannels.FINISHED),
    )
}

/**
 * 设置页要展示的提示：null 表示当前配置可以正常提醒。
 *
 * 只检查用户真正打开的那类提醒的渠道，避免关掉「任务完成提醒」后还提示渠道异常。
 */
fun notificationProblem(
    status: NotificationStatus,
    decisionAlerts: Boolean,
    taskAlerts: Boolean,
): String? = when {
    !status.permissionGranted ->
        "系统通知权限未开启，决策提醒和任务完成提醒都不会弹出。请到系统设置允许 Flux 发送通知。"
    !status.appNotificationsEnabled ->
        "系统已关闭 Flux 的通知，提醒不会弹出。请到系统设置重新打开。"
    decisionAlerts && !status.decisionChannelEnabled ->
        "「决策提醒」的系统通知渠道已被关闭，决策到达时不会提醒。请到系统设置重新打开。"
    taskAlerts && !status.finishedChannelEnabled ->
        "「任务完成提醒」的系统通知渠道已被关闭，任务结束时不会提醒。请到系统设置重新打开。"
    else -> null
}
