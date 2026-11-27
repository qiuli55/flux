package top.qiuli55.flux.mobile.notify

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import top.qiuli55.flux.mobile.R
import top.qiuli55.flux.mobile.data.Task

/**
 * 真正把提醒发到系统通知栏。
 *
 * 失败不静默吞掉：[lastError] 由设置页展示；权限 / 渠道被关时直接给出可操作的说明，
 * 等用户去系统设置打开后，下一轮轮询会自动重新尝试（服务端不会补发，但状态还在）。
 */
object FluxNotificationCenter {
    private const val POSTED_PREFS = "flux_notification_posted"

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    fun notifyDecision(context: Context, task: Task, key: String): Boolean =
        post(context, AlertKind.DECISION, key, decisionContent(task), task.id)

    fun notifyFinished(context: Context, task: Task, key: String): Boolean =
        post(context, AlertKind.FINISHED, key, finishedContent(task), task.id)

    private fun post(
        context: Context,
        kind: AlertKind,
        key: String,
        content: NotificationContent,
        taskId: String,
    ): Boolean {
        NotificationChannels.ensure(context)
        val problem = deliverabilityProblem(context, kind)
        if (problem != null) {
            _lastError.value = problem
            return false
        }

        val id = stableId(key)
        val notification = NotificationCompat.Builder(context, channelOf(kind))
            .setSmallIcon(R.drawable.ic_flux_monochrome)
            .setContentTitle(content.title)
            .setContentText(content.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content.body))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setWhen(System.currentTimeMillis())
            .setCategory(
                if (kind == AlertKind.DECISION) {
                    NotificationCompat.CATEGORY_MESSAGE
                } else {
                    NotificationCompat.CATEGORY_STATUS
                },
            )
            .setContentIntent(
                openTaskPendingIntent(context, taskId, reasonOf(kind), id),
            )
            .build()

        return try {
            NotificationManagerCompat.from(context).notify(tagOf(kind), id, notification)
            rememberPosted(context, kind, id)
            _lastError.value = null
            true
        } catch (e: SecurityException) {
            _lastError.value = "没有通知权限，提醒未能发出。请到系统设置允许 Flux 发送通知。"
            false
        } catch (e: Exception) {
            _lastError.value = "发送提醒失败：${e.message ?: e::class.simpleName ?: "未知错误"}"
            false
        }
    }

    /** 关闭开关时取消已经发出的同类通知（不影响另一类与前台服务常驻通知）。 */
    fun cancelKind(context: Context, kind: AlertKind) {
        val manager = NotificationManagerCompat.from(context)
        val ids = postedIds(context, kind)
        ids.forEach { id -> runCatching { manager.cancel(tagOf(kind), id) } }
        context.getSharedPreferences(POSTED_PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(idsKey(kind))
            .apply()
    }

    /** 退出登录 / 关掉全部提醒时清掉所有提醒类通知（不碰前台服务通知）。 */
    fun cancelAll(context: Context) {
        AlertKind.values().forEach { cancelKind(context, it) }
    }

    private fun deliverabilityProblem(context: Context, kind: AlertKind): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return "系统通知权限未开启，提醒未能发出。请到系统设置允许 Flux 发送通知。"
        }
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            return "系统已关闭 Flux 的通知，提醒未能发出。请到系统设置重新打开。"
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = manager?.getNotificationChannel(channelOf(kind))
        if (channel?.importance == NotificationManager.IMPORTANCE_NONE) {
            val name = if (kind == AlertKind.DECISION) "「决策提醒」" else "「任务完成提醒」"
            return "$name 的系统通知渠道已被关闭，提醒未能发出。请到系统设置重新打开。"
        }
        return null
    }

    private fun channelOf(kind: AlertKind): String = when (kind) {
        AlertKind.DECISION -> NotificationChannels.DECISION
        AlertKind.FINISHED -> NotificationChannels.FINISHED
    }

    private fun reasonOf(kind: AlertKind): OpenTaskReason = when (kind) {
        AlertKind.DECISION -> OpenTaskReason.DECISION
        AlertKind.FINISHED -> OpenTaskReason.FINISHED
    }

    private fun tagOf(kind: AlertKind): String = "flux_${kind.name.lowercase()}"

    /** 用去重键算稳定 id：同一条决策 / 同一轮 run 更新同一个通知。 */
    internal fun stableId(key: String): Int = key.hashCode() and 0x7fffffff

    private fun idsKey(kind: AlertKind): String = "ids_${kind.name}"

    private fun postedIds(context: Context, kind: AlertKind): List<Int> =
        context.getSharedPreferences(POSTED_PREFS, Context.MODE_PRIVATE)
            .getStringSet(idsKey(kind), emptySet())
            .orEmpty()
            .mapNotNull { it.toIntOrNull() }

    private fun rememberPosted(context: Context, kind: AlertKind, id: Int) {
        val prefs = context.getSharedPreferences(POSTED_PREFS, Context.MODE_PRIVATE)
        val ids = prefs.getStringSet(idsKey(kind), emptySet()).orEmpty().toMutableSet()
        ids.add(id.toString())
        // 只保留最近 64 条，避免长期使用后集合无限增长。
        val bounded = ids.toList().takeLast(64).toSet()
        prefs.edit().putStringSet(idsKey(kind), bounded).apply()
    }
}
