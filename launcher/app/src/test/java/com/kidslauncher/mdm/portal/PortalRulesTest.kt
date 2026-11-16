package com.kidslauncher.mdm.portal

import com.kidslauncher.mdm.server.ServerJson
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/** Design 102 Part A: the window's and the entry's rules over `login_rules.json` (the launcher's alone). */
class PortalRulesTest {
    private val rules = ServerJson.parseToJsonElement(javaClass.classLoader!!.getResource("login_rules.json")!!.readText()).jsonObject

    private fun rows(name: String) = rules.getValue(name).jsonArray.map { it.jsonObject }
    private fun JsonObject.str(key: String): String? = get(key)?.let { if (it is JsonNull) null else it.jsonPrimitive.contentOrNull }
    private fun JsonObject.bool(key: String) = getValue(key).jsonPrimitive.boolean
    private fun JsonObject.name() = str("name") ?: toString()

    @Test
    fun `navigation`() {
        for (row in rows("navigation")) {
            val sites = row.getValue("sites").jsonObject
            val chain = row["chain"]?.takeIf { it !is JsonNull }?.jsonObject?.let {
                PortalChain(ChainStart.of(it.str("start")!!), it.str("from"), it.bool("from_portal"), it.str("target"))
            }
            val step = row.getValue("step").jsonObject
            val outcome = portalNavigation(
                kind = PortalKind.of(row.str("kind"))!!,
                pause = row.bool("pause"),
                recordedSite = row.str("recorded"),
                sites = PortalSites(
                    portal = sites.getValue("portal").jsonArray.map { it.jsonPrimitive.content }.toSet(),
                    current = sites.str("current"),
                    settled = sites.bool("settled"),
                ),
                chain = chain,
                step = NavStep(StepKind.of(step.str("kind")!!), step.str("site")!!),
            )
            assertEquals(row.name(), row.bool("allow"), outcome.allow)
            assertEquals(row.name(), row.getValue("portal_after").jsonArray.map { it.jsonPrimitive.content }.toSet(), outcome.sites.portal)
        }
    }

    @Test
    fun `sites and schemes`() {
        for (row in rows("sites")) assertEquals(row.str("host"), row.str("site"), siteOf(row.str("host")!!))
        for (row in rows("schemes")) assertEquals(row.str("scheme"), row.str("verdict"), schemeVerdict(row.str("scheme")).name.lowercase())
    }

    @Test
    fun `approve`() {
        for (row in rows("approve")) {
            val input = ApproveInput(
                kind = PortalKind.of(row.str("kind"))!!,
                networkOk = row.bool("network_ok"),
                passes = row.bool("passes"),
                locked = row.bool("locked"),
                windowOpen = row.bool("window_open"),
                requireCode = row.bool("require_code"),
                pause = row.bool("pause"),
                grantLeft = row.bool("grant_left"),
                budgetLeft = row.bool("budget_left"),
            )
            val result = when (val a = portalApprove(input, PinOutcome.of(row.str("pin")))) {
                Approval.Keypad -> "keypad"
                Approval.Forward -> "forward"
                is Approval.Open -> "open:${a.approved}"
                is Approval.Refuse -> "refuse:${a.reason}"
            }
            assertEquals(row.name(), row.str("result"), result)
        }
    }

    @Test
    fun `frames and passes`() {
        for (row in rows("frames")) assertEquals(row.toString(), row.bool("document"), frameDocument(row.str("accept"), row.bool("main")))
        fun answer(row: JsonObject, key: String) = row[key]?.takeIf { it !is JsonNull }?.jsonObject?.let {
            CheckAnswer(it.getValue("status").jsonPrimitive.int, it.bool("empty"))
        }
        for (row in rows("passes")) {
            assertEquals(row.name(), row.bool("pass"), portalPasses(answer(row, "google"), answer(row, "cloudflare"), answer(row, "server")))
        }
    }

    @Test
    fun `close`() {
        for (row in rows("close")) {
            val input = CloseInput(
                kind = PortalKind.of(row.str("kind"))!!,
                validated = row.bool("validated"),
                passes = row.bool("passes"),
                elapsedMs = row.getValue("elapsed_ms").jsonPrimitive.long,
                minutes = 10,
                codeFree = row.bool("code_free"),
                budgetLeftMs = row.getValue("budget_left_ms").jsonPrimitive.long,
                openedInPause = row.bool("opened_in_pause"),
                pauseOn = row.bool("pause_on"),
            )
            assertEquals(row.name(), row.str("outcome"), portalClose(input))
        }
    }

    private fun budgetOf(o: JsonObject) = PortalBudget(
        date = o.str("date")!!,
        openings = o.getValue("openings").jsonPrimitive.int,
        ms = o.getValue("ms").jsonPrimitive.long,
        grantUsed = o.getValue("grant_used").jsonPrimitive.int,
        grantOpenings = o.getValue("grant_openings").jsonPrimitive.int,
        grantSeen = o.getValue("grant_seen").jsonPrimitive.long,
    )

    @Test
    fun `budget`() {
        for (row in rows("budget")) {
            val stored = row["stored"]?.takeIf { it !is JsonNull }?.jsonObject?.let(::budgetOf)
            var b = budgetToday(stored, LocalDate.parse(row.str("today")), row.getValue("grant").jsonPrimitive.long)
            for (charge in row.getValue("charges").jsonArray) b = chargeOpening(b, charge.jsonPrimitive.content)
            b = chargeTime(b, row.getValue("time_ms").jsonPrimitive.long)
            assertEquals(row.name(), budgetOf(row.getValue("expect").jsonObject), b)
            assertEquals(row.name(), row.bool("budget_left"), budgetLeft(b, 4, 20, 10))
            assertEquals(row.name(), row.bool("grant_left"), grantLeft(b))
        }
        val now = 1_794_000_000_000L
        for (row in rows("open_sessions")) {
            val opened = now - row.getValue("opened_ago_ms").jsonPrimitive.long
            val file = PortalSessionFile(
                openedMs = opened,
                closedMs = if (row.bool("closed")) opened + 1_000L else null,
                start = "system", approved = "code", kind = "sign_in",
                pauseEndMs = row["pause_end_in_ms"]?.let { now + it.jsonPrimitive.long },
            )
            assertEquals(row.name(), row.bool("open"), sessionOpen(file, now, row.getValue("minutes").jsonPrimitive.int))
        }
    }

    @Test
    fun `wifi rows and the reopen's match`() {
        for (row in rows("wifi_rows")) {
            val got = wifiRowState(row.bool("part_on"), row.bool("captive"), row.bool("require_code"), row.bool("budget_left"), row.bool("grant_left"), row.bool("revisit"))
            assertEquals(row.name(), row.str("row"), got.name.lowercase())
        }
        for (row in rows("revisit")) {
            val r = row.getValue("record").jsonObject
            val record = RevisitRecord("http://portal.hotel.example/login", "hotel.example", r.str("ssid"), r.getValue("handle").jsonPrimitive.long, r.getValue("boot").jsonPrimitive.int, r.str("date")!!)
            val got = revisitMatches(record, row.str("today")!!, row.getValue("boot").jsonPrimitive.int, row.getValue("handle").jsonPrimitive.long, row.str("ssid"))
            assertEquals(row.name(), row.bool("match"), got)
        }
        assertNull(PortalRuntime.cleanSsid("<unknown ssid>"))
        assertEquals("Hotel Fjord", PortalRuntime.cleanSsid("\"Hotel Fjord\""))
    }
}
