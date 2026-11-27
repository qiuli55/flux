package top.qiuli55.flux.mobile.ui.v2

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

/**
 * 底部弹层下拉关闭 / 上拉展开的运动学。
 *
 * 这里只放可单测的纯函数：橡胶带映射、松手后的动作判定、以及对应的动画 plan。
 * 参考 Material 3 bottom sheets + M3 motion tokens（standard spatial spring）
 * 与 Apple HIG Sheets：拖动 1:1，越界用橡胶带，回位/展开用高阻尼弹簧，
 * 关闭用临界阻尼（不回弹）。
 */

/** 标准空间弹簧（Material Standard spatial-default）：允许极小过冲，但看不出晃动。 */
internal const val V2_SHEET_STANDARD_DAMPING = .9f
internal const val V2_SHEET_STANDARD_STIFFNESS = 700f

/** 关闭必须是临界阻尼：不能回弹。 */
internal const val V2_SHEET_DISMISS_DAMPING = 1f

/** 减少动态时的位移时长，与现有 LocalReduceMotion 处理保持一致。 */
internal const val V2_SHEET_REDUCE_MOTION_MS = 120

/** 向上拖过上限后的橡胶带系数：起始增益 0.55，越拉越紧。 */
internal const val V2_SHEET_RUBBER_BAND_COEFFICIENT = .55f

/** 松手后弹层要做什么。保留原有的两段式状态机语义。 */
internal enum class V2SheetRelease {
    /** 展开态下滑：收回到半高。 */
    Collapse,

    /** 半高态下滑且过阈值：关闭消失。 */
    Dismiss,

    /** 半高态上拉且过阈值：展开到全高。 */
    Expand,

    /** 没到阈值或方向不构成切换：回到原位。 */
    Settle,
}

/** 一次松手后弹层位移的完整计划，包含目标、动画 spec 与松手速度。 */
internal data class V2SheetMotionPlan(
    val release: V2SheetRelease,
    val targetOffsetPx: Float,
    val spec: AnimationSpec<Float>,
    val initialVelocity: Float,
)

/**
 * 橡胶带：overscroll 越大返回越多，但增益单调递减（渐近于 dimension），
 * 不会像硬 clamp 那样「撞墙」。overscroll <= 0 时没有位移。
 */
internal fun v2SheetRubberBand(
    overscrollPx: Float,
    dimensionPx: Float,
    coefficient: Float = V2_SHEET_RUBBER_BAND_COEFFICIENT,
): Float {
    if (overscrollPx <= 0f) return 0f
    val d = dimensionPx.coerceAtLeast(1f)
    val c = coefficient.coerceAtLeast(0f)
    return overscrollPx * d * c / (d + c * overscrollPx)
}

/** 向上拖动的上限（负值）。保留原有档位，只把超出的部分改成橡胶带。 */
internal fun v2SheetUpperDragLimit(heightPx: Float, expanded: Boolean): Float {
    val h = heightPx.coerceAtLeast(1f)
    return -h * if (expanded) .1f else .25f
}

/**
 * 把手指的累计位移映射成弹层 translationY。
 * 上限内严格 1:1 跟手；向上超出上限后进入橡胶带；向下最多到整屏高度。
 */
internal fun v2SheetDragOffset(
    dragPx: Float,
    heightPx: Float,
    expanded: Boolean,
): Float {
    val h = heightPx.coerceAtLeast(1f)
    val upperLimit = v2SheetUpperDragLimit(h, expanded)
    return when {
        dragPx < upperLimit ->
            upperLimit - v2SheetRubberBand(upperLimit - dragPx, h)
        dragPx > h -> h
        else -> dragPx
    }
}

/** nested scroll 接手一次内容位移后的结果。 */
internal data class V2SheetNestedResult(
    /** 弹层新的 translationY。 */
    val newOffsetPx: Float,
    /** 本次由弹层消费的位移（与 available 同号，向下为正）。 */
    val consumedPx: Float,
)

/**
 * 内容滚到顶部后继续下拉时，弹层如何接手。
 *
 * - availableDownPx > 0：内容已经到顶，剩下的向下位移全部由弹层消费，
 *   映射规则与手指拖动一致（1:1，越界橡胶带）；
 * - availableDownPx < 0 且弹层已被向下拖出：先把弹层 1:1 推回原位，
 *   只消费掉这部分，剩余位移再还给内容；
 * - 其它情况不消费，交给内容自己滚动。
 */
internal fun v2SheetNestedDrag(
    availableDownPx: Float,
    currentOffsetPx: Float,
    heightPx: Float,
    expanded: Boolean,
): V2SheetNestedResult {
    if (availableDownPx > 0f) {
        val next = v2SheetDragOffset(currentOffsetPx + availableDownPx, heightPx, expanded)
        return V2SheetNestedResult(next, next - currentOffsetPx)
    }
    if (availableDownPx < 0f && currentOffsetPx > 0f) {
        val next = v2SheetDragOffset(
            (currentOffsetPx + availableDownPx).coerceAtLeast(0f),
            heightPx,
            expanded,
        )
        return V2SheetNestedResult(next, next - currentOffsetPx)
    }
    return V2SheetNestedResult(currentOffsetPx, 0f)
}

/**
 * 松手后必须由弹层吃掉的速度（px/s，向下为正）。
 *
 * 只要弹层这次手势被拖出过（offset > 0），松手速度就全部由弹层消化：
 * 向下用于关闭、向上用于回位，都不能再交给内容或父页面。
 * 弹层没被拖出时（内容自己在滚）不抢内容的 fling。
 */
internal fun v2SheetConsumedVelocityY(
    availableVelocityY: Float,
    currentOffsetPx: Float,
): Float = if (currentOffsetPx > 0f) availableVelocityY else 0f

/**
 * 松手判定：沿用原来的方向 + 距离 + 速度阈值，不改变交互语义。
 *
 * @param dragPx 手指累计位移（向下为正）
 * @param velocityY 松手瞬时速度（向下为正，px/s）
 */
internal fun v2SheetReleaseAction(
    dragPx: Float,
    velocityY: Float,
    heightPx: Float,
    expanded: Boolean,
    dismissThresholdPx: Float,
    expandThresholdPx: Float,
): V2SheetRelease {
    val h = heightPx.coerceAtLeast(1f)
    return when {
        expanded && (dragPx > dismissThresholdPx || velocityY > 700f) ->
            V2SheetRelease.Collapse

        !expanded && dragPx > 0f && (dragPx > h * .3f || velocityY > 600f) ->
            V2SheetRelease.Dismiss

        !expanded && (dragPx < -expandThresholdPx || velocityY < -600f) ->
            V2SheetRelease.Expand

        else -> V2SheetRelease.Settle
    }
}

/**
 * 生成松手后的动画计划。
 *
 * - 回位 / 收起 / 展开：standard spring（damping .9 / stiffness 700），把松手速度带进去；
 * - 关闭：临界阻尼（damping 1），同样带速度，不做固定 tween；
 * - ReduceMotion：统一 120ms tween，且不注入速度，避免任何位移弹跳。
 */
internal fun v2SheetMotionPlan(
    release: V2SheetRelease,
    heightPx: Float,
    velocityY: Float,
    reduceMotion: Boolean,
): V2SheetMotionPlan {
    val h = heightPx.coerceAtLeast(1f)
    val target = if (release == V2SheetRelease.Dismiss) h else 0f
    if (reduceMotion) {
        return V2SheetMotionPlan(
            release = release,
            targetOffsetPx = target,
            spec = tween(durationMillis = V2_SHEET_REDUCE_MOTION_MS),
            initialVelocity = 0f,
        )
    }
    val damping =
        if (release == V2SheetRelease.Dismiss) V2_SHEET_DISMISS_DAMPING
        else V2_SHEET_STANDARD_DAMPING
    return V2SheetMotionPlan(
        release = release,
        targetOffsetPx = target,
        spec = spring(dampingRatio = damping, stiffness = V2_SHEET_STANDARD_STIFFNESS),
        initialVelocity = velocityY,
    )
}
