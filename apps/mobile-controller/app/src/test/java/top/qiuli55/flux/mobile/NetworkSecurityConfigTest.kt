package top.qiuli55.flux.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 回归保护：debug 源集的 network_security_config.xml 会整体覆盖主配置。
 *
 * debug APK 是实际分发的安装包（带分发证书），所以它必须包含主配置里的所有
 * 明文域名；只允许额外增加本地调试域名，绝不能因为本地联调把生产域名删掉。
 */
class NetworkSecurityConfigTest {

    private fun moduleDir(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, "src/main/res/xml/network_security_config.xml").isFile) return dir
            dir = dir.parentFile
        }
        error("cannot locate app module from ${System.getProperty("user.dir")}")
    }

    private fun domains(file: File): Set<String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("domain")
        val out = linkedSetOf<String>()
        for (i in 0 until nodes.length) {
            val element = nodes.item(i) as Element
            val text = element.textContent.trim()
            if (text.isNotEmpty()) out += text
        }
        return out
    }

    private fun cleartextConfigs(file: File): List<Element> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("domain-config")
        return (0 until nodes.length).map { nodes.item(it) as Element }.filter {
            it.getAttribute("cleartextTrafficPermitted") == "true"
        }
    }

    @Test
    fun `debug config keeps every production domain from main config`() {
        val root = moduleDir()
        val main = File(root, "src/main/res/xml/network_security_config.xml")
        val debug = File(root, "src/debug/res/xml/network_security_config.xml")
        assertTrue("debug override must exist at $debug", debug.isFile)

        val mainDomains = domains(main)
        val debugDomains = domains(debug)
        assertTrue("main config should list at least one domain", mainDomains.isNotEmpty())

        val missing = mainDomains - debugDomains
        assertTrue(
            "debug network config drops production domains: $missing; debug=$debugDomains",
            missing.isEmpty(),
        )
        assertEquals(
            "debug config should not silently replace production hosts",
            emptySet<String>(),
            missing,
        )
    }

    @Test
    fun `debug config adds the local audit hosts`() {
        val debug = File(moduleDir(), "src/debug/res/xml/network_security_config.xml")
        val debugDomains = domains(debug)
        assertTrue("10.0.2.2 missing", "10.0.2.2" in debugDomains)
        assertTrue("127.0.0.1 missing", "127.0.0.1" in debugDomains)
        assertTrue("localhost missing", "localhost" in debugDomains)
    }

    @Test
    fun `main config does not permit cleartext globally`() {
        val main = File(moduleDir(), "src/main/res/xml/network_security_config.xml")
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(main)
        val base = doc.getElementsByTagName("base-config")
        for (i in 0 until base.length) {
            val element = base.item(i) as Element
            assertFalse(
                "main base-config must not enable cleartext globally",
                element.getAttribute("cleartextTrafficPermitted") == "true",
            )
        }
        // 主配置里的明文只允许出现在列出的内网域名上。
        val cleartext = cleartextConfigs(main)
        assertEquals("main config cleartext domain-config count", 1, cleartext.size)
        assertTrue(domains(main).contains("159.75.222.60"))
        assertTrue(domains(main).contains("115.159.125.254"))
    }
}
