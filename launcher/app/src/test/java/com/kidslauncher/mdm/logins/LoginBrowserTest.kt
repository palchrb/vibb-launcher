package com.kidslauncher.mdm.logins

import com.kidslauncher.mdm.play.PlayState
import com.kidslauncher.mdm.server.ServerJson
import com.kidslauncher.mdm.server.computeEnforcementPlan
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Design 102 Part B: Chrome only for logins - the typed bundles, the plan's rows, the tightening and the role, over
 * `login_rules.json` (the launcher's alone); design 119: the link gate's order, the login sites' match, the link's
 * target, the window's ends and the role repair. */
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
                managed = row.getValue("managed").jsonPrimitive.boolean,
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
                strings(row["allowlist"]), true, 0, row["override"]?.jsonPrimitive?.boolean == true, controllable, "me.vibb.launcher", null,
                scheduleLocked = row.getValue("locked").jsonPrimitive.boolean,
                playState = PlayState(updateWindow = row["update_window"]?.jsonPrimitive?.boolean == true),
                loginBrowser = CHROME_PACKAGE.takeIf { row.getValue("role_ready").jsonPrimitive.boolean },
                loginWindowOpen = row.getValue("window_open").jsonPrimitive.boolean,
            )
            assertEquals(row.name(), row.getValue("hidden").jsonPrimitive.boolean, CHROME_PACKAGE in plan.hide)
            assertEquals(row.name(), row.getValue("suspended").jsonPrimitive.boolean, CHROME_PACKAGE in plan.suspend)
            assertEquals(row.name(), row.getValue("pinned").jsonPrimitive.boolean, CHROME_PACKAGE in plan.kioskPackages.orEmpty())
        }
    }

    /**
     * A model of how Chrome weighs `URLBlocklist` against `URLAllowlist` (Chrome Enterprise help, "URL blocklist filter
     * format"): of the filters matching the host - a leading dot that host only, else it and its subdomains, `*` any -
     * those with the longest host; scheme and port must match; of those the longest path prefix; an allow wins a tie;
     * no filter at a host, the host loses its left-most label and the search goes on; nothing matches: allowed.
     */
    private fun chromeAllows(blocklist: List<String>, allowlist: List<String>, url: String): Boolean {
        val scheme = url.substringBefore("://", "").lowercase()
        if (scheme != "http" && scheme != "https") {
            // Only `scheme://*` filters name another scheme (`chrome://*`).
            return allowlist.any { it == "$scheme://*" } || blocklist.none { it == "$scheme://*" }
        }
        val u = webUrlOf(url) ?: return false
        data class Filter(val host: String, val exact: Boolean, val port: Int?, val path: String, val allow: Boolean)
        fun parse(entry: String, allow: Boolean): Filter? {
            if (entry == "*") return Filter("", false, null, "", allow)
            if ("://" in entry) return null
            val exact = entry.startsWith('.')
            val rest = entry.removePrefix(".")
            val slash = rest.indexOf('/')
            val authority = if (slash >= 0) rest.substring(0, slash) else rest
            val port = authority.substringAfter(':', "").toIntOrNull()
            return Filter(authority.substringBefore(':').lowercase(), exact, port, if (slash >= 0) rest.substring(slash) else "", allow)
        }
        val filters = blocklist.mapNotNull { parse(it, false) } + allowlist.mapNotNull { parse(it, true) }
        var host = u.host
        while (true) {
            val atHost = filters.filter {
                it.host == host && (!it.exact || host == u.host) && (it.port == null || it.port == u.port) && u.path.startsWith(it.path)
            }
            if (atHost.isNotEmpty()) {
                val longest = atHost.maxOf { it.path.length }
                return atHost.filter { it.path.length == longest }.any { it.allow }
            }
            if (host.isEmpty()) return true
            host = if ('.' in host) host.substringAfter('.') else ""
        }
    }

    /** PLAN "Captchas in the login window": the fixed set in every lockdown, each an entry as the PWA's rule takes it,
     * every captcha frame passes and no Google search or other page of those companies does (read as Chrome reads
     * `URLAllowlist`, [loginLinkMatches]); never a login link for the gate, never in Meldinger's plan. */
    @Test
    fun `the captcha set passes the captchas' frames and nothing else`() {
        val captcha = rules.getValue("captcha").jsonObject
        assertEquals(strings(captcha["paths"]), LOGIN_CAPTCHA_PATHS)
        for (path in LOGIN_CAPTCHA_PATHS) assertEquals(path, path, loginEntry(path))
        for (url in strings(captcha["frames"])!!) assertTrue(url, loginLinkMatchesAny(LOGIN_CAPTCHA_PATHS, url))
        for (url in strings(captcha["blocked"])!!) assertFalse(url, loginLinkMatchesAny(LOGIN_CAPTCHA_PATHS, url))
        // Code QA L1: hCaptcha's and Arkose's own sites stay closed - with both lists, as Chrome weighs them.
        assertEquals(strings(captcha["blocks"]), LOGIN_CAPTCHA_BLOCKS)
        val blocklist = CHROME_BLOCKLIST + LOGIN_CAPTCHA_BLOCKS
        val allowlist = chromeAllowlist(listOf("accounts.example.org"))
        for (row in captcha.getValue("chrome").jsonArray.map { it.jsonObject }) {
            val url = row.getValue("url").jsonPrimitive.content
            assertEquals(url, row.getValue("allowed").jsonPrimitive.boolean, chromeAllows(blocklist, allowlist, url))
        }
        for (url in strings(captcha["frames"])!!) assertTrue(url, chromeAllows(blocklist, allowlist, url))
        for (url in strings(captcha["blocked"])!!) assertFalse(url, chromeAllows(blocklist, allowlist, url))
        // Code QA L2: what is written and compared is the whole allowlist, the parent's sites first, each once.
        assertEquals(listOf("www.recaptcha.net/recaptcha") + (LOGIN_CAPTCHA_PATHS - "www.recaptcha.net/recaptcha"), chromeAllowlist(listOf("www.recaptcha.net/recaptcha")))
        assertTrue(chromeTightened(chromeAllowlist(emptyList()), chromeAllowlist(emptyList()).drop(1)))
        val config = java.io.File(listOf("src/main/java", "app/src/main/java").map { java.io.File(it) }.first { it.isDirectory }, "com/kidslauncher/mdm/server/BrowserConfig.kt").readText()
        assertTrue("written = lockdown?.let { com.kidslauncher.mdm.logins.chromeAllowlist(it) }" in config)
        assertTrue("chromeTightened(old, written)" in config)
        assertTrue(".putString(KEY_WRITTEN, written?.let" in config)
        // Only Chrome's bundle uses it: the gate's login match reads the policy's sites alone.
        val src = listOf("src/main/java", "app/src/main/java").map { java.io.File(it) }.first { it.isDirectory }
        val users = src.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") && "LOGIN_CAPTCHA_PATHS" in it.readText() }
        assertEquals(listOf("LoginBrowser.kt"), users.map { it.name }.toList())
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

    private fun str(e: JsonElement?): String? = e?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
    private fun JsonObject.bool(key: String) = getValue(key).jsonPrimitive.boolean
    private fun JsonObject.long(key: String) = getValue(key).jsonPrimitive.long

    @Test
    fun `the gate's order`() {
        for (row in rows("gate")) {
            val step = linkGatePlan(
                GateInput(
                    locked = row.bool("locked"),
                    paused = row.bool("paused"),
                    pauseBrowser = str(row["pause_browser"]),
                    mapLink = row.bool("map"),
                    roleReady = row.bool("role_ready"),
                    roleHeld = row.bool("role_held"),
                    fromMessages = row.bool("from_messages"),
                    loginMatch = row.bool("login_match"),
                    target = str(row["target"]),
                    freeBrowser = str(row["free_browser"]),
                    updateWindow = row["update_window"]?.jsonPrimitive?.boolean == true,
                ),
            )
            val (name, pkg, refused) = when (step) {
                GateStep.Nothing -> Triple("nothing", null, null)
                GateStep.Map -> Triple("map", null, null)
                GateStep.Login -> Triple("login", null, null)
                is GateStep.Browser -> Triple("browser", step.pkg, null)
                is GateStep.App -> Triple("app", step.pkg, null)
                is GateStep.Note -> Triple("note", null, step.refused)
            }
            assertEquals(row.name(), row.getValue("step").jsonPrimitive.content, name)
            assertEquals(row.name(), str(row["pkg"]), pkg)
            assertEquals(row.name(), row["refused"]?.jsonPrimitive?.boolean, refused)
        }
    }

    @Test
    fun `a login site matches as Chrome's allowlist reads it`() {
        for (row in rows("link_match")) {
            val entry = row.getValue("entry").jsonPrimitive.content
            val url = row.getValue("url").jsonPrimitive.content
            assertEquals("$entry $url", row.bool("match"), loginLinkMatches(entry, url))
        }
        assertTrue(loginLinkMatchesAny(listOf("idp.feide.no", "accounts.google.com"), "https://accounts.google.com/x"))
        assertFalse(loginLinkMatchesAny(emptyList(), "https://accounts.google.com/x"))
    }

    @Test
    fun `the link's target`() {
        for (row in rows("target")) {
            val allowed = strings(row["allowed"])?.toSet()
            val got = linkTargetOf(
                str(row["approved"]), strings(row["declaring"])!!, allowed, row.bool("locked"), "me.vibb.launcher",
                strings(row["browsers"])!!.toSet(),
            )
            assertEquals(row.name(), str(row["target"]), got)
        }
    }

    private fun record(e: JsonElement?): WindowRecord? =
        e?.takeIf { it !is JsonNull }?.let { ServerJson.decodeFromJsonElement(WindowRecord.serializer(), it) }

    @Test
    fun `each end of a login window, the grace and its cancel`() {
        for (row in rows("window")) {
            val event = WindowEvent.valueOf(row.getValue("event").jsonPrimitive.content.uppercase())
            val grace = row["grace_since"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.long
            val step = loginWindowStep(record(row["record"]), grace, event, row.long("now"), row.getValue("boot").jsonPrimitive.int, row.getValue("process").jsonPrimitive.content)
            assertEquals(row.name(), str(row["end"]), step.end)
            assertEquals(row.name(), row["grace_after"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.long, step.graceSince)
        }
        for (row in rows("window_open")) {
            val opened = windowOpenRecord(
                record(row["existing"]), row.long("now"), 1_794_000_100_000L, row.getValue("boot").jsonPrimitive.int,
                row.getValue("process").jsonPrimitive.content, session = true,
            )
            assertEquals(row.name(), row.long("opened_elapsed"), opened.openedElapsed)
        }
        // The timer: the ceiling, or the grace's end when sooner.
        val rec = WindowRecord(1_000_000, 0, 7, "p", true)
        assertEquals(1_300_000L, windowDeadline(rec, null))
        assertEquals(1_100_000L, windowDeadline(rec, 1_040_000))
        assertEquals(1_300_000L, windowDeadline(rec, 1_280_000))
        assertEquals(300_000L, LOGIN_WINDOW_MAX_MS)
        assertEquals(60_000L, LOGIN_TAB_GRACE_MS)
    }

    @Test
    fun `the role repair, at most once an hour and at once after a pause`() {
        val now = 1_794_000_000_000L
        for (row in rows("order")) {
            val ago = row["last_nudge_ago_ms"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.long
            val due = roleRepairDue(
                row.bool("gate_on"), row.bool("just_enabled"), row.bool("chrome_visible"), row.bool("role_held"),
                ago?.let { now - it }, now, row.bool("pause_ended"),
            )
            assertEquals(row.name(), row.bool("due"), due)
        }
    }

    /** M1: in the apply Chrome is hidden first (the package loop, the role's hide step under the window's lock), and
     * only then is the gate switched and the role repaired; the gate's PPA is the re-seat's last (L4). */
    @Test
    fun `the apply hides Chrome before it enables the gate`() {
        val src = listOf("src/main/java", "app/src/main/java").map { java.io.File(it) }.first { it.isDirectory }
        val enforcer = java.io.File(src, "com/kidslauncher/mdm/server/AppEnforcer.kt").readText()
        val apply = enforcer.substringAfter("private fun applyHeld(").substringBefore("\n    }\n")
        val hide = apply.indexOf("LoginWindow.chromeStep(context, dpm, admin, playState.updateWindow)")
        val gate = apply.indexOf("LinkGateRuntime.afterLoop(")
        assertTrue(hide in 0 until gate)
        assertTrue(apply.indexOf("LoginWindow.duringApply(") in 0 until apply.indexOf("loginWindowOpen = loginWindowOpen"))
    }

    /** Code QA M1 and M2: when the apply ends a window, and Play's update window - Chrome out for its update while the
     * gate keeps Android's browser role, so no link reaches Chrome. */
    @Test
    fun `the update window unhides Chrome and the gate keeps the role, a pause's start ends a window`() {
        fun ends(locked: Boolean = false, ready: Boolean = true, gate: Boolean = true, update: Boolean = false, now: Boolean = false, before: Boolean? = false) =
            loginWindowRuleEnds(locked, ready, gate, update, now, before)
        assertFalse(ends())
        assertFalse(ends(before = null))
        assertTrue(ends(now = true, before = false))
        assertTrue(ends(now = true, before = null))
        assertTrue(ends(now = false, before = true))
        assertFalse(ends(now = true, before = true))
        assertTrue(ends(update = true))
        assertTrue(ends(locked = true) && ends(ready = false) && ends(gate = false))
        // The update window: Chrome unhidden with no window open; the gate stays on (Chrome isn't an allowed app), no
        // nudge with Chrome visible (the role service's hash unchanged: the role stays Vibb One's), and the gate opens no
        // window and hands no link to Chrome.
        assertFalse(loginChromeHidden(roleReady = true, windowOpen = false, updateWindow = true))
        assertTrue(loginChromeHidden(roleReady = false, windowOpen = false, updateWindow = true))
        assertTrue(linkGateOn(managed = true, freeBrowsers = listOf(CHROME_PACKAGE).filter { it in setOf("org.example.game") }))
        assertFalse(roleRepairDue(true, false, chromeVisible = true, roleHeld = false, lastNudgeMs = null, nowMs = 0L, pauseEnded = true))
        val login = GateInput(false, false, null, false, true, true, false, true, null, null, updateWindow = true)
        assertEquals(GateStep.Nothing, linkGatePlan(login))
        assertEquals(GateStep.Login, linkGatePlan(login.copy(updateWindow = false)))
    }

    @Test
    fun `the report's stopped hosts and its role state`() {
        val now = 1_794_000_000_000L
        var list = emptyList<RefusedHost>()
        for (i in 1..12) list = refusedWith(list, "s$i.example", now + i)
        assertEquals((3..12).map { "s$it.example" }, list.map { it.host })
        // A host again moves to the end, once.
        list = refusedWith(list, "s5.example", now + 20)
        assertEquals("s5.example", list.last().host)
        assertEquals(1, list.count { it.host == "s5.example" })
        // Seven days later they are gone; one stamped by a later clock is dropped too.
        assertTrue(refusedKept(list, now + REFUSED_MAX_AGE_MS + 100).isEmpty())
        assertEquals(listOf("x.example"), refusedWith(emptyList(), "x.example", now).map { it.host })
        assertTrue(refusedKept(listOf(RefusedHost(now + 5_000, "f.example")), now).isEmpty())
        // A host only: never a path or a query.
        assertEquals("example.com", refusedHostOf("https://Example.COM/secret/path?q=1"))
        assertEquals(null, refusedHostOf("not a url"))
        // The role on but Vibb One not the browser: failed/role; with the gate off nothing changes.
        assertEquals("failed" to "role", reportedRoleState("on", null, browserRole = false))
        assertEquals("on" to null, reportedRoleState("on", null, browserRole = true))
        assertEquals("on" to null, reportedRoleState("on", null, browserRole = null))
        assertEquals("off" to null, reportedRoleState("off", null, browserRole = false))
        // L8: only CORS-safelisted headers reach Chrome.
        assertEquals(
            mapOf("Accept-Language" to "nb"),
            safeHeaders(mapOf("Accept-Language" to "nb", "Authorization" to "Bearer x", "Cookie" to "a=b")),
        )
        // The gate is on while managed and no free browser is allowed (D2).
        assertTrue(linkGateOn(managed = true, freeBrowsers = emptyList()))
        assertFalse(linkGateOn(managed = true, freeBrowsers = listOf("org.mozilla.firefox")))
        assertFalse(linkGateOn(managed = false, freeBrowsers = emptyList()))
        assertTrue(loginChromeHidden(roleReady = true, windowOpen = false))
        assertFalse(loginChromeHidden(roleReady = true, windowOpen = true))
    }
}
