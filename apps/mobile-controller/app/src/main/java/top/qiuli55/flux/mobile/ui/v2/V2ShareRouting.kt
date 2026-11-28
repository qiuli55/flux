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
    /** 其它页面但内存里有未发送草稿：先问用户并入还是新开。 */
    ASK_DRAFT_CONFLICT,
    /** 其它页面 / 没有当前对话：新开一段对话。 */
    NEW_CHAT,
}

internal fun resolveShareLanding(
    route: V2Route,
    bootReady: Boolean,
    hasDraftChat: Boolean,
    /** 其它页面（首页等）内存里是否有尚未发送的新对话内容。 */
    hasUnsentDraft: Boolean = false,
): ShareLanding = when {
    !bootReady -> ShareLanding.HOLD
    route is V2Route.TaskChat -> ShareLanding.CURRENT_TASK
    route == V2Route.Chat -> if (hasDraftChat) ShareLanding.CURRENT_DRAFT else ShareLanding.NEW_CHAT
    hasUnsentDraft -> ShareLanding.ASK_DRAFT_CONFLICT
    else -> ShareLanding.NEW_CHAT
}

/**
 * 内存里是否有「未发送的新对话草稿」。
 *
 * 只看页面实际持有的状态：对话对象为空，或只有系统欢迎语且没有附件，都算干净的草稿页，
 * 分享可以直接新开一段对话，不需要打断用户。
 */
internal fun hasUnsentDraft(
    chat: V2ChatState?,
    messages: List<V2Message>,
    attachmentCount: Int,
): Boolean {
    if (chat == null) return false
    if (attachmentCount > 0) return true
    return messages.any { it.role != V2MessageRole.SYSTEM }
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
