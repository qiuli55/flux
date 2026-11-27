package top.qiuli55.flux.mobile.ui.v2

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState

/**
 * 要把某一项的**底部**贴到列表可视区底部，还需要再向下滚多少像素。
 *
 * `LazyListState.scrollToItem(index)` 默认把该项顶部对齐视口顶部。最后一项
 * （尤其是超长回复）比一屏高时，这会把用户从消息末尾拽回开头——正是对话页
 * 滑到底后突然 snap 到长消息开头的根因。
 *
 * 坐标来自 `LazyListLayoutInfo`：`itemOffset` 以内容视口起点为 0；
 * 可视内容底边是 `viewportStartOffset + viewportEndOffset`（已经扣掉上下
 * contentPadding）。这里只返回正数：已经在底部或该项不足一屏时返回 0，
 * 避免把视口推过界。
 */
internal fun bottomAlignScrollDelta(
    itemOffset: Int,
    itemSize: Int,
    viewportStartOffset: Int,
    viewportEndOffset: Int,
): Int = (itemOffset + itemSize - (viewportStartOffset + viewportEndOffset)).coerceAtLeast(0)

/**
 * 把 [index] 这一项滚到列表底部（视口对齐该项末尾），而不是像
 * [LazyListState.scrollToItem] 那样对齐该项开头。
 *
 * 该项还没被测量时先滚到它拿到真实高度，再补齐剩下的距离。
 */
internal suspend fun LazyListState.scrollToBottomOfItem(index: Int) {
    if (index < 0 || index >= layoutInfo.totalItemsCount) return
    if (layoutInfo.visibleItemsInfo.none { it.index == index }) {
        scrollToItem(index)
    }
    val info = layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return
    val delta = bottomAlignScrollDelta(
        itemOffset = item.offset,
        itemSize = item.size,
        viewportStartOffset = info.viewportStartOffset,
        viewportEndOffset = info.viewportEndOffset,
    )
    if (delta > 0) scrollBy(delta.toFloat())
}
