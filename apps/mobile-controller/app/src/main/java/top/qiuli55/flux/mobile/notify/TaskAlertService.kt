package top.qiuli55.flux.mobile.notify

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import top.qiuli55.flux.mobile.MainActivity
import top.qiuli55.flux.mobile.R
import top.qiuli55.flux.mobile.data.FluxEnv
import top.qiuli55.flux.mobile.data.NotificationPrefs
import top.qiuli55.flux.mobile.data.SettingsStore
import top.qiuli55.flux.mobile.data.Task
import top.qiuli55.flux.mobile.ui.v2.V2TaskStatus
import top.qiuli55.flux.mobile.ui.v2.isFinished
import top.qiuli55.flux.mobile.ui.v2.toV2TaskStatus

/**
 * 应用级任务提醒服务。
 *
 * 工程没有 FCM / 云推送，所以这里用前台服务 + 轮询 `GET /tasks` 的方式，
 * 在用户离开聊天回到桌面后仍能发现「等待决策」和「任务结束」。只监听任务列表
 * （复用 FluxApi.listTasks），不为每个对话各开一条 SSE，连接与耗电都可控。
 *
 * 限制写清楚：应用被系统彻底划掉后，前台服务也会停；需要重新打开 Flux 才会继续提醒。
 */
class TaskAlertService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null
    private var foregroundStarted = false
    private val lastStatuses = mutableMapOf<String, V2TaskStatus>()
    private lateinit var dedupe: AlertDeduplicator
    private val settings by lazy { SettingsStore(applicationContext) }

    override fun onCreate() {
        super.onCreate()
        NotificationChannels.ensure(this)
        dedupe = AlertDeduplicator(initial = loadDedupe())
        foregroundStarted = startAsForeground()
        if (!foregroundStarted) {
            // 无法进入前台就不要继续跑：否则 startForegroundService 的超时机制会杀掉进程。
            stopSelf()
            return
        }
        pollJob = scope.launch { pollLoop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!foregroundStarted) return START_NOT_STICKY
        // 已在 onCreate 里进入前台；后续 start 只需保证轮询在跑。
        if (pollJob?.isActive != true) pollJob = scope.launch { pollLoop() }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        pollJob?.cancel()
        scope.cancel()
        stopForegroundCompat()
        super.onDestroy()
    }

    // ------------------------------------------------------------ 前台服务

    private fun startAsForeground(): Boolean {
        val notification = ongoingNotification(0)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    ONGOING_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(ONGOING_ID, notification)
            }
            true
        } catch (e: Exception) {
            // Android 12+ 在后台不允许启动前台服务；只影响后台提醒，不打断主流程。
            Log.w(TAG, "startForeground failed: ${e.message}")
            false
        }
    }

    private fun updateOngoing(watchCount: Int) {
        runCatching {
            NotificationManagerCompat.from(this).notify(ONGOING_ID, ongoingNotification(watchCount))
        }.onFailure { Log.w(TAG, "update ongoing notification failed: ${it.message}") }
    }

    private fun ongoingNotification(watchCount: Int): Notification {
        val text = if (watchCount > 0) {
            "正在关注 $watchCount 个进行中的任务；完成或需要决策时会提醒你。"
        } else {
            "正在关注任务状态；有进展时会提醒你。"
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, NotificationChannels.WATCH)
            .setSmallIcon(R.drawable.ic_flux_monochrome)
            .setContentTitle("Flux 正在关注任务")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .build()
    }

    private fun stopForegroundCompat() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    // ------------------------------------------------------------ 轮询

    private suspend fun pollLoop() {
        while (scope.isActive) {
            val prefs = runCatching { settings.notificationPrefs.first() }
                .getOrElse { NotificationPrefs() }
            if (!prefs.anyAlertEnabled) break

            val config = runCatching { FluxEnv.configWithFreshToken() }.getOrNull()
            if (config != null && config.isComplete) {
                val tasks = runCatching { FluxEnv.api(config).listTasks() }.getOrNull()
                if (tasks != null && evaluate(tasks, prefs)) break
            }
            delay(POLL_INTERVAL_MS)
        }
        stopSelf()
    }

    /**
     * 对比上一轮状态，发出需要发的提醒。
     *
     * - 决策：`pending_decision` 或 `waiting_for_user_decision`，同一决策只发一次；
     * - 完成：观察到「非终态 → 终态」的转变才发，避免服务启动时把历史已完成任务
     *   全部补弹一遍；同一轮 run 只发一次。
     *
     * @return true 表示没有需要继续关注的任务，服务可以退出。
     */
    private suspend fun evaluate(tasks: List<Task>, prefs: NotificationPrefs): Boolean {
        val watchCount = tasks.count { !it.status.toV2TaskStatus().isFinished }
        updateOngoing(watchCount)

        val foreground = AppVisibility.foreground
        val viewedTaskId = AppVisibility.viewedTaskId
        var changed = false

        tasks.forEach { task ->
            val status = task.status.toV2TaskStatus()

            if (prefs.decisionAlerts) {
                val key = decisionKey(task)
                if (key != null &&
                    shouldSendAlert(AlertKind.DECISION, prefs, task.id, foreground, viewedTaskId) &&
                    dedupe.firstTime(key)
                ) {
                    changed = true
                    if (!FluxNotificationCenter.notifyDecision(this, task, key)) {
                        // 权限 / 渠道问题：撤销去重，等用户打开后下一轮还能再试。
                        dedupe.forget(key)
                    }
                }
            }

            if (prefs.taskAlerts) {
                val previous = lastStatuses[task.id]
                val key = finishedKey(task)
                if (key != null && previous != null && !previous.isFinished &&
                    shouldSendAlert(AlertKind.FINISHED, prefs, task.id, foreground, viewedTaskId) &&
                    dedupe.firstTime(key)
                ) {
                    changed = true
                    if (!FluxNotificationCenter.notifyFinished(this, task, key)) {
                        dedupe.forget(key)
                    }
                }
            }

            lastStatuses[task.id] = status
        }

        // 已被删除 / 不再出现在列表里的任务不保留旧状态，避免 id 复用时误判。
        lastStatuses.keys.retainAll(tasks.mapTo(HashSet()) { it.id })
        if (changed) saveDedupe()
        return watchCount == 0
    }

    // ------------------------------------------------------------ 去重持久化

    private fun loadDedupe(): List<String> =
        getSharedPreferences(DEDUPE_PREFS, MODE_PRIVATE)
            .getStringSet(DEDUPE_KEY, emptySet())
            .orEmpty()
            .toList()

    private fun saveDedupe() {
        getSharedPreferences(DEDUPE_PREFS, MODE_PRIVATE)
            .edit()
            .putStringSet(DEDUPE_KEY, dedupe.snapshot().toSet())
            .apply()
    }

    companion object {
        private const val TAG = "FluxTaskAlert"
        private const val ONGOING_ID = 0x464C5558 // "FLUX"
        private const val POLL_INTERVAL_MS = 5_000L
        private const val DEDUPE_PREFS = "flux_notification_dedupe"
        private const val DEDUPE_KEY = "keys"
        private const val ACTION_START = "top.qiuli55.flux.mobile.action.START_TASK_ALERTS"
        private const val ACTION_STOP = "top.qiuli55.flux.mobile.action.STOP_TASK_ALERTS"

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, TaskAlertService::class.java).setAction(ACTION_START),
                )
            }.onFailure { Log.w(TAG, "start alerts service failed: ${it.message}") }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, TaskAlertService::class.java)) }
        }
    }
}
