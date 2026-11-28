package top.qiuli55.flux.mobile.share

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import top.qiuli55.flux.mobile.data.IncomingShare
import top.qiuli55.flux.mobile.data.ShareIntakeBus
import top.qiuli55.flux.mobile.data.ShareIntakeRecovery
import top.qiuli55.flux.mobile.data.SharedAttachmentRef
import top.qiuli55.flux.mobile.data.SharedIntakeStore
import top.qiuli55.flux.mobile.data.materializeSharedAttachments
import top.qiuli55.flux.mobile.data.parseShareIntent

/**
 * 登录墙 / OAuth 回跳导致的进程重建：分享必须先落盘，回到 App 后还能原样恢复。
 *
 * 这里直接驱动「materialize → persist → 清空总线 → restore」这条真实链路，
 * 不依赖 Activity / Compose，验证 P1 的暂存与消费语义。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class ShareIntakePersistenceTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var store: SharedIntakeStore

    @Before
    fun setUp() {
        ShareIntakeBus.resetForTest()
        store = SharedIntakeStore.inDirectory(temporaryFolder.newFolder("share-intake"))
    }

    @After
    fun tearDown() {
        ShareIntakeBus.resetForTest()
    }

    private fun persistRealShare(): IncomingShare {
        val context = RuntimeEnvironment.getApplication()
        val source = File(context.cacheDir, "persist-source-${System.nanoTime()}.txt")
        source.writeText("附件内容")
        val prepared = materializeSharedAttachments(
            context,
            IncomingShare(
                text = "登录墙前的分享",
                attachments = listOf(SharedAttachmentRef(Uri.fromFile(source), "source.txt")),
            ),
        )
        return ShareIntakeRecovery.persist(store, prepared)
    }

    @Test
    fun `restore after process rebuild returns text and copied file uri`() {
        val published = persistRealShare()
        assertNotNull("落盘后应带记录 id", published.recordId)
        assertEquals(1, store.load().size)

        // 模拟进程重建：进程内总线被清空。
        ShareIntakeBus.resetForTest()

        assertEquals(1, ShareIntakeRecovery.restore(store))

        val restored = ShareIntakeBus.pending.value.single().share
        assertEquals("登录墙前的分享", restored.text)
        assertEquals(published.recordId, restored.recordId)
        val attachment = restored.attachments.single()
        assertEquals("file", attachment.uri.scheme)
        assertTrue(attachment.uri.path.orEmpty().contains("shared-inbox"))
        assertEquals("附件内容", File(attachment.uri.path!!).readText())
    }

    @Test
    fun `same record is not delivered twice while it is already pending`() {
        persistRealShare()
        ShareIntakeBus.resetForTest()

        assertEquals(1, ShareIntakeRecovery.restore(store))
        // 同一条记录还在总线里时，Activity 重建再次恢复不能重复投递。
        assertEquals(0, ShareIntakeRecovery.restore(store))
        assertEquals(1, ShareIntakeBus.pending.value.size)
    }

    @Test
    fun `record stays on disk until the share is acknowledged`() {
        val published = persistRealShare()
        ShareIntakeBus.resetForTest()
        ShareIntakeRecovery.restore(store)

        // 只从总线搬到内存队列（登录墙 / 选设备）不算消费：磁盘记录必须还在。
        ShareIntakeBus.consume(ShareIntakeBus.pending.value.map { it.id }.toSet())
        assertEquals(1, store.load().size)

        // 页面真正应用后才删除；重建也不会再恢复。
        ShareIntakeRecovery.acknowledge(store, published.recordId)
        assertTrue(store.load().isEmpty())
        ShareIntakeBus.resetForTest()
        assertEquals(0, ShareIntakeRecovery.restore(store))
        assertTrue(ShareIntakeBus.pending.value.isEmpty())
    }

    @Test
    fun `auth redirect intent alone still restores the earlier share`() {
        persistRealShare()
        ShareIntakeBus.resetForTest()

        // OAuth 回跳：当前 intent 只是 flux://auth，不是分享。
        val auth = Intent(Intent.ACTION_VIEW, Uri.parse("flux://auth?code=abc"))
        assertNull(parseShareIntent(auth))

        // 恢复不依赖当前 intent，磁盘上的分享仍然回来。
        assertEquals(1, ShareIntakeRecovery.restore(store))
        assertEquals("登录墙前的分享", ShareIntakeBus.pending.value.single().share.text)
    }

    @Test
    fun `corrupt manifest is ignored instead of crashing`() {
        val directory = temporaryFolder.newFolder("corrupt")
        val store = SharedIntakeStore.inDirectory(directory)
        File(directory, "pending.json").writeText("{ not json")

        assertTrue(store.load().isEmpty())
        assertEquals(0, ShareIntakeRecovery.restore(store))
    }

    @Test
    fun `remove only deletes the acknowledged record`() {
        val first = ShareIntakeRecovery.persist(store, IncomingShare(text = "第一条"))
        val second = ShareIntakeRecovery.persist(store, IncomingShare(text = "第二条"))

        ShareIntakeRecovery.acknowledge(store, first.recordId)

        assertEquals(listOf("第二条"), store.load().map { it.text })
        assertNotNull(second.recordId)
    }
}
