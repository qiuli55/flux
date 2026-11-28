package top.qiuli55.flux.mobile.ui.v2.design

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.qiuli55.flux.mobile.data.DesignAiOp
import top.qiuli55.flux.mobile.data.DesignDocument
import top.qiuli55.flux.mobile.data.DesignNode
import top.qiuli55.flux.mobile.data.DesignScreen

/**
 * AI 修改计划：分类、人话摘要、分步应用计数与「确认前不落盘」。
 * 桌面 `DesignAiPlanner.cs` 与本文件的语义必须一致。
 */
class DesignAiPlannerTest {

    private fun document(): DesignDocument = DesignDocument(
        id = "d1",
        name = "首页",
        version = 3,
        screens = listOf(
            DesignScreen(
                id = "s1",
                name = "首页",
                w = 390f,
                h = 844f,
                nodes = listOf(
                    DesignNode(
                        id = "title",
                        type = "text",
                        name = "标题",
                        x = 20f,
                        y = 40f,
                        w = 300f,
                        h = 30f,
                        text = "旧标题",
                        fontSize = 16f,
                    ),
                    DesignNode(
                        id = "card",
                        type = "card",
                        name = "内容卡",
                        x = 20f,
                        y = 100f,
                        w = 350f,
                        h = 200f,
                        children = listOf(
                            DesignNode(
                                id = "child",
                                type = "text",
                                name = "卡内标题",
                                x = 10f,
                                y = 12f,
                                w = 100f,
                                h = 20f,
                            ),
                        ),
                    ),
                ),
            ),
        ),
    )

    private val setTitle = DesignAiOp(
        op = "set",
        target = "title",
        set = buildJsonObject {
            put("fontSize", 20)
            put("text", "新标题")
        },
    )

    private val addNode = DesignAiOp(
        op = "add",
        node = buildJsonObject {
            put("id", "new")
            put("type", "button")
            put("name", "新按钮")
            put("x", 20)
            put("y", 600)
            put("w", 200)
            put("h", 48)
        },
    )

    private val removeCard = DesignAiOp(op = "remove", target = "card")
    private val reorderTitle = DesignAiOp(op = "reorder", target = "title", index = 1)

    @Test
    fun `plan applies ops to preview without touching source`() {
        val source = document()
        val plan = DesignAiPlanner.plan(
            source,
            screenIndex = 0,
            targets = setOf("title"),
            ops = listOf(setTitle, addNode, removeCard, reorderTitle),
        )

        assertEquals(5, plan.applied)
        assertEquals(4, plan.totalOps)
        assertEquals(0, plan.unapplied)
        assertTrue(plan.hasChanges)

        // 源文档保持不变，预览稿才是应用结果。
        assertEquals(2, source.screens[0].nodes.size)
        assertEquals(16f, source.screens[0].nodes[0].fontSize)
        assertEquals(2, plan.preview.screens[0].nodes.size)
        assertEquals(20f, plan.preview.screens[0].nodes.first { it.id == "title" }.fontSize)
        assertNull(DesignAiPlanner.findDesignNode(source.screens[0].nodes, "new"))
        assertTrue(DesignAiPlanner.findDesignNode(plan.preview.screens[0].nodes, "new") != null)
        assertNull(DesignAiPlanner.findDesignNode(plan.preview.screens[0].nodes, "card"))
    }

    @Test
    fun `changes classify and summarize in human language`() {
        val plan = DesignAiPlanner.plan(
            document(),
            screenIndex = 0,
            targets = setOf("title"),
            ops = listOf(setTitle, addNode, removeCard, reorderTitle),
        )
        val kinds = plan.changes.map { it.kind }.toSet()
        assertEquals(
            setOf(
                DesignChangeKind.CHANGED,
                DesignChangeKind.ADDED,
                DesignChangeKind.REMOVED,
                DesignChangeKind.REORDERED,
            ),
            kinds,
        )
        val changed = plan.changes.first { it.kind == DesignChangeKind.CHANGED }
        assertEquals("标题", changed.title)
        assertTrue(changed.detail.contains("字号 16 → 20"))
        assertTrue(changed.detail.contains("新标题"))
        assertEquals("新增「新按钮」", plan.changes.first { it.kind == DesignChangeKind.ADDED }.title)
        assertEquals("删除「内容卡」", plan.changes.first { it.kind == DesignChangeKind.REMOVED }.title)
        assertTrue(plan.statLine.contains("5"))
    }

    @Test
    fun `empty or unusable ops produce no plan`() {
        val source = document()
        val empty = DesignAiPlanner.plan(source, 0, setOf("title"), emptyList())
        assertEquals(0, empty.applied)
        assertFalse(empty.hasChanges)
        assertTrue(empty.changes.isEmpty())
        assertTrue(empty.preview === source)

        val missing = DesignAiPlanner.plan(
            source,
            0,
            setOf("title"),
            listOf(DesignAiOp(op = "set", target = "missing", set = buildJsonObject { put("text", "x") })),
        )
        assertEquals(0, missing.applied)
        assertEquals(1, missing.unapplied)
        assertFalse(missing.hasChanges)
    }

    @Test
    fun `set without target falls back to first selected node`() {
        val plan = DesignAiPlanner.plan(
            document(),
            0,
            targets = setOf("title"),
            ops = listOf(DesignAiOp(op = "set", set = buildJsonObject { put("opacity", 0.5) })),
        )
        assertEquals(1, plan.applied)
        assertEquals(0.5f, plan.preview.screens[0].nodes.first { it.id == "title" }.opacity)
    }

    @Test
    fun `absolute frames accumulate parent offsets`() {
        val frames = DesignAiPlanner.absoluteFrames(document().screens[0].nodes)
        assertEquals(20f, frames.getValue("title").left, .01f)
        assertEquals(30f, frames.getValue("child").left, .01f)
        assertEquals(112f, frames.getValue("child").top, .01f)
        assertEquals(100f, frames.getValue("child").width, .01f)
    }

    @Test
    fun `plan exposes one intermediate document per applied op`() {
        val plan = DesignAiPlanner.plan(
            document(),
            0,
            setOf("title"),
            listOf(setTitle, addNode),
        )
        assertEquals(2, plan.changes.size)
        assertEquals(2, plan.steps.size)

        // 第一步只改了标题，新按钮还没出现
        val first = plan.steps[0].screens[0].nodes
        assertEquals("新标题", first.first { it.id == "title" }.text)
        assertNull(first.firstOrNull { it.id == "new" })

        // 第二步才加入新按钮，且最终稿就是最后一步
        val second = plan.steps[1].screens[0].nodes
        assertTrue(second.any { it.id == "new" })
        assertEquals(
            plan.preview.screens[0].nodes.map { it.id },
            second.map { it.id },
        )
    }

    @Test
    fun `failed op does not add an intermediate step`() {
        val plan = DesignAiPlanner.plan(
            document(),
            0,
            setOf("title"),
            listOf(setTitle, DesignAiOp(op = "remove", target = "missing")),
        )
        assertEquals(1, plan.steps.size)
        assertEquals(1, plan.unapplied)
    }

    @Test
    fun `removed node frame comes from before snapshot`() {
        val plan = DesignAiPlanner.plan(
            document(),
            0,
            setOf("title"),
            listOf(removeCard),
        )
        val removed = plan.changes.single { it.kind == DesignChangeKind.REMOVED }
        assertTrue(removed.frame != null)
        assertEquals(20f, removed.frame!!.left, .01f)
        assertEquals(100f, removed.frame!!.top, .01f)
    }
}
