package top.qiuli55.flux.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 账号云错误体解析：必须把服务端中文原样带到界面。
 *
 * 覆盖 FastAPI 的字符串 detail、校验数组 detail 和通用 message 三种形态。
 */
class CloudApiErrorTest {

    @Test
    fun `detail string keeps the server chinese message`() {
        val error = parseCloudError("""{"detail":"验证码无效或已过期"}""", 400)

        assertEquals("验证码无效或已过期", error.message)
    }

    @Test
    fun `detail array keeps chinese validation messages`() {
        val error = parseCloudError(
            """{"detail":[{"loc":["body","password"],"msg":"密码至少 8 位","type":"value_error"}]}""",
            422,
        )

        assertTrue(error.message.contains("密码至少 8 位"))
    }

    @Test
    fun `conflict detail is surfaced for duplicate registration`() {
        val error = parseCloudError("""{"detail":"该邮箱已被注册"}""", 409)

        assertEquals("该邮箱已被注册", error.message)
        assertEquals("conflict", error.code)
    }

    @Test
    fun `message field is accepted when there is no detail`() {
        val error = parseCloudError("""{"message":"邮箱或密码错误"}""", 401)

        assertEquals("邮箱或密码错误", error.message)
        assertEquals("unauthenticated", error.code)
    }

    @Test
    fun `empty body falls back to a readable chinese message`() {
        val error = parseCloudError("", 500)

        assertTrue(error.message.contains("HTTP 500"))
    }
}
