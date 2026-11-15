package top.qiuli55.flux.mobile.ui.v2

/**
 * 输入区（composer）的测试标记与日志 tag。
 *
 * 加这些标记是为了让 Compose UI 测试能定位「+ / 模型 chip / 盾牌 / 发送」四个按钮，
 * 验证窄屏 + 大字号 + 超长模型名时发送按钮仍在根容器内、尺寸 >= 48dp 且可点击。
 * 只加 testTag，不改变任何视觉或交互语义。
 */
internal object V2ComposerTags {
    const val ROOT = "v2_composer_root"
    const val INPUT = "v2_composer_input"
    const val TOOLBAR = "v2_composer_toolbar"
    const val ATTACH = "v2_composer_attach"
    const val MODEL_CHIP = "v2_composer_model_chip"
    const val MODEL_LABEL = "v2_composer_model_label"
    const val PERMISSION = "v2_composer_permission"
    const val SEND = "v2_composer_send"
}

/** 交互类修复统一使用的日志 tag。 */
internal const val FLUX_UX_LOG_TAG = "FluxUx"
