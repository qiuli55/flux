// 设计稿渲染规范的回归用例。
//
// 这些默认值/颜色解析必须与后端 design_render.py、桌面端 DesignCanvas.cs 完全一致，
// 一旦改动就会让「网页预览」「桌面端预览」「手机端画布」三处出现差异。
package top.qiuli55.flux.mobile.ui.v2.design

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.qiuli55.flux.mobile.data.DesignNode
import top.qiuli55.flux.mobile.data.DesignRadius
import top.qiuli55.flux.mobile.data.DesignShadow
import top.qiuli55.flux.mobile.data.DesignStroke
import top.qiuli55.flux.mobile.data.DesignTextEffect
import top.qiuli55.flux.mobile.data.DesignTextStroke

class DesignSpecTest {

    private val fallback = Color(0xFFFF00FF)

    @Test
    fun `short hex expands`() {
        assertEquals(Color(0xFFAABBCC), DesignSpec.color("#abc", fallback))
    }

    @Test
    fun `six digit hex assumes opaque`() {
        assertEquals(Color(0xFF0F172A), DesignSpec.color("#0F172A", fallback))
    }

    @Test
    fun `eight digit hex is aarrggbb`() {
        val color = DesignSpec.color("#80FF0000", fallback)
        assertEquals(0.502f, color.alpha, 0.01f)
        assertEquals(1f, color.red, 0.01f)
        assertEquals(0f, color.green, 0.01f)
        assertEquals(0f, color.blue, 0.01f)
    }

    @Test
    fun `rgb and rgba parse`() {
        assertEquals(Color(0xFF0891B2), DesignSpec.color("rgb(8, 145, 178)", fallback))
        val rgba = DesignSpec.color("rgba(8,145,178,0.5)", fallback)
        assertEquals(0.5f, rgba.alpha, 0.01f)
        assertEquals(8f / 255f, rgba.red, 0.01f)
    }

    @Test
    fun `empty or invalid falls back`() {
        assertEquals(fallback, DesignSpec.color(null, fallback))
        assertEquals(fallback, DesignSpec.color("  ", fallback))
        assertEquals(fallback, DesignSpec.color("not-a-color", fallback))
    }

    @Test
    fun `gradient single color uses last stop`() {
        val raw = "linear-gradient(180deg, #0891B2, #FF0000)"
        assertEquals(Color(0xFFFF0000), DesignSpec.color(raw, fallback))
    }

    @Test
    fun `gradient brush keeps every stop`() {
        val colors = DesignSpec.tokens("linear-gradient(180deg, #0891B2, #FF0000)")
        assertEquals(listOf("#0891B2", "#FF0000"), colors)
    }

    @Test
    fun `default radius follows type`() {
        assertEquals(24f, DesignSpec.defaultRadius("button", 200f, 48f), 0.01f)
        assertEquals(24f, DesignSpec.defaultRadius("input", 200f, 48f), 0.01f)
        assertEquals(18f, DesignSpec.defaultRadius("card", 200f, 120f), 0.01f)
        assertEquals(18f, DesignSpec.defaultRadius("nav", 390f, 64f), 0.01f)
        assertEquals(30f, DesignSpec.defaultRadius("circle", 60f, 40f), 0.01f)
        assertEquals(0f, DesignSpec.defaultRadius("text", 200f, 20f), 0.01f)
    }

    @Test
    fun `explicit radius wins`() {
        val node = DesignNode(type = "button", w = 200f, h = 48f, radius = DesignRadius.all(8f))
        assertEquals(8f, DesignSpec.radiusOf(node), 0.01f)
    }

    @Test
    fun `partial radius object falls back per corner`() {
        val node = DesignNode(type = "card", w = 200f, h = 120f, radius = DesignRadius(tl = 4f))
        val corners = DesignSpec.cornersOf(node)
        assertEquals(4f, corners.tl, 0.01f)
        assertEquals(18f, corners.tr, 0.01f)
        assertEquals(18f, corners.br, 0.01f)
        assertEquals(18f, corners.bl, 0.01f)
    }

    @Test
    fun `four distinct corners are preserved`() {
        val node = DesignNode(
            type = "card",
            w = 200f,
            h = 120f,
            radius = DesignRadius(0f, 4f, 8f, 12f),
        )
        val corners = DesignSpec.cornersOf(node)
        assertEquals(listOf(0f, 4f, 8f, 12f), listOf(corners.tl, corners.tr, corners.br, corners.bl))
    }

    @Test
    fun `radius clamps to half the short side`() {
        val big = DesignNode(type = "button", w = 200f, h = 48f, radius = DesignRadius.all(999f))
        assertEquals(24f, DesignSpec.radiusOf(big), 0.01f)
        val negative = DesignNode(type = "card", w = 200f, h = 120f, radius = DesignRadius.all(-5f))
        assertEquals(0f, DesignSpec.radiusOf(negative), 0.01f)
    }

    @Test
    fun `new shape types follow the shared defaults`() {
        assertEquals(18f, DesignSpec.defaultRadius("progress", 200f, 36f), 0.01f)
        assertEquals(24f, DesignSpec.defaultRadius("progress", 200f, 48f), 0.01f)
        assertEquals(20f, DesignSpec.defaultRadius("avatar", 40f, 40f), 0.01f)
        assertEquals(DesignSpec.ACCENT, DesignSpec.defaultFill("progress"))
        assertEquals(0.12f, DesignSpec.defaultFill("avatar").alpha, 0.01f)
        assertEquals(Color.White, DesignSpec.defaultTextColor("badge"))
        assertEquals(DesignSpec.ACCENT, DesignSpec.defaultTextColor("chip"))
        assertEquals(DesignSpec.INK, DesignSpec.defaultTextColor("text"))
    }

    @Test
    fun `stroke string means one pixel solid inside`() {
        val spec = DesignSpec.strokeOf(
            DesignNode(type = "card", stroke = DesignStroke(color = "#FF0000")),
        )
        assertNotNull(spec)
        assertEquals(1f, spec!!.width, 0.01f)
        assertFalse(spec.dash)
        assertNull(spec.sides)
        assertTrue(spec.has("top"))
    }

    @Test
    fun `stroke object carries dash and sides`() {
        val spec = DesignSpec.strokeOf(
            DesignNode(
                type = "card",
                stroke = DesignStroke(
                    color = "#FF0000",
                    width = 2f,
                    dash = listOf(4f, 2f),
                    sides = listOf("Top"),
                ),
            ),
        )
        assertNotNull(spec)
        assertEquals(2f, spec!!.width, 0.01f)
        assertTrue(spec.dash)
        assertEquals(setOf("top"), spec.sides)
        assertTrue(spec.has("top"))
        assertFalse(spec.has("bottom"))
    }

    @Test
    fun `stroke needs a valid color and positive width`() {
        assertNull(DesignSpec.strokeOf(DesignNode(type = "card")))
        assertNull(
            DesignSpec.strokeOf(DesignNode(type = "card", stroke = DesignStroke(color = "nope"))),
        )
        assertNull(
            DesignSpec.strokeOf(
                DesignNode(type = "card", stroke = DesignStroke(color = "#FF0000", width = 0f)),
            ),
        )
    }

    @Test
    fun `shadow blur becomes concentric rings large to small`() {
        val rings = DesignSpec.shadowRings(
            DesignNode(type = "card", shadow = DesignShadow(dy = 6f, blur = 10f, color = "#000000")),
        )
        assertTrue(rings.isNotEmpty())
        // 环 i=8 起 alpha 才过可见阈值（0.3×(1−0.8)²=0.012），spread=10×0.8。
        assertEquals(8f, rings.first().spread, 0.01f)
        assertEquals(1f, rings.last().spread, 0.01f)
        assertEquals(6f, rings.first().dy, 0.01f)
        // 大 spread 在下：越靠外 alpha 越低
        assertTrue(rings.first().color.alpha < rings.last().color.alpha)
        assertTrue(rings.zipWithNext().all { (a, b) -> a.spread > b.spread })
    }

    @Test
    fun `sharp shadow stays a single ring`() {
        // 不给颜色时回落到共享默认色（黑 25%），与后端 DEFAULT_SHADOW_ALPHA 一致。
        val rings = DesignSpec.shadowRings(
            DesignNode(type = "card", shadow = DesignShadow(dx = 1f, dy = 2f)),
        )
        assertEquals(1, rings.size)
        assertEquals(0f, rings[0].spread, 0.01f)
        assertEquals(1f, rings[0].dx, 0.01f)
        assertEquals(0.25f, rings[0].color.alpha, 0.01f)
    }

    @Test
    fun `card without shadow gets the shared default shadow`() {
        val rings = DesignSpec.shadowRings(DesignNode(type = "card", w = 200f, h = 120f))
        assertEquals(1, rings.size)
        assertEquals(0f, rings[0].spread, 0.01f)
        assertEquals(DesignSpec.CARD_SHADOW_OFFSET, rings[0].dy, 0.01f)
        assertEquals(DesignSpec.CARD_SHADOW_ALPHA, rings[0].color.alpha, 0.01f)
    }

    @Test
    fun `inset shadow is kept apart from the outer shadow`() {
        val node = DesignNode(
            type = "card",
            shadow = DesignShadow(dy = 2f, color = "#000000"),
            insetShadow = DesignShadow(dy = 2f, blur = 4f, color = "#FFFFFF"),
        )
        assertEquals(1, DesignSpec.shadowRings(node).size)
        val inset = DesignSpec.insetShadowRings(node)
        assertTrue(inset.isNotEmpty())
        assertTrue(inset.all { it.inset })
    }

    @Test
    fun `transparent shadow draws nothing`() {
        val node = DesignNode(
            type = "card",
            shadow = DesignShadow(blur = 8f, color = "transparent"),
        )
        assertTrue(DesignSpec.shadowRings(node).isEmpty())
    }

    @Test
    fun `gradient angle and radial center follow css`() {
        val linear = DesignSpec.gradientOf("linear-gradient(90deg, #FF0000, #0000FF)")
        assertEquals(90f, linear!!.angle, 0.01f)
        assertEquals(2, linear.stops.size)
        assertEquals(0f, linear.stops[0].offset, 0.01f)
        assertEquals(1f, linear.stops[1].offset, 0.01f)

        val direction = DesignSpec.gradientOf("linear-gradient(to right, #FF0000, #0000FF)")
        assertEquals(90f, direction!!.angle, 0.01f)

        val radial = DesignSpec.gradientOf(
            "radial-gradient(circle at right bottom, #FF0000, #0000FF)",
        )
        assertEquals(1f, radial!!.cx, 0.01f)
        assertEquals(1f, radial.cy, 0.01f)

        assertNull(DesignSpec.gradientOf("#FF0000"))
    }

    @Test
    fun `default fill follows type`() {
        assertEquals(DesignSpec.ACCENT, DesignSpec.defaultFill("button"))
        assertEquals(0.12f, DesignSpec.defaultFill("pill").alpha, 0.01f)
        assertEquals(Color.White, DesignSpec.defaultFill("card"))
        assertEquals(DesignSpec.INPUT_FILL, DesignSpec.defaultFill("input"))
        assertEquals(0.08f, DesignSpec.defaultFill("text").alpha, 0.01f)
    }

    @Test
    fun `blank type is text`() {
        assertEquals("text", DesignSpec.typeOf(DesignNode(type = "  ")))
        assertEquals("card", DesignSpec.typeOf(DesignNode(type = "card")))
    }

    @Test
    fun `spec constants match web baseline`() {
        assertEquals(13f, DesignSpec.DEFAULT_FONT_SIZE, 0.01f)
        assertEquals(1.35f, DesignSpec.DEFAULT_LINE_HEIGHT, 0.01f)
        assertEquals(10.5f, DesignSpec.DEFAULT_NAV_FONT_SIZE, 0.01f)
        assertEquals(12f, DesignSpec.DEFAULT_INPUT_PAD, 0.01f)
        assertEquals(3, DesignSpec.MAX_TEXT_LINES)
        assertEquals(64, DesignSpec.TEXT_LINES_LIMIT)
        assertEquals(0.6f, DesignSpec.AUTO_FIT_MIN_SCALE, 0.01f)
        assertEquals(0.5f, DesignSpec.AUTO_FIT_STEP, 0.01f)
        assertEquals(26f, DesignSpec.DEVICE_RADIUS, 0.01f)
        assertEquals(0.5f, DesignSpec.INPUT_PLACEHOLDER_ALPHA, 0.01f)
    }

    @Test
    fun `font family aliases normalize like the backend`() {
        assertEquals("system", DesignSpec.normalizeFamily(null))
        assertEquals("system", DesignSpec.normalizeFamily("  Sans Serif "))
        assertEquals("noto-sans-sc", DesignSpec.normalizeFamily("Noto Sans SC"))
        assertEquals("noto-sans-sc", DesignSpec.normalizeFamily("source_han_sans"))
        assertEquals("noto-serif-sc", DesignSpec.normalizeFamily("Serif"))
        assertEquals("noto-serif-sc", DesignSpec.normalizeFamily("Source Han Serif"))
        assertEquals("inter", DesignSpec.normalizeFamily("Inter"))
        assertEquals("mono", DesignSpec.normalizeFamily("JetBrains Mono"))
        assertEquals("mono", DesignSpec.normalizeFamily("monospace"))
        assertEquals("system", DesignSpec.normalizeFamily("Comic Sans"))
    }

    @Test
    fun `max lines clamp and auto fit flag follow spec`() {
        assertEquals(3, DesignSpec.maxLinesOf(DesignNode()))
        assertEquals(1, DesignSpec.maxLinesOf(DesignNode(maxLines = 0)))
        assertEquals(1, DesignSpec.maxLinesOf(DesignNode(maxLines = -5)))
        assertEquals(64, DesignSpec.maxLinesOf(DesignNode(maxLines = 999)))
        assertEquals(5, DesignSpec.maxLinesOf(DesignNode(maxLines = 5)))
        assertTrue(DesignSpec.autoFitOf(DesignNode(autoFit = true)))
        assertFalse(DesignSpec.autoFitOf(DesignNode()))
    }

    @Test
    fun `fx rings match the shared baseline`() {
        assertEquals(
            listOf(.35f to .55f, .7f to .35f, 1f to .18f),
            DesignSpec.FX_RINGS,
        )
    }

    @Test
    fun `text fill only accepts gradients`() {
        assertNotNull(DesignSpec.textBrushOf("linear-gradient(180deg, #0891B2, #FF0000)"))
        assertNull(DesignSpec.textBrushOf("#0891B2"))
        assertNull(DesignSpec.textBrushOf(null))
        // 单色标仍是渐变（网页渲染成纯色），与后端 parse_gradient 保持一致。
        assertNotNull(DesignSpec.textBrushOf("linear-gradient(180deg, #0891B2)"))
    }

    @Test
    fun `text stroke outside doubles the width`() {
        val outside = DesignSpec.textStrokeOf(
            DesignNode(textStroke = DesignTextStroke(width = 2f, color = "#FFFFFF")),
        )
        assertEquals(4f, outside!!.width, 0.01f)

        val inside = DesignSpec.textStrokeOf(
            DesignNode(textStroke = DesignTextStroke(width = 2f, color = "#FFFFFF", align = "inside")),
        )
        assertEquals(2f, inside!!.width, 0.01f)
    }

    @Test
    fun `text stroke drops invalid input`() {
        assertNull(DesignSpec.textStrokeOf(DesignNode()))
        assertNull(DesignSpec.textStrokeOf(DesignNode(textStroke = DesignTextStroke(width = 2f))))
        assertNull(
            DesignSpec.textStrokeOf(
                DesignNode(textStroke = DesignTextStroke(width = 2f, color = "not-a-color")),
            ),
        )
        assertNull(
            DesignSpec.textStrokeOf(
                DesignNode(textStroke = DesignTextStroke(width = 0f, color = "#FFFFFF")),
            ),
        )
    }

    @Test
    fun `text rings draw shadow first then glow`() {
        val node = DesignNode(
            textShadow = DesignTextEffect(dx = 2f, dy = 3f, blur = 4f, color = "#FF0000"),
            textGlow = DesignTextEffect(dx = 9f, dy = 9f, blur = 6f, color = "#00FF00"),
        )
        val rings = DesignSpec.textRingsOf(node)
        assertEquals(6, rings.size)

        // 每组按半径从大到小：1.0 / 0.7 / 0.35
        assertEquals(listOf(4f, 2.8f, 1.4f), rings.take(3).map { it.spread })
        assertEquals(listOf(6f, 4.2f, 2.1f), rings.drop(3).map { it.spread })

        // 投影带偏移，发光忽略 dx/dy
        assertEquals(2f, rings[0].dx, 0.01f)
        assertEquals(3f, rings[0].dy, 0.01f)
        assertEquals(0f, rings[3].dx, 0.01f)
        assertEquals(0f, rings[3].dy, 0.01f)

        // 环透明度 = color.alpha × intensity × 环倍率
        assertEquals(.18f, rings[0].color.alpha, 0.01f)
        assertEquals(.35f, rings[1].color.alpha, 0.01f)
        assertEquals(.55f, rings[2].color.alpha, 0.01f)
    }

    @Test
    fun `text blur falls back to radius`() {
        val node = DesignNode(
            textGlow = DesignTextEffect(radius = 5f, color = "#FFFFFF"),
        )
        assertEquals(listOf(5f, 3.5f, 1.75f), DesignSpec.textRingsOf(node).map { it.spread })
    }

    @Test
    fun `text effects with invalid color are dropped`() {
        val node = DesignNode(textGlow = DesignTextEffect(blur = 4f, color = "nope"))
        assertTrue(DesignSpec.textRingsOf(node).isEmpty())
        assertTrue(DesignSpec.textRingsOf(DesignNode(textShadow = DesignTextEffect(blur = 4f))).isEmpty())
    }

    @Test
    fun `text rings below the alpha floor are skipped`() {
        // 0x10/255 ≈ 0.0627，再乘 intensity=0.1 与最大环倍率 0.55 也只有 0.0034
        val node = DesignNode(
            textGlow = DesignTextEffect(blur = 4f, intensity = .1f, color = "#10000000"),
        )
        assertTrue(DesignSpec.textRingsOf(node).isEmpty())
    }
}
