package top.qiuli55.flux.mobile.notify

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import top.qiuli55.flux.mobile.MainActivity

/** 通知点击要回到的页面类型。 */
enum class OpenTaskReason {
    /** 决策到达：进对应的对话，那里能直接处理审批。 */
    DECISION,

    /** 任务结束：进对应的任务详情。 */
    FINISHED;

    val raw: String
        get() = when (this) {
            DECISION -> REASON_DECISION
            FINISHED -> REASON_FINISHED
        }

    companion object {
        fun fromRaw(raw: String?): OpenTaskReason =
            if (raw == REASON_FINISHED) FINISHED else DECISION
    }
}

const val EXTRA_OPEN_TASK_ID = "flux_open_task_id"
const val EXTRA_OPEN_REASON = "flux_open_reason"
const val REASON_DECISION = "decision"
const val REASON_FINISHED = "finished"

/** 一个启动 Intent 的语义分类；四类互不干扰。 */
sealed interface FluxLaunch {
    data class OpenTask(val taskId: String, val reason: OpenTaskReason) : FluxLaunch
    data class OAuth(val host: String, val code: String, val state: String) : FluxLaunch
    data object Share : FluxLaunch
    data object None : FluxLaunch
}

/**
 * 纯函数解析启动 Intent，优先级：通知点击 > OAuth 回跳 > 系统分享 > 普通启动。
 *
 * 通知点击走显式 MainActivity，没有 data / action；OAuth 是 `flux://...` 的 VIEW；
 * 分享是 SEND / SEND_MULTIPLE。三者用不同的判据，避免把分享误当成 OAuth 或反过来。
 */
fun parseFluxLaunch(intent: Intent?): FluxLaunch {
    val target = intent ?: return FluxLaunch.None
    val taskId = target.getStringExtra(EXTRA_OPEN_TASK_ID)?.trim().orEmpty()
    if (taskId.isNotEmpty()) {
        return FluxLaunch.OpenTask(
            taskId = taskId,
            reason = OpenTaskReason.fromRaw(target.getStringExtra(EXTRA_OPEN_REASON)),
        )
    }

    val data = target.data
    if (data?.scheme == "flux" && (data.host == "auth" || data.host == "github-link")) {
        return FluxLaunch.OAuth(
            host = data.host.orEmpty(),
            code = data.getQueryParameter("code").orEmpty(),
            state = data.getQueryParameter("state").orEmpty(),
        )
    }

    return when (target.action) {
        Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> FluxLaunch.Share
        else -> FluxLaunch.None
    }
}

/** 消费后清掉回跳 extra，避免旋转 / 重建时重复跳转。 */
fun clearOpenTaskExtras(intent: Intent?) {
    intent?.removeExtra(EXTRA_OPEN_TASK_ID)
    intent?.removeExtra(EXTRA_OPEN_REASON)
}

/** 通知点击使用的显式 Intent：MainActivity 是 singleTask，已有实例会走 onNewIntent。 */
fun openTaskIntent(context: Context, taskId: String, reason: OpenTaskReason): Intent =
    Intent(context, MainActivity::class.java).apply {
        putExtra(EXTRA_OPEN_TASK_ID, taskId)
        putExtra(EXTRA_OPEN_REASON, reason.raw)
        addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP,
        )
    }

/** 每条通知一个 PendingIntent（requestCode 由去重键决定），重复点击更新同一入口。 */
fun openTaskPendingIntent(
    context: Context,
    taskId: String,
    reason: OpenTaskReason,
    requestCode: Int,
): PendingIntent = PendingIntent.getActivity(
    context,
    requestCode,
    openTaskIntent(context, taskId, reason),
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
)
