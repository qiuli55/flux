package top.qiuli55.flux.mobile.update

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 更新策略的纯逻辑测试：清单解析、版本比较、跳过与每日一次的限制。
 * 不依赖 Android，网络与安装流程由真机验收覆盖。
 */
class UpdatePolicyTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val sample = """
        {
          "version_code": 11,
          "version_name": "0.3.2",
          "apk_url": "https://flux.app.qiuli55.top/flux-mobile/flux-mobile-0.3.2-vc11.apk",
          "sha256": "abc123",
          "size_bytes": 19000000,
          "published_at": "2026-10-23T19:40:00+08:00",
          "notes": ["新增应用内更新", "修复终端重连"],
          "min_version_code": 10
        }
    """.trimIndent()

    @Test
    fun parses_release_manifest_fields() {
        val manifest = json.decodeFromString<UpdateManifest>(sample)
        assertEquals(11, manifest.versionCode)
        assertEquals("0.3.2", manifest.versionName)
        assertEquals(19_000_000L, manifest.sizeBytes)
        assertEquals(2, manifest.notes.size)
        assertEquals(10, manifest.minVersionCode)
    }

    @Test
    fun ignores_manifest_with_same_or_lower_version() {
        val manifest = json.decodeFromString<UpdateManifest>(sample)
        assertFalse(manifest.isNewerThan(11))
        assertFalse(manifest.isNewerThan(12))
        assertTrue(manifest.isNewerThan(10))
    }

    @Test
    fun rejects_http_apk_url() {
        val plain = json.decodeFromString<UpdateManifest>(sample)
            .copy(apkUrl = "http://flux.app.qiuli55.top/flux-mobile/app.apk")
        assertFalse(plain.isNewerThan(10))
    }

    @Test
    fun skipped_version_does_not_prompt_on_later_day() {
        val manifest = json.decodeFromString<UpdateManifest>(sample)
        assertFalse(
            shouldAutoPrompt(
                manifest = manifest,
                currentCode = 10,
                skippedCode = 11,
                lastPromptDay = "2026-10-22",
                today = "2026-10-23",
            ),
        )
    }

    @Test
    fun prompts_at_most_once_per_day() {
        val manifest = json.decodeFromString<UpdateManifest>(sample)
        assertFalse(
            shouldAutoPrompt(manifest, 10, skippedCode = 0, lastPromptDay = "2026-10-23", today = "2026-10-23"),
        )
        assertTrue(
            shouldAutoPrompt(manifest, 10, skippedCode = 0, lastPromptDay = "2026-10-22", today = "2026-10-23"),
        )
    }

    @Test
    fun forced_update_ignores_skipped_version() {
        val manifest = json.decodeFromString<UpdateManifest>(sample).copy(minVersionCode = 11)
        assertTrue(manifest.isForcedFor(10))
        assertTrue(
            shouldAutoPrompt(manifest, 10, skippedCode = 11, lastPromptDay = "", today = "2026-10-23"),
        )
    }
}
