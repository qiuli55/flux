package top.qiuli55.flux.mobile.data

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * `webSession` 必须按账号云约定发 `POST /api/auth/web-session` 并带 Bearer。
 *
 * 用 JDK/Android 都自带的 ServerSocket 起一个本地假账号云（不引入新测试依赖），
 * 断言方法、路径、Authorization 头和空 JSON body。
 */
class CloudApiWebSessionTest {
    private lateinit var server: ServerSocket
    private lateinit var worker: Thread
    private val method = AtomicReference<String>()
    private val path = AtomicReference<String>()
    private val authorization = AtomicReference<String>()
    private val requestBody = AtomicReference<String>()

    @Before
    fun startFakeCloud() {
        server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        worker = thread(name = "fake-cloud") {
            server.accept().use { socket ->
                socket.soTimeout = 5_000
                val input = socket.getInputStream()

                val head = StringBuilder()
                while (!head.endsWith("\r\n\r\n")) {
                    val b = input.read()
                    if (b < 0) return@use
                    head.append(b.toChar())
                }
                val lines = head.split("\r\n")
                val requestLine = lines.first().split(" ")
                method.set(requestLine.getOrElse(0) { "" })
                path.set(requestLine.getOrElse(1) { "" })
                val headers = lines.drop(1)
                    .filter { it.contains(':') }
                    .associate {
                        it.substringBefore(':').trim().lowercase() to
                            it.substringAfter(':').trim()
                    }
                authorization.set(headers["authorization"])

                val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
                val bodyBytes = ByteArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = input.read(bodyBytes, read, contentLength - read)
                    if (n < 0) break
                    read += n
                }
                requestBody.set(String(bodyBytes, 0, read, Charsets.UTF_8))

                val payload = """
                    {"url":"https://flux.qiuli55.top/api/auth/web-session/enter?code=abc","expires_in":60}
                """.trimIndent().toByteArray(Charsets.UTF_8)
                val out = socket.getOutputStream()
                out.write(
                    (
                        "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: application/json\r\n" +
                            "Content-Length: ${payload.size}\r\n" +
                            "Connection: close\r\n\r\n"
                        ).toByteArray(Charsets.US_ASCII),
                )
                out.write(payload)
                out.flush()
            }
        }
    }

    @After
    fun stopFakeCloud() {
        server.close()
        worker.join(5_000)
    }

    @Test
    fun `webSession posts with bearer token and empty json body`() = runBlocking {
        val api = CloudApi("http://127.0.0.1:${server.localPort}")

        val url = api.webSession("access-token-123")

        worker.join(5_000)
        assertEquals(
            "https://flux.qiuli55.top/api/auth/web-session/enter?code=abc",
            url,
        )
        assertEquals("POST", method.get())
        assertEquals("/api/auth/web-session", path.get())
        assertEquals("Bearer access-token-123", authorization.get())
        assertEquals("{}", requestBody.get().trim())
    }
}
