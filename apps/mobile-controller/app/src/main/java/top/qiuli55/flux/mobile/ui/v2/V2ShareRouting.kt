package top.qiuli55.flux.mobile.ui.v2

import top.qiuli55.flux.mobile.data.IncomingShare

/** 系统分享该落到哪里。 */
internal enum class ShareLanding {
    /** 还在登录墙 / 选设备：先攒着，不消费。 */
    HOLD,
    /** 正在看某段已有对话：进它的输入栏。 */
    CURRENT_TASK,
    /** 已经在未建任务的新对话草稿页：不另开一层。 */
    CURRENT_DRAFT,
    /** 其它页面 / 没有当前对话：新开一段对话。 */
    NEW_CHAT,
}

internal fun resolveShareLanding(
    route: V2Route,
    bootReady: Boolean,
    hasDraftChat: Boolean,
): ShareLanding = when {
    !bootReady -> ShareLanding.HOLD
    route is V2Route.TaskChat -> ShareLanding.CURRENT_TASK
    route == V2Route.Chat -> if (hasDraftChat) ShareLanding.CURRENT_DRAFT else ShareLanding.NEW_CHAT
    else -> ShareLanding.NEW_CHAT
}

/**
 * 分享在还没进入主界面时被收下，给用户的提示语。
 *
 * 文案要跟当前 boot 阶段对得上：启动中说「启动完成」，登录墙说「登录」，
 * 选设备页说「选好设备」。已就绪时不需要提示，返回 null。
 */
internal fun shareHeldToast(boot: V2Boot): String? = when (boot) {
    V2Boot.READY -> null
    V2Boot.LOADING -> "已收下，启动完成后会放入新对话输入栏"
    V2Boot.LOGIN -> "已收下，登录后会放入新对话输入栏"
    V2Boot.DEVICES -> "已收下，选好设备后会放入新对话输入栏"
}

/** 分享队列：进入主界面前只入队，进入后每条只消费一次。 */
internal data class ShareIntakeState(
    val pending: List<IncomingShare> = emptyList(),
) {
    fun receive(share: IncomingShare): ShareIntakeState =
        copy(pending = pending + share)

    fun receive(shares: List<IncomingShare>): ShareIntakeState =
        copy(pending = pending + shares)

    /**
     * 主界面就绪时取出队首一条；未就绪或队列为空时原样返回。
     * 一次只取一条，避免同一帧里连续切换路由、把后一条落到错误页面。
     */
    fun nextIfReady(bootReady: Boolean): Pair<ShareIntakeState, IncomingShare?> =
        if (!bootReady || pending.isEmpty()) {
            this to null
        } else {
            copy(pending = pending.drop(1)) to pending.first()
        }
}
