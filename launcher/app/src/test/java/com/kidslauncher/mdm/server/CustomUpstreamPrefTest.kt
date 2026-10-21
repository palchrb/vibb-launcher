package com.kidslauncher.mdm.server

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Design 36 §5.1 and §7: the pref `dns_upstream_custom` (what is written, and that only an accepted
 * policy writes it, with the provider, in one `commit()`), and that nothing logs a server.
 */
class CustomUpstreamPrefTest {
    private val src = listOf("src", "app/src").map(::File).first { File(it, "main/java").isDirectory }
    private val serverDir = File(src, "main/java/com/kidslauncher/mdm/server")
    private fun source(name: String) = File(serverDir, name).readText()

    private val valid = ServerJson.parseToJsonElement(
        """{"kind": "doh", "url": "https://dns.example/dns-query/abc123", "rev": 4,
            "backup": {"kind": "dot", "host": "192.0.2.53", "port": 853}}""",
    )

    @Test
    fun `a valid pair is stored as sent and read back, anything else removes it`() {
        val stored = customUpstreamPref(valid)
        assertNotNull(stored)
        assertEquals(customUpstreamFrom(valid), customUpstreamFromPref(stored))
        assertNull(customUpstreamPref(null))
        assertNull(customUpstreamPref(ServerJson.parseToJsonElement("""{"kind": "doh", "url": "https://dns.example", "rev": 4}""")))
        assertNull(customUpstreamFromPref(null))
        assertNull(customUpstreamFromPref("{not json"))
    }

    @Test
    fun `only the accepted policy's commit writes the servers, and the VPN learns them after it`() {
        val worker = source("MdmSyncWorker.kt")
        val store = worker.substringAfter("private fun storeAcceptedPolicy(").substringBefore("\n}\n")
        // Both DNS prefs go into the editor that is committed once with the policy.
        for (key in listOf("keys.dnsUpstreamProvider()", "keys.dnsUpstreamCustom()")) {
            assertEquals(key, 1, Regex(Regex.escape("editor.putString($key")).findAll(store).count())
        }
        assertEquals(1, Regex(Regex.escape("editor.remove(keys.dnsUpstreamCustom())")).findAll(store).count())
        // Nowhere else: no generated setter, no other writer.
        val writers = productionSources().filter { file ->
            val text = file.readText()
            Regex("""\.dnsUpstream(Custom|Provider)\(\s*[^)\s]""").containsMatchIn(text) ||
                (file.name != "MdmSyncWorker.kt" && "keys().dnsUpstreamCustom()" in text)
        }
        assertEquals(emptyList<File>(), writers)
        // The running VPN takes them only from an accepted policy, after the commit.
        val accepted = worker.substringAfter("if (freshPolicy != null) {\n        storeAcceptedPolicy(context, freshPolicy)")
            .substringBefore("\n    }\n")
        org.junit.Assert.assertTrue("DnsFilterEngine.setCustomUpstream(" in accepted)
        assertEquals(1, productionSources().sumOf { Regex("""setCustomUpstream\(""").findAll(it.readText()).count() } - 1)
    }

    @Test
    fun `nothing logs a server - kinds, reasons, counts and exception classes only`() {
        // retryMs/nextMs: the VPN's tunnel-retry delays (qa-lfix-notifs-code #1), numbers only.
        val allowed = Regex("""^(mode|isPrimary|label|retryMs|nextMs|[\w.?]*\.(kind|wire|ok|failures|periods|size|simpleName))$""")
        val offenders = mutableListOf<String>()
        val files = listOf("CustomDns.kt", "UpstreamTransport.kt", "BootstrapDns.kt", "UpstreamChooser.kt", "CustomUpstream.kt", "KidVpnService.kt", "PublicResolver.kt")
        for (name in files) {
            val text = source(name)
            for (call in Regex("""\b(Log\.[a-z]|log)\(""").findAll(text)) {
                val args = argumentsAt(text, call.range.last + 1)
                val message = args.firstOrNull { it.trimStart().startsWith("\"") } ?: continue
                for (template in Regex("""\$\{([^}]*)}|\$(\w+)""").findAll(message)) {
                    val expr = (template.groupValues[1].ifEmpty { template.groupValues[2] }).substringBefore(" ?:").trim()
                    if (!allowed.matches(expr)) offenders += "$name: \$$expr"
                }
                val throwable = args.size > (if (call.value.startsWith("Log")) 2 else 1)
                if (throwable && name != "KidVpnService.kt") offenders += "$name: a throwable in ${call.value}"
            }
        }
        assertEquals(emptyList<String>(), offenders)
        // The targets' own toStrings are redacted.
        val config = customUpstreamFrom(valid)!!
        assertFalse(config.toString().contains("dns.example") || config.toString().contains("192.0.2.53"))
        // The provider's old log of the failing upstream with the exception (a message may name it) is gone.
        assertFalse(Regex("""Log\.\w\([^)]*,\s*e\)""").containsMatchIn(source("UpstreamTransport.kt") + source("CustomDns.kt")))
    }

    /** The top-level arguments of the call whose parenthesis opened just before [start]. */
    private fun argumentsAt(text: String, start: Int): List<String> {
        val args = mutableListOf<String>()
        var depth = 0
        var inString = false
        val current = StringBuilder()
        var i = start
        while (i < text.length) {
            val c = text[i]
            when {
                inString -> { if (c == '"' && text[i - 1] != '\\') inString = false }
                c == '"' -> inString = true
                c == '(' || c == '{' || c == '[' -> depth++
                c == ')' || c == '}' || c == ']' -> {
                    if (depth == 0) { args += current.toString(); return args }
                    depth--
                }
                c == ',' && depth == 0 -> { args += current.toString(); current.clear(); i++; continue }
            }
            current.append(c)
            i++
        }
        return args
    }

    private fun productionSources(): List<File> = src.listFiles()!!
        .filter { it.isDirectory && !it.name.startsWith("test") && !it.name.startsWith("androidTest") }
        .flatMap { set -> set.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }.toList() }
}
