package top.qiuli55.flux.mobile.ui.v2.design

import androidx.compose.ui.geometry.Rect
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import top.qiuli55.flux.mobile.data.DesignAiOp
import top.qiuli55.flux.mobile.data.DesignDocument
import top.qiuli55.flux.mobile.data.DesignNode
import top.qiuli55.flux.mobile.data.DesignRadius
import top.qiuli55.flux.mobile.data.DesignRadiusSerializer
import top.qiuli55.flux.mobile.data.DesignScreen
import top.qiuli55.flux.mobile.data.DesignStroke
import top.qiuli55.flux.mobile.data.DesignStrokeSerializer
import kotlin.math.roundToInt

enum class DesignChangeKind { CHANGED, ADDED, REMOVED, REORDERED }

/**
 * 一条可读的变更：节点、类型、人话描述、画板绝对坐标框。
 *
 * [frame] 用于在画布上高亮；删除项取删除前的框，新增 / 修改项取应用后的框。
 */
data class DesignAiChange(
    val kind: DesignChangeKind,
    val nodeId: String,
    val title: String,
    val detail: String,
    val frame: Rect?,
)

/**
 * AI 修改的「待确认计划」：先算出预览稿与变更清单，用户确认后才真正写入画布。
 *
 * 状态机与桌面 `DesignAiPlanner.cs` 对齐：
 *  - [preview] 是应用全部 op 后的新文档，原文档保持不变；
 *  - [applied] 按「每个被处理的属性 / 每条结构操作计 1」统计，与桌面 `DesignOps.ApplyOps` 一致；
 *  - [unapplied] 是无法应用的 op 条数，界面要如实说明而不是静默吞掉。
 */
data class DesignAiPlan(
    val preview: DesignDocument,
    val changes: List<DesignAiChange>,
    val applied: Int,
    val totalOps: Int,
    val unapplied: Int,
    val reply: String = "",
    /**
     * 每应用完一条 op 后的中间文档（与 [changes] 同序）。
     * 确认后可以一项一项地演进画布；取消时直接回到应用前。
     */
    val steps: List<DesignDocument> = emptyList(),
) {
    val hasChanges: Boolean get() = applied > 0

    /** 「修改 3 项」这类简短统计。 */
    val statLine: String
        get() = when {
            applied <= 0 -> "没有可应用的修改"
            unapplied > 0 -> "可应用 $applied 项，另有 $unapplied 项无法应用"
            else -> "共 $applied 项修改"
        }
}

/**
 * 把 set / add / remove / reorder 套到画布上，同时产出人话清单与高亮框。
 *
 * 纯函数，不碰 UI 与网络；桌面同名实现 `DesignAiPlanner` 逐条对齐。
 */
object DesignAiPlanner {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = true
    }

    /** 与桌面 `DesignOps.AllowedSet` / 网页 `aiApplyPatch` 的白名单一致。 */
    val ALLOWED_SET = setOf(
        "x", "y", "w", "h", "radius", "fill", "color", "text",
        "fontSize", "fontWeight", "opacity", "letterSpacing",
        "stroke", "lineHeight", "align", "blur", "padLeft",
        "value", "track", "on", "items", "active",
    )

    fun plan(
        document: DesignDocument,
        screenIndex: Int,
        targets: Set<String>,
        ops: List<DesignAiOp>,
        reply: String = "",
    ): DesignAiPlan {
        val screen = document.screens.getOrNull(screenIndex)
            ?: return DesignAiPlan(document, emptyList(), 0, ops.size, ops.size, reply)
        val beforeFrames = absoluteFrames(screen.nodes)
        var nodes = screen.nodes
        var applied = 0
        var unapplied = 0
        val changes = mutableListOf<DesignAiChange>()
        val steps = mutableListOf<DesignDocument>()

        /** 当前 nodes 对应的完整文档快照；分步应用时每一步都用它。 */
        fun snapshot(): DesignDocument {
            val screens = document.screens.toMutableList()
            screens[screenIndex] = screen.copy(nodes = nodes)
            return document.copy(screens = screens)
        }

        ops.forEach { op ->
            val kind = op.op.ifBlank { "set" }.lowercase()
            val changesBefore = changes.size
            var hit = false
            when (kind) {
                "set" -> {
                    val target = op.target?.takeIf { it.isNotBlank() } ?: targets.firstOrNull()
                    val set = op.set
                    val allowed = set.keys.filter { it in ALLOWED_SET }
                    if (!target.isNullOrBlank() && allowed.isNotEmpty()) {
                        val before = findDesignNode(nodes, target)
                        if (before != null) {
                            var after: DesignNode? = null
                            nodes = mapDesignNode(nodes, target) { node ->
                                after = patchNode(node, set)
                                after!!
                            }
                            val updated = after
                            if (updated != null) {
                                hit = true
                                applied += allowed.size
                                val detail = describeNodeChange(before, updated, set)
                                changes += DesignAiChange(
                                    kind = DesignChangeKind.CHANGED,
                                    nodeId = target,
                                    title = nodeTitle(before),
                                    detail = detail.ifBlank { "调整了 ${allowed.size} 项属性" },
                                    frame = frameOf(nodes, target),
                                )
                            }
                        }
                    }
                }

                "add" -> {
                    val payload = op.node
                    val node = payload?.let(::decodeNode)
                    if (node != null && findDesignNode(nodes, node.id) == null) {
                        val index = op.index ?: Int.MAX_VALUE
                        val target = op.target
                        if (target.isNullOrBlank() || target == "@screen") {
                            nodes = insertNode(nodes, index, node)
                            hit = true
                        } else if (findDesignNode(nodes, target) != null) {
                            nodes = mapDesignNode(nodes, target) { parent ->
                                parent.copy(children = insertNode(parent.children, index, node))
                            }
                            hit = true
                        }
                        if (hit) {
                            applied += 1
                            changes += DesignAiChange(
                                kind = DesignChangeKind.ADDED,
                                nodeId = node.id,
                                title = "新增「${nodeTitle(node)}」",
                                detail = "添加 ${node.type.ifBlank { "节点" }} · " +
                                    "${node.w.roundToInt()}×${node.h.roundToInt()}",
                                frame = frameOf(nodes, node.id),
                            )
                        }
                    }
                }

                "remove" -> {
                    val target = op.target?.takeIf { it.isNotBlank() }
                    val before = target?.let { findDesignNode(nodes, it) }
                    if (target != null && before != null) {
                        val (updated, removed) = removeDesignNode(nodes, target)
                        if (removed) {
                            nodes = updated
                            hit = true
                            applied += 1
                            changes += DesignAiChange(
                                kind = DesignChangeKind.REMOVED,
                                nodeId = target,
                                title = "删除「${nodeTitle(before)}」",
                                detail = "从画布移除 ${before.type.ifBlank { "节点" }}",
                                frame = beforeFrames[target],
                            )
                        }
                    }
                }

                "reorder" -> {
                    val target = op.target?.takeIf { it.isNotBlank() }
                    val before = target?.let { findDesignNode(nodes, it) }
                    if (target != null && before != null) {
                        val (updated, moved) = reorderDesignNode(nodes, target, op.index ?: Int.MAX_VALUE)
                        if (moved) {
                            nodes = updated
                            hit = true
                            applied += 1
                            changes += DesignAiChange(
                                kind = DesignChangeKind.REORDERED,
                                nodeId = target,
                                title = "调整「${nodeTitle(before)}」层级",
                                detail = "移动到第 ${(op.index ?: 0) + 1} 层",
                                frame = frameOf(nodes, target),
                            )
                        }
                    }
                }
            }
            if (!hit) unapplied += 1
            // 一条成功 op 产生一个变更项，也对应一份可独立呈现的中间稿。
            if (changes.size > changesBefore) steps += snapshot()
        }

        if (applied == 0) {
            return DesignAiPlan(document, emptyList(), 0, ops.size, maxOf(unapplied, 1), reply)
        }

        return DesignAiPlan(
            preview = steps.lastOrNull() ?: snapshot(),
            changes = changes,
            applied = applied,
            totalOps = ops.size,
            unapplied = unapplied,
            reply = reply,
            steps = steps,
        )
    }

    // ------------------------------------------------------------ 节点工具

    fun findDesignNode(nodes: List<DesignNode>, id: String): DesignNode? {
        for (node in nodes) {
            if (node.id == id) return node
            findDesignNode(node.children, id)?.let { return it }
        }
        return null
    }

    fun mapDesignNode(
        nodes: List<DesignNode>,
        id: String,
        transform: (DesignNode) -> DesignNode,
    ): List<DesignNode> = nodes.map { node ->
        when {
            node.id == id -> transform(node)
            node.children.isNotEmpty() ->
                node.copy(children = mapDesignNode(node.children, id, transform))
            else -> node
        }
    }

    /** 画板绝对坐标框；高亮与变更清单都用它。 */
    fun absoluteFrames(nodes: List<DesignNode>, originX: Float = 0f, originY: Float = 0f): Map<String, Rect> {
        val out = LinkedHashMap<String, Rect>()
        nodes.forEach { node ->
            val x = originX + node.x
            val y = originY + node.y
            out[node.id] = Rect(x, y, x + node.w, y + node.h)
            if (node.children.isNotEmpty()) {
                out.putAll(absoluteFrames(node.children, x, y))
            }
        }
        return out
    }

    fun frameOf(nodes: List<DesignNode>, id: String): Rect? = absoluteFrames(nodes)[id]

    // ------------------------------------------------------------ 内部

    private fun decodeNode(payload: JsonObject): DesignNode? =
        runCatching { json.decodeFromJsonElement(DesignNode.serializer(), payload) }
            .getOrNull()
            ?.takeIf { it.id.isNotBlank() && it.type.isNotBlank() }

    /** 白名单 + 数值归一化，与桌面 `DesignOps.ApplyPatch` 一致。 */
    internal fun patchNode(node: DesignNode, patch: JsonObject): DesignNode = node.copy(
        x = patch.float("x") ?: node.x,
        y = patch.float("y") ?: node.y,
        w = patch.float("w")?.coerceAtLeast(1f) ?: node.w,
        h = patch.float("h")?.coerceAtLeast(1f) ?: node.h,
        radius = radiusPatch(patch) ?: node.radius,
        fill = patch.string("fill") ?: node.fill,
        color = patch.string("color") ?: node.color,
        text = patch.string("text") ?: node.text,
        fontSize = patch.float("fontSize")?.coerceAtLeast(6f) ?: node.fontSize,
        fontWeight = patch.float("fontWeight")?.let {
            if (it == 0f || it.isNaN()) 400 else it.roundToInt().coerceIn(1, 1000)
        } ?: node.fontWeight,
        opacity = patch.float("opacity")?.coerceIn(0f, 1f) ?: node.opacity,
        letterSpacing = patch.float("letterSpacing") ?: node.letterSpacing,
        stroke = strokePatch(patch) ?: node.stroke,
        lineHeight = patch.float("lineHeight") ?: node.lineHeight,
        align = patch.string("align") ?: node.align,
        blur = patch.float("blur")?.coerceAtLeast(0f) ?: node.blur,
        padLeft = patch.float("padLeft")?.coerceAtLeast(0f) ?: node.padLeft,
        value = patch.float("value")?.coerceIn(0f, 1f) ?: node.value,
        track = patch.string("track") ?: node.track,
        on = patch.bool("on") ?: node.on,
        items = patch.strings("items")?.take(12) ?: node.items,
        active = patch.int("active") ?: node.active,
    )

    /**
     * radius 支持 number / [tl,tr,br,bl] / {tl,tr,br,bl}；单角负值按 0。
     * 与桌面 `DesignOps` 一样接受三种写法，避免 AI 返回对象时两端画得不一样。
     */
    private fun radiusPatch(patch: JsonObject): DesignRadius? {
        val raw = patch["radius"] ?: return null
        val decoded = runCatching {
            json.decodeFromJsonElement(DesignRadiusSerializer, raw)
        }.getOrNull() ?: return null
        fun clamp(value: Float?) = value?.coerceAtLeast(0f)
        return DesignRadius(
            tl = clamp(decoded.tl),
            tr = clamp(decoded.tr),
            br = clamp(decoded.br),
            bl = clamp(decoded.bl),
        )
    }

    /** stroke 支持字符串与对象；对象走和设计稿加载时相同的序列化器。 */
    private fun strokePatch(patch: JsonObject): DesignStroke? {
        val raw = patch["stroke"] ?: return null
        return runCatching {
            json.decodeFromJsonElement(DesignStrokeSerializer, raw)
        }.getOrNull()
    }

    internal fun describeNodeChange(
        before: DesignNode,
        after: DesignNode,
        set: JsonObject,
    ): String {
        val parts = mutableListOf<String>()
        set.keys.filter { it in ALLOWED_SET }.forEach { key ->
            val line = describeField(key, before, after)
            if (line != null) parts += line
        }
        return parts.joinToString("；")
    }

    private fun describeField(key: String, before: DesignNode, after: DesignNode): String? {
        fun changed(a: Any?, b: Any?): Boolean = a != b
        return when (key) {
            "text" -> if (changed(before.text, after.text)) {
                "文案 ${quote(before.text)} → ${quote(after.text)}"
            } else null
            "fontSize" -> if (changed(before.fontSize, after.fontSize)) {
                "字号 ${num(before.fontSize)} → ${num(after.fontSize)}"
            } else null
            "fontWeight" -> if (changed(before.fontWeight, after.fontWeight)) {
                "字重 ${num(before.fontWeight)} → ${num(after.fontWeight)}"
            } else null
            "fill" -> if (changed(before.fill, after.fill)) {
                "填充 ${colorText(before.fill)} → ${colorText(after.fill)}"
            } else null
            "color" -> if (changed(before.color, after.color)) {
                "文字颜色 ${colorText(before.color)} → ${colorText(after.color)}"
            } else null
            "x" -> if (changed(before.x, after.x)) "X ${num(before.x)} → ${num(after.x)}" else null
            "y" -> if (changed(before.y, after.y)) "Y ${num(before.y)} → ${num(after.y)}" else null
            "w" -> if (changed(before.w, after.w)) "宽度 ${num(before.w)} → ${num(after.w)}" else null
            "h" -> if (changed(before.h, after.h)) "高度 ${num(before.h)} → ${num(after.h)}" else null
            "radius" -> if (changed(before.radius?.tl, after.radius?.tl)) {
                "圆角 ${num(before.radius?.tl)} → ${num(after.radius?.tl)}"
            } else null
            "opacity" -> if (changed(before.opacity, after.opacity)) {
                "不透明度 ${percent(before.opacity)} → ${percent(after.opacity)}"
            } else null
            "align" -> if (changed(before.align, after.align)) {
                "对齐 ${before.align ?: "默认"} → ${after.align ?: "默认"}"
            } else null
            "lineHeight" -> if (changed(before.lineHeight, after.lineHeight)) {
                "行高 ${num(before.lineHeight)} → ${num(after.lineHeight)}"
            } else null
            "letterSpacing" -> if (changed(before.letterSpacing, after.letterSpacing)) {
                "字距 ${num(before.letterSpacing)} → ${num(after.letterSpacing)}"
            } else null
            "stroke" -> if (changed(before.stroke?.color, after.stroke?.color) ||
                changed(before.stroke?.width, after.stroke?.width)
            ) {
                "描边 ${strokeText(before.stroke)} → ${strokeText(after.stroke)}"
            } else null
            "blur" -> if (changed(before.blur, after.blur)) {
                "模糊 ${num(before.blur)} → ${num(after.blur)}"
            } else null
            "padLeft" -> if (changed(before.padLeft, after.padLeft)) {
                "左内边距 ${num(before.padLeft)} → ${num(after.padLeft)}"
            } else null
            "value" -> if (changed(before.value, after.value)) {
                "进度 ${percent(before.value)} → ${percent(after.value)}"
            } else null
            "track" -> if (changed(before.track, after.track)) {
                "轨道 ${colorText(before.track)} → ${colorText(after.track)}"
            } else null
            "on" -> if (changed(before.on, after.on)) {
                "开关 ${if (before.on == true) "开" else "关"} → ${if (after.on == true) "开" else "关"}"
            } else null
            "items" -> if (changed(before.items, after.items)) "导航项已更新" else null
            "active" -> if (changed(before.active, after.active)) {
                "选中项 ${(before.active ?: 0) + 1} → ${(after.active ?: 0) + 1}"
            } else null
            else -> null
        }
    }

    private fun insertNode(nodes: List<DesignNode>, index: Int, node: DesignNode): List<DesignNode> {
        val list = nodes.toMutableList()
        list.add(index.coerceIn(0, list.size), node)
        return list
    }

    fun removeDesignNode(nodes: List<DesignNode>, id: String): Pair<List<DesignNode>, Boolean> {
        var removed = false
        val next = nodes.mapNotNull { node ->
            when {
                node.id == id -> {
                    removed = true
                    null
                }
                node.children.isNotEmpty() -> {
                    val (children, hit) = removeDesignNode(node.children, id)
                    if (hit) {
                        removed = true
                        node.copy(children = children)
                    } else {
                        node
                    }
                }
                else -> node
            }
        }
        return next to removed
    }

    private fun reorderDesignNode(
        nodes: List<DesignNode>,
        id: String,
        index: Int,
    ): Pair<List<DesignNode>, Boolean> {
        val mutable = nodes.toMutableList()
        val at = mutable.indexOfFirst { it.id == id }
        if (at >= 0) {
            val node = mutable.removeAt(at)
            mutable.add(index.coerceIn(0, mutable.size), node)
            return mutable to true
        }
        var moved = false
        val next = mutable.map { node ->
            if (!moved && node.children.isNotEmpty()) {
                val (children, hit) = reorderDesignNode(node.children, id, index)
                if (hit) {
                    moved = true
                    node.copy(children = children)
                } else {
                    node
                }
            } else {
                node
            }
        }
        return next to moved
    }

    private fun nodeTitle(node: DesignNode): String =
        node.name.ifBlank { node.type.ifBlank { node.id } }

    private fun quote(raw: String?): String {
        val value = raw.orEmpty().replace("\n", " ").trim()
        if (value.isEmpty()) return "（空）"
        return "「${value.take(18)}${if (value.length > 18) "…" else ""}」"
    }

    private fun colorText(raw: String?): String =
        raw?.takeIf { it.isNotBlank() } ?: "默认"

    private fun strokeText(stroke: DesignStroke?): String = when {
        stroke == null -> "无"
        stroke.color.isNullOrBlank() -> "无"
        else -> "${stroke.color} ${num(stroke.width)}px"
    }

    private fun num(value: Number?): String {
        if (value == null) return "—"
        val double = value.toDouble()
        if (double.isNaN()) return "—"
        return if (double == double.toLong().toDouble()) double.toLong().toString()
        else ((double * 10).roundToInt() / 10.0).toString()
    }

    private fun percent(value: Float?): String =
        if (value == null) "—" else "${(value * 100).roundToInt()}%"

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.float(key: String): Float? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toFloatOrNull()

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()

    private fun JsonObject.bool(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()

    private fun JsonObject.strings(key: String): List<String>? {
        val array = this[key] as? JsonArray ?: return null
        return array.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    }
}
