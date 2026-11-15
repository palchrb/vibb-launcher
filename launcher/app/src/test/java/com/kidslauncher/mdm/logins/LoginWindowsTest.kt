package com.kidslauncher.mdm.logins

import com.kidslauncher.mdm.portal.CaptivePortalReport
import com.kidslauncher.mdm.portal.LoginBrowserReport
import com.kidslauncher.mdm.server.ServerJson
import com.kidslauncher.mdm.server.dto.PolicyResponse
import com.kidslauncher.mdm.server.dto.StatusReportRequest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/** Design 102: the shared contract `login_windows.json` (byte-identical with `server/testdata/`, whose
 * `tests::logins` produces the same `policy` rows from the page's saves and posts the same reports). */
class LoginWindowsTest {
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private val withDefaults = kotlinx.serialization.json.Json { encodeDefaults = true; namingStrategy = kotlinx.serialization.json.JsonNamingStrategy.SnakeCase }
    private val contractText = javaClass.classLoader!!.getResource("login_windows.json")!!.readText()
    private val contract = ServerJson.parseToJsonElement(contractText).jsonObject

    private fun policyOf(row: JsonObject): PolicyResponse =
        ServerJson.decodeFromJsonElement(PolicyResponse.serializer(), JsonObject(row.filterKeys { it in setOf("captive_portal", "login_browser") }))

    @Test
    fun `the capabilities are the contract's`() {
        val capabilities = contract.getValue("capabilities").jsonObject
        assertEquals(CAPTIVE_PORTAL_CAPABILITY, capabilities.getValue("captive_portal").jsonPrimitive.content)
        assertEquals(LOGIN_BROWSER_CAPABILITY, capabilities.getValue("login_browser").jsonPrimitive.content)
    }

    @Test
    fun `every policy row decodes, and an older server's means on with the code`() {
        for (element in contract.getValue("policy").jsonArray) {
            val row = element.jsonObject
            val policy = policyOf(row)
            val name = row.getValue("name").jsonPrimitive.content
            // Every key the server sends, exactly (a default-valued field encoded too).
            assertEquals(name, row.getValue("captive_portal"), withDefaults.encodeToJsonElement(CaptivePortalPolicy.serializer(), portalPolicyOf(policy)))
            val browser = row.getValue("login_browser")
            if (browser is JsonNull) {
                assertNull(name, policy.loginBrowser)
                assertNull(name, loginHostsOf(policy.loginBrowser))
            } else {
                assertEquals(name, CHROME_PACKAGE, policy.loginBrowser!!.packageName)
                val hosts = browser.jsonObject.getValue("hosts").jsonArray.map { it.jsonPrimitive.content }
                assertEquals(name, hosts, policy.loginBrowser!!.hosts)
                assertEquals(name, hosts, loginHostsOf(policy.loginBrowser))
            }
        }
        val older = contract.getValue("older_server").jsonObject
        val policy = ServerJson.decodeFromJsonElement(PolicyResponse.serializer(), older.getValue("policy"))
        assertEquals(
            ServerJson.decodeFromJsonElement(CaptivePortalPolicy.serializer(), older.getValue("captive_portal")),
            portalPolicyOf(policy),
        )
        assertEquals(true, portalPolicyOf(policy).requireCode)
        assertNull(policy.loginBrowser)
    }

    @Test
    fun `the reports encode exactly as the contract's`() {
        for (name in listOf("report", "report_off")) {
            val row = contract.getValue(name).jsonObject
            val portal = ServerJson.decodeFromJsonElement(CaptivePortalReport.serializer(), row.getValue("captive_portal"))
            val browser = ServerJson.decodeFromJsonElement(LoginBrowserReport.serializer(), row.getValue("login_browser"))
            val body = ServerJson.encodeToJsonElement(
                StatusReportRequest.serializer(),
                StatusReportRequest(lockReason = "none", kioskEngaged = true, captivePortal = portal, loginBrowser = browser),
            ).jsonObject
            assertEquals(name, row.getValue("captive_portal"), body.getValue("captive_portal"))
            assertEquals(name, row.getValue("login_browser"), body.getValue("login_browser"))
        }
    }

    @Test
    fun `the entries are the server's`() {
        for (element in contract.getValue("entries").jsonArray) {
            val row = element.jsonObject
            val input = row.getValue("in").jsonPrimitive.content
            assertEquals(input, row.getValue("entry").jsonPrimitive.contentOrNull, loginEntry(input))
        }
        // An invalid site from a policy is dropped, never written to Chrome.
        assertEquals(listOf("school.example"), loginHostsOf(LoginBrowserPolicy(CHROME_PACKAGE, listOf("*", "school.example", "School.Example"))))
    }

    @Test
    fun `the two copies of the contract are identical`() {
        val serverCopy = listOf("../../server/testdata/login_windows.json", "../server/testdata/login_windows.json").map(::File).first { it.exists() }
        assertEquals(serverCopy.readText(), contractText)
    }
}
