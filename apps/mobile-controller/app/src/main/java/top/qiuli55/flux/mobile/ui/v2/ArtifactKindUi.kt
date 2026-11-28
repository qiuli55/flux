package top.qiuli55.flux.mobile.ui.v2

/**
 * 「创作」里三类交付物的统一标识。
 *
 * 页面 / 设计稿 / 文档必须在对话卡片、创作列表、详情页用同一套说法：
 * - 可交互页面：单文件 HTML，点开就能点按操作；
 * - 设计稿：设计画布上的视觉稿，点开进画布；
 * - 文档：Markdown，点开阅读。
 *
 * 这里只做纯字符串映射，方便单测锁住文案，不依赖 Compose。
 */
internal data class ArtifactKindUi(
    /** 完整类型名，用在副标题 / 详情页。 */
    val label: String,
    /** 短标识，用在封面 / 卡片角标。 */
    val badge: String,
    /** 一句人话说明，回答「这东西能不能点」。 */
    val description: String,
    /** 点开之后会发生什么。 */
    val openHint: String,
)

/**
 * 对话卡片右上角的状态词：直接回答「拿到的东西能不能点 / 能不能读」，
 * 不用含糊的「已发布」。
 */
internal fun artifactCardBadge(type: String): String = when (type) {
    "doc" -> "可阅读"
    "design" -> "进画布"
    "page" -> "可交互"
    else -> "查看"
}

/** 设计稿卡片副标题：先标明这是设计稿，再放项目与描述。 */
internal fun designCardSubtitle(project: String, description: String): String =
    listOf("设计稿", project, description)
        .filter { it.isNotBlank() }
        .joinToString(" · ")

/**
 * 把「整理成文档 / 页面」拼成一条普通消息，由服务端分流到整理能力。
 *
 * 用户在输入框里已经写了的说明要保留，作为整理要求一起发给服务端；
 * 这里不调用任何端侧模型，也不在手机本地判断能不能整理。
 */
internal fun organizeAttachmentPrompt(target: String, userText: String): String {
    val command = if (target == "page") "整理成页面" else "整理成文档"
    val typed = userText.trim()
    return if (typed.isBlank()) command else "$typed\n\n$command"
}

internal fun artifactKindUi(type: String): ArtifactKindUi = when (type) {
    "design" -> ArtifactKindUi(
        label = "设计稿",
        badge = "设计稿",
        description = "设计画布上的视觉稿，可改布局、样式与组件",
        openHint = "点开进画布",
    )
    "doc" -> ArtifactKindUi(
        label = "文档",
        badge = "文档",
        description = "Markdown 文档，可阅读与改源码",
        openHint = "点开阅读",
    )
    "page" -> ArtifactKindUi(
        label = "可交互页面",
        badge = "可交互",
        description = "单文件 HTML 页面，点开就能点按操作",
        openHint = "点开可交互预览",
    )
    // 后端将来新增类型时不要崩，也不要误标成「页面」。
    else -> ArtifactKindUi(
        label = "创作",
        badge = "创作",
        description = "对话里产出的内容",
        openHint = "点开查看",
    )
}
