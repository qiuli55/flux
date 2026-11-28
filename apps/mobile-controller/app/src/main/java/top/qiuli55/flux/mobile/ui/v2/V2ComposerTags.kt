package top.qiuli55.flux.mobile.ui.v2

/**
 * 输入区（composer）的测试标记与日志 tag。
 *
 * 加这些标记是为了让 Compose UI 测试能定位「+ / Agent 胶囊 / 上下文环 /
 * 模型立方体 / 盾牌 / 发送」这些入口，验证窄屏 + 大字号时发送按钮仍在根容器内、
 * 尺寸 >= 48dp 且可点击，环形与立方体也完整可见。
 * 只加 testTag，不改变任何视觉或交互语义。
 */
internal object V2ComposerTags {
    const val ROOT = "v2_composer_root"
    const val INPUT = "v2_composer_input"
    const val TOOLBAR = "v2_composer_toolbar"
    const val ATTACH = "v2_composer_attach"
    /** Agent 触发器（只有官方运行时图标；名称在 contentDescription 与下拉菜单里）。 */
    const val AGENT_BUTTON = "v2_composer_agent"
    /** 触发器里的官方运行时图标。 */
    const val AGENT_ICON = "v2_composer_agent_icon"
    /** 旧的 Agent·模型 chip 容器名保留，测试与日志仍可用。 */
    const val MODEL_CHIP = "v2_composer_model_chip"
    /** 历史名称标签 tag；触发器不再渲染 Agent 名，保留常量以兼容旧测试。 */
    const val MODEL_LABEL = "v2_composer_model_label"
    /** 输入栏旁的模型选择入口（线框立方体）。 */
    const val MODEL_BUTTON = "v2_composer_model_button"
    /** 输入栏旁的上下文占用环。 */
    const val CONTEXT_RING = "v2_composer_context_ring"
    const val CONTEXT_INFO = "v2_composer_context_info"
    const val PERMISSION = "v2_composer_permission"
    const val SEND = "v2_composer_send"
    /** Agent 小窗（锚定在 Agent 胶囊上的自定义卡片）。 */
    const val AGENT_MENU = "v2_composer_agent_menu"
    /** 模型小窗（锚定在模型立方体上的自定义卡片）。 */
    const val MODEL_MENU = "v2_composer_model_menu"
    /** 小窗里的单行：前缀 + agentId / model（含 keyId 时可再拼 keyId）。 */
    const val AGENT_MENU_ROW_PREFIX = "v2_composer_agent_menu_row_"
    const val MODEL_MENU_ROW_PREFIX = "v2_composer_model_menu_row_"

    /** 输入栏上方的一张附件草稿（按 localId 区分）。 */
    fun attachmentDraft(localId: String) = "v2_composer_attachment_$localId"
    /** 图片附件的本地缩略图。 */
    fun attachmentThumb(localId: String) = "v2_composer_attachment_thumb_$localId"
    /** 失败草稿的重试入口。 */
    fun attachmentRetry(localId: String) = "v2_composer_attachment_retry_$localId"
    /** 粘贴生成的文本附件：在输入框中显示。 */
    fun attachmentReveal(localId: String) = "v2_composer_attachment_reveal_$localId"

    /** 「+」底部面板与其中的入口。 */
    const val PICK_SHEET = "v2_composer_pick_sheet"
    const val PICK_CAMERA = "v2_composer_pick_camera"
    const val PICK_IMAGE = "v2_composer_pick_image"
    const val PICK_FILE = "v2_composer_pick_file"
    const val PICK_DESIGN = "v2_composer_pick_design"
    const val PICK_SKILLS = "v2_composer_pick_skills"
    const val PICK_CONNECTORS = "v2_composer_pick_connectors"

    /** 技能 / 连接器选择面板。 */
    const val SKILL_SHEET = "v2_composer_skill_sheet"
    const val CONNECTOR_SHEET = "v2_composer_connector_sheet"
    fun skillRow(name: String) = "v2_composer_skill_row_$name"
    fun connectorRow(id: String) = "v2_composer_connector_row_$id"

    /** 输入区上方的「本次指定」芯片。 */
    fun specifiedChip(kind: String, id: String) = "v2_composer_specified_${kind}_$id"
    fun specifiedRemove(kind: String, id: String) = "v2_composer_specified_remove_${kind}_$id"
}

/** 交互类修复统一使用的日志 tag。 */
internal const val FLUX_UX_LOG_TAG = "FluxUx"

/** 触摸目标相关的稳定测试锚点（密集小胶囊）。 */
internal object V2UxTags {
    const val LOG_FILTER_PREFIX = "v2_log_filter_"
    const val LOG_FOLLOW = "v2_log_follow"
    const val LOG_CLEAR = "v2_log_clear"
    const val MEMORY_PILL_PREFIX = "v2_memory_pill_"
}
