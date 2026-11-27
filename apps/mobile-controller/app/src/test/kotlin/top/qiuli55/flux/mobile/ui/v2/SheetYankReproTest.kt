package top.qiuli55.flux.mobile.ui.v2

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 弹层关闭不能把动量/剩余位移漏给背后的页面。
 *
 * 用一个包住「父列表 + 弹层」的祖先 [NestedScrollConnection] 做监听：
 * 如果弹层没有自己吃掉向下剩余位移和松手速度，这里就会看到非零值。
 * 父列表的 [ScrollState] 同时必须保持 0。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], qualifiers = "w400dp-h800dp-mdpi")
class SheetYankReproTest {

    @get:Rule
    val rule = createComposeRule()

    private val open = mutableStateOf(true)
    private val parentScroll = ScrollState(0)

    /** 弹层往外漏的向下剩余位移（px）。 */
    private var leakedDownScroll = 0f

    /** 弹层往外漏的向下剩余速度（px/s）。 */
    private var leakedDownFling = 0f

    private fun renderSheet() {
        open.value = true
        leakedDownScroll = 0f
        leakedDownFling = 0f
        rule.setContent {
            val leakSpy = remember {
                object : NestedScrollConnection {
                    override fun onPostScroll(
                        consumed: Offset,
                        available: Offset,
                        source: NestedScrollSource,
                    ): Offset {
                        if (available.y > 0f) leakedDownScroll += available.y
                        return Offset.Zero
                    }

                    override suspend fun onPreFling(available: Velocity): Velocity {
                        // pre-fling 是祖先先看到，不能作为「泄漏」判据；
                        // 真正的剩余速度看 post-fling（child-first）。
                        return Velocity.Zero
                    }

                    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                        if (available.y > 0f) leakedDownFling += available.y
                        return Velocity.Zero
                    }
                }
            }
            Box(Modifier.fillMaxSize().nestedScroll(leakSpy)) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(parentScroll)
                        .testTag(PARENT),
                ) {
                    repeat(20) { Box(Modifier.fillMaxWidth().height(120.dp)) }
                }
                if (open.value) {
                    // 用「我的」同款结构：弹层内容顶部就是可滚动列表。
                    SheetFrame(onDismiss = { open.value = false }) {
                        Column(
                            Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .testTag(SHEET),
                        ) {
                            repeat(30) { Box(Modifier.fillMaxWidth().height(60.dp)) }
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun `pulling sheet content down dismisses without leaking to the page behind`() {
        renderSheet()

        // 从内容区（把手 96dp 以下）往下拉：由 nested scroll 交给弹层并关闭。
        rule.onNodeWithTag(SHEET).performTouchInput {
            swipe(
                start = Offset(centerX, top + 150.dp.toPx()),
                end = Offset(centerX, bottom - 10.dp.toPx()),
                durationMillis = 200,
            )
        }
        rule.mainClock.advanceTimeBy(2500)
        rule.waitForIdle()

        assertFalse("内容到顶后下拉应关闭弹层", open.value)
        assertEquals("父列表不能被滚动", 0, parentScroll.value)
        assertEquals("父级不能收到向下的剩余位移", 0f, leakedDownScroll, .001f)
        assertEquals("父级不能收到向下的剩余速度", 0f, leakedDownFling, .001f)
    }

    @Test
    fun `flinging the handle down dismisses without leaking`() {
        renderSheet()

        // 从最顶部（把手区）快速下甩。
        rule.onNodeWithTag(SHEET).performTouchInput {
            swipe(
                start = Offset(centerX, top + 8.dp.toPx()),
                end = Offset(centerX, top + 300.dp.toPx()),
                durationMillis = 90,
            )
        }
        rule.mainClock.advanceTimeBy(2500)
        rule.waitForIdle()

        assertFalse("把手快速下甩应关闭弹层", open.value)
        assertEquals("父列表不能被滚动", 0, parentScroll.value)
        assertEquals("父级不能收到向下的剩余位移", 0f, leakedDownScroll, .001f)
        assertEquals("父级不能收到向下的剩余速度", 0f, leakedDownFling, .001f)
    }

    @Test
    fun `small pull from content top settles back and keeps the sheet open`() {
        renderSheet()

        // 40dp 的慢速下拉：不到关闭阈值，弹层应回位而不是把位移漏给父页。
        rule.onNodeWithTag(SHEET).performTouchInput {
            swipe(
                start = Offset(centerX, top + 150.dp.toPx()),
                end = Offset(centerX, top + 190.dp.toPx()),
                durationMillis = 600,
            )
        }
        rule.mainClock.advanceTimeBy(2500)
        rule.waitForIdle()

        assertTrue("未过阈值时应回位，弹层保持打开", open.value)
        assertEquals("父列表不能被滚动", 0, parentScroll.value)
        assertEquals("父级不能收到向下的剩余位移", 0f, leakedDownScroll, .001f)
    }

    private companion object {
        const val PARENT = "sheet_yank_parent"
        const val SHEET = "sheet_yank_content"
    }
}
