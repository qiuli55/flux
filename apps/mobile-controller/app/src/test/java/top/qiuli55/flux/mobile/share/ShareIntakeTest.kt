package top.qiuli55.flux.mobile.share

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import top.qiuli55.flux.mobile.data.htmlToPlainText
import top.qiuli55.flux.mobile.data.parseShareIntent

/**
 * 系统分享解析：文字 / 链接 / 单图 / 多文件 / 混合分享都要稳定落到输入草稿。
 *
 * 用 Robolectric 跑真实的 Intent / ClipData，避免自己编一套假的 extras 结构。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class ShareIntakeTest {

    private fun send(type: String?, text: String? = null): Intent =
        Intent(Intent.ACTION_SEND).apply {
            this.type = type
            if (text != null) putExtra(Intent.EXTRA_TEXT, text)
        }

    @Test
    fun `send plain text becomes draft text`() {
        val share = parseShareIntent(send("text/plain", "帮我总结这段话"))
        assertNotNull(share)
        assertEquals("帮我总结这段话", share!!.text)
        assertTrue(share.attachments.isEmpty())
    }

    @Test
    fun `send link keeps the url verbatim`() {
        val url = "https://example.com/a?b=1&c=2"
        val share = parseShareIntent(send("text/plain", url))
        assertEquals(url, share?.text)
    }

    @Test
    fun `send single image becomes one attachment`() {
        val uri = Uri.parse("content://media/external/images/1")
        val intent = send("image/png").apply {
            putExtra(Intent.EXTRA_STREAM, uri)
        }
        val share = parseShareIntent(intent)
        assertEquals(1, share?.attachments?.size)
        assertEquals(uri, share?.attachments?.first()?.uri)
        assertTrue(share!!.text.isBlank())
    }

    @Test
    fun `send multiple files keeps every readable stream`() {
        val first = Uri.parse("content://docs/1")
        val second = Uri.parse("content://docs/2")
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "application/pdf"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(first, second))
        }
        val share = parseShareIntent(intent)
        assertEquals(listOf(first, second), share?.attachments?.map { it.uri })
    }

    @Test
    fun `mixed text and files fill both fields`() {
        val file = Uri.parse("content://docs/report")
        val intent = send("text/plain", "看看这份报告").apply {
            putExtra(Intent.EXTRA_STREAM, file)
        }
        val share = parseShareIntent(intent)
        assertEquals("看看这份报告", share?.text)
        assertEquals(listOf(file), share?.attachments?.map { it.uri })
    }

    @Test
    fun `empty intent is ignored`() {
        assertNull(parseShareIntent(null))
        assertNull(parseShareIntent(Intent()))
    }

    @Test
    fun `main and view intents are not treated as shares`() {
        assertNull(parseShareIntent(Intent(Intent.ACTION_MAIN)))
        assertNull(
            parseShareIntent(
                Intent(Intent.ACTION_VIEW, Uri.parse("flux://auth?code=abc")),
            ),
        )
    }

    @Test
    fun `subject and html are merged without duplicating the same text`() {
        val intent = send("text/html", "正文").apply {
            putExtra(Intent.EXTRA_SUBJECT, "标题")
            putExtra(
                Intent.EXTRA_HTML_TEXT,
                "<p>正文</p><p>第二段 &amp; 更多</p>",
            )
        }
        val share = parseShareIntent(intent)
        val text = share?.text.orEmpty()
        assertTrue(text.contains("标题"))
        assertTrue(text.contains("正文"))
        assertTrue(text.contains("第二段 & 更多"))
        // 「正文」只保留一次
        assertEquals(1, Regex("正文").findAll(text).count())
    }

    @Test
    fun `clip data text is read when extras are empty`() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            clipData = ClipData.newPlainText("标题", "来自 ClipData 的内容")
        }
        assertEquals("来自 ClipData 的内容", parseShareIntent(intent)?.text)
    }

    @Test
    fun `http uri list entries become text not attachments`() {
        val uri = Uri.parse("https://example.com/page")
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/uri-list"
            clipData = ClipData.newRawUri("链接", uri)
        }
        val share = parseShareIntent(intent)
        assertEquals("https://example.com/page", share?.text)
        assertTrue(share!!.attachments.isEmpty())
    }

    @Test
    fun `blank stream is reported but other items survive`() {
        val file = Uri.parse("content://docs/ok")
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(Uri.EMPTY, file))
        }
        val share = parseShareIntent(intent)
        assertEquals(listOf(file), share?.attachments?.map { it.uri })
        assertTrue(share!!.errors.isNotEmpty())
    }

    @Test
    fun `same uri in stream and clip is only attached once`() {
        val file = Uri.parse("content://docs/dup")
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, file)
            clipData = ClipData.newRawUri("dup", file)
        }
        assertEquals(1, parseShareIntent(intent)?.attachments?.size)
    }

    @Test
    fun `html tags are stripped and entities decoded`() {
        val text = htmlToPlainText("<div>你好<br/>世界 &lt;Flux&gt;</div>")
        assertFalse(text.contains("<div>"))
        assertTrue(text.contains("你好"))
        assertTrue(text.contains("世界 <Flux>"))
    }
}
