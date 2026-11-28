package top.qiuli55.flux.mobile.ui.v2

import top.qiuli55.flux.mobile.data.ThinkingSpec

/**
 * 密钥库模型的思考模式（纯逻辑，供 UI 与单测共用）。
 *
 * 类型与档位来自后端 `thinking-defaults` 的预填值；用户可以在四种类型之间改，
 * 也可以在已定档位里改默认档。这里不维护第二份模型能力表，只负责把用户选择
 * 归一成可以提交给后端的形状。
 */

internal val V2_THINKING_KINDS: List<Pair<String, String>> = listOf(
    "none" to "不支持思考",
    "toggle" to "开 / 关",
    "levels" to "多档",
    "always" to "始终思考",
)

/** 对不上官方模型时的通用档位：无思考 + 5 档（共 6 项）。 */
internal val V2_GENERIC_THINKING_LEVELS: List<String> =
    listOf("none", "minimal", "low", "medium", "high", "max")

private val v2ThinkingLevelLabels: Map<String, String> = mapOf(
    "none" to "无思考",
    "minimal" to "最低",
    "low" to "低",
    "medium" to "中",
    "high" to "高",
    "max" to "最高",
    "on" to "开",
    "off" to "关",
)

internal fun thinkingKindLabel(kind: String): String =
    V2_THINKING_KINDS.firstOrNull { it.first == kind }?.second ?: "不支持思考"

internal fun thinkingLevelLabel(level: String): String =
    v2ThinkingLevelLabels[level] ?: level

internal fun thinkingSpecLabels(spec: ThinkingSpec): List<String> =
    spec.levels.mapIndexed { index, level ->
        spec.labels.getOrNull(index)?.takeIf { it.isNotBlank() } ?: thinkingLevelLabel(level)
    }

/** 当前档位；未设置或不合法时落到第一个可选档。 */
internal fun thinkingActiveLevel(spec: ThinkingSpec): String =
    spec.default.takeIf { it in spec.levels } ?: spec.levels.firstOrNull().orEmpty()

/**
 * 切换思考类型。保留能对上的默认档；新类型的档位按后端预填 / 通用规则给出。
 */
internal fun switchThinkingKind(current: ThinkingSpec, kind: String): ThinkingSpec = when (kind) {
    "none" -> ThinkingSpec(kind = "none", canDisable = false)
    "toggle" -> ThinkingSpec(
        kind = "toggle",
        levels = listOf("on", "off"),
        labels = listOf("开", "关"),
        default = current.default.takeIf { it in setOf("on", "off") } ?: "on",
        canDisable = true,
    )
    "levels" -> {
        val levels = current.levels.takeIf { current.kind == "levels" && it.isNotEmpty() }
            ?: V2_GENERIC_THINKING_LEVELS
        val labels = if (current.kind == "levels" && current.labels.size == levels.size) {
            current.labels
        } else {
            levels.map(::thinkingLevelLabel)
        }
        ThinkingSpec(
            kind = "levels",
            levels = levels,
            labels = labels,
            default = current.default.takeIf { it in levels } ?: levels.first(),
            canDisable = true,
        )
    }
    "always" -> ThinkingSpec(kind = "always", default = "always", canDisable = false)
    else -> current
}

/** 每个登记模型都必须有合法思考模式，才能保存。 */
internal fun keyThinkingComplete(
    models: List<String>,
    specs: Map<String, ThinkingSpec>,
): Boolean = models.isNotEmpty() && models.all { model ->
    val spec = specs[model] ?: return@all false
    when (spec.kind) {
        "none", "always" -> true
        "toggle" -> spec.default in setOf("on", "off")
        "levels" -> spec.levels.isNotEmpty() && spec.default in spec.levels
        else -> false
    }
}

/** 只提交当前模型清单里的思考模式。 */
internal fun keyThinkingPayload(
    models: List<String>,
    specs: Map<String, ThinkingSpec>,
): Map<String, ThinkingSpec> = models.mapNotNull { model ->
    specs[model]?.let { model to it }
}.toMap()
