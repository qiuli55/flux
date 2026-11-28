package top.qiuli55.flux.mobile.ui.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「粘贴大文本自动作为文件」纯规则（手机 / 桌面 / Web 共用同一套阈值）。
 *
 * 关键边界：9999 字不转；10000 字但只有一行（超长链接）也不转；
 * 10000 字且含换行、模式为「始终作为文件」才转；「始终直接粘贴」永不转。
 */
class V2PasteAsFileTest {

    private val multiline = List(200) { "第 $it 行内容，用于凑够一万字。" }.joinToString("\n")
        .let { it + "x".repeat(LARGE_PASTE_THRESHOLD - it.length) }

    @Test
    fun `threshold and newline are both required`() {
        assertFalse(isLargePaste("x".repeat(LARGE_PASTE_THRESHOLD - 1), PasteTextMode.ALWAYS_FILE))
        assertFalse(isLargePaste("x".repeat(LARGE_PASTE_THRESHOLD), PasteTextMode.ALWAYS_FILE))
        assertFalse(isLargePaste("x".repeat(LARGE_PASTE_THRESHOLD) + "\n", PasteTextMode.ALWAYS_TEXT))
        assertEquals(LARGE_PASTE_THRESHOLD, multiline.length)
        assertTrue(isLargePaste(multiline, PasteTextMode.ALWAYS_FILE))
    }

    @Test
    fun `9999 chars single line stays in the input`() {
        val text = "x".repeat(9_999)
        assertNull(largePasteToAttach("", text, PasteTextMode.ALWAYS_FILE))
    }

    @Test
    fun `10000 chars single line such as a long url stays in the input`() {
        val url = "https://example.com/" + "a".repeat(10_000)
        assertNull(largePasteToAttach("", url, PasteTextMode.ALWAYS_FILE))
    }

    @Test
    fun `10000 chars with newline becomes a file in file mode`() {
        assertEquals(multiline, largePasteToAttach("", multiline, PasteTextMode.ALWAYS_FILE))
    }

    @Test
    fun `always paste mode never converts even above threshold`() {
        assertNull(largePasteToAttach("", multiline, PasteTextMode.ALWAYS_TEXT))
    }

    @Test
    fun `image paste never goes through the text rule`() {
        assertNull(
            largePasteToAttach("", multiline, PasteTextMode.ALWAYS_FILE, hasImage = true),
        )
        assertTrue(isImagePaste(listOf("image/png")))
        assertFalse(isImagePaste(listOf("text/plain")))
        assertFalse(isImagePaste(null))
    }

    @Test
    fun `insertion is detected around existing text`() {
        val before = "开头\n"
        val after = before + multiline + "\n结尾"
        assertEquals(multiline + "\n结尾", pastedSegment(before, after))
        assertNull(pastedSegment(after, before))
    }

    @Test
    fun `typed text is never mistaken for a large paste`() {
        val before = "x".repeat(100)
        assertNull(largePasteToAttach(before, before + "y", PasteTextMode.ALWAYS_FILE))
    }

    @Test
    fun `file name uses a safe first line and falls back to pasted txt`() {
        assertEquals("周报：本周进展.txt", pastedFileName("周报：本周进展\n正文……"))
        assertEquals("a b c.txt", pastedFileName("  a/b\\c\n正文"))
        assertEquals("粘贴.txt", pastedFileName("\n\n   \n"))
        assertFalse(pastedFileName("..\\..\\etc/passwd").contains("/"))
    }

    @Test
    fun `reveal merges into the draft and refuses to exceed the backend limit`() {
        val small = revealPastedText("我的问题", "附件正文")
        assertTrue(small.ok)
        assertEquals("我的问题\n\n附件正文", small.text)

        val tooLong = revealPastedText("x".repeat(COMPOSER_TEXT_MAX), "y")
        assertFalse(tooLong.ok)
        assertTrue(tooLong.error.orEmpty().contains("超过 $COMPOSER_TEXT_MAX 字上限"))
    }

    @Test
    fun `only two paste modes and the default is always file`() {
        assertEquals(2, PasteTextMode.entries.size)
        assertEquals(PasteTextMode.ALWAYS_FILE, PasteTextMode.DEFAULT)
        assertEquals(
            listOf("始终作为文件", "始终直接粘贴"),
            PasteTextMode.entries.map { it.label },
        )
        assertEquals(PasteTextMode.ALWAYS_FILE, PasteTextMode.fromStorage(null))
        assertEquals(PasteTextMode.ALWAYS_TEXT, PasteTextMode.fromStorage("always_text"))
    }
}
