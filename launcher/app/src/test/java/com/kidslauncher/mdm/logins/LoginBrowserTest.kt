package com.kidslauncher.mdm.logins

import com.kidslauncher.mdm.server.ServerJson
import com.kidslauncher.mdm.server.computeEnforcementPlan
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Design 102 Part B: Chrome only for logins - the typed bundles, the plan's rows, the tightening and the role, over
 * `login_rules.json` (the launcher's alone). */
class LoginBrowserTest {
    private val rules = ServerJson.parseToJsonElement(javaClass.classLoader!!.getResource("login_rules.json")!!.readText()).jsonObject

    private fun rows(name: String) = rules.getValue(name).jsonArray.map { it.jsonObject }
    private fun JsonObject.name() = getValue("name").jsonPrimitive.content
    private fun strings(e: JsonElement?): List<String>? = e?.takeIf { it !is JsonNull }?.jsonArray?.map { it.jsonPrimitive.content }

    /** A plan value in its Bundle type, as the rows write it: `{"string": …}`, `{"int": …}` or `{"bool": …}`. */
    private fun typed(value: Any): JsonObject = when (value) {
        is String -> JsonObject(mapOf("string" to JsonPrimitive(value)))
        is Int -> JsonObject(mapOf("int" to JsonPrimitive(value)))
        is Boolean -> JsonObject(mapOf("bool" to JsonPrimitive(value)))
        else -> error("untyped $value")
    }

    @Test
    fun `the package is Chrome's and the bundles are typed as Chrome's schema`() {
        assertEquals("com.android.chrome", CHROME_PACKAGE)
        for (row in rows("bundles")) {
            val plan = browserConfigPlan(
                filterOn = row.getValue("filter_on").jsonPrimitive.boolean,
                lockdownHosts = strings(row["lockdown"]),
                installed = strings(row["installed"])!!.toSet(),
            )
            val got = JsonObject(plan.mapValues { (_, values) -> JsonObject(values.mapValues { typed(it.value) }) })
            assertEquals(row.name(), row.getValue("expect"), got)
        }
    }

    @Test
    fun `the plan's rows`() {
        val controllable = listOf("me.vibb.launcher", CHROME_PACKAGE, "org.example.game")
        for (row in rows("plans")) {
            val plan = computeEnforcementPlan(
                strings(row["allowlist"]), true, 0, false, controllable, "me.vibb.launcher", null,
                scheduleLocked = row.getValue("locked").jsonPrimitive.boolean,
                loginBrowser = CHROME_PACKAGE.takeIf { row.getValue("role_ready").jsonPrimitive.boolean },
            )
            assertEquals(row.name(), row.getValue("hidden").jsonPrimitive.boolean, CHROME_PACKAGE in plan.hide)
            assertEquals(row.name(), row.getValue("suspended").jsonPrimitive.boolean, CHROME_PACKAGE in plan.suspend)
            assertEquals(row.name(), row.getValue("pinned").jsonPrimitive.boolean, CHROME_PACKAGE in plan.kioskPackages.orEmpty())
        }
    }

    @Test
    fun `a tightening is a set difference or the lockdown coming back`() {
        for (row in rows("tighten")) {
            assertEquals(row.name(), row.getValue("tightened").jsonPrimitive.boolean, chromeTightened(strings(row["old"]), strings(row["new"])))
        }
    }

    @Test
    fun `the role from the policy and the allowlist, and never on the lists`() {
        for (row in rows("roles")) {
            val policy = row["policy"]?.takeIf { it !is JsonNull }?.let { ServerJson.decodeFromJsonElement(LoginBrowserPolicy.serializer(), it) }
            val role = loginRoleOf(policy, strings(row["allowlist"]))
            val name = when (role) {
                LoginRole.Off -> "off"
                LoginRole.WrongPackage -> "wrong_package"
                is LoginRole.On -> "on"
            }
            assertEquals(row.name(), row.getValue("role").jsonPrimitive.content, name)
            assertEquals(row.name(), strings(row["hosts"]), (role as? LoginRole.On)?.hosts)
        }
        assertFalse(loginRoleListable(CHROME_PACKAGE, CHROME_PACKAGE))
        assertTrue(loginRoleListable(CHROME_PACKAGE, null))
        assertTrue(loginRoleListable("org.example.game", CHROME_PACKAGE))
        // D13: cleared once, and again only after a free phase forgot the record.
        assertTrue(chromeClearDue(roleOn = true, cleared = false))
        assertFalse(chromeClearDue(roleOn = true, cleared = true))
        assertFalse(chromeClearDue(roleOn = false, cleared = false))
    }
}
