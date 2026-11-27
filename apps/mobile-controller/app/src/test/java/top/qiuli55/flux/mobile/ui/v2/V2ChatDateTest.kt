package top.qiuli55.flux.mobile.ui.v2

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

/** 对话日期分隔：按设备本地时区显示今天 / 昨天 / 具体日期。 */
class V2ChatDateTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = Instant.parse("2026-11-27T10:00:00Z") // 上海 2026-11-27 18:00

    @Test
    fun `same local day is today`() {
        assertEquals("今天", chatDateLabel(Instant.parse("2026-11-27T02:00:00Z").epochSecond, now, zone))
    }

    @Test
    fun `previous local day is yesterday`() {
        // UTC 还是 26 日 15:00，上海已经是 26 日 23:00，属于昨天。
        assertEquals("昨天", chatDateLabel(Instant.parse("2026-11-26T15:00:00Z").epochSecond, now, zone))
    }

    @Test
    fun `older date in same year shows month and day`() {
        assertEquals("10月20日", chatDateLabel(Instant.parse("2026-10-20T03:00:00Z").epochSecond, now, zone))
    }

    @Test
    fun `different year includes the year`() {
        assertEquals("2025年12月31日", chatDateLabel(Instant.parse("2025-12-31T03:00:00Z").epochSecond, now, zone))
    }

    @Test
    fun `missing timestamp falls back to today`() {
        assertEquals("今天", chatDateLabel(null, now, zone))
    }
}
