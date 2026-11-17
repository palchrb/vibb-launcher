package com.kidslauncher.mdm.push.up

import com.kidslauncher.mdm.server.ParentPinStoreTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Design 108 §2.3: the policy's `regs` reconciled race-free (a registration confirmed during the policy fetch
 * survives), stopped and not-allowed apps told, unknown server rows deleted; and the engine around it. */
class UpReconcileTest {
    @Test
    fun `every reconcile row keeps, tells and deletes as written`() {
        for (case in UpFixtures.rules["reconcile"]!!.jsonArray.map { it.jsonObject }) {
            val name = case["case"]!!.jsonPrimitive.content
            val result = UpRules.reconcile(
                UpFixtures.rows,
                UpFixtures.view(case["view"]!!.jsonPrimitive.content),
                UpFixtures.strings(case["regs"]),
                case["request_start_ms"]!!.jsonPrimitive.long,
                case["now_ms"]!!.jsonPrimitive.long,
            )
            assertEquals(name, UpFixtures.strings(case["keep"]), result.keep.map { it.reg })
            assertEquals(name, UpFixtures.strings(case["tell"]), result.drop.filter { it.tellApp }.map { it.row.reg })
            val deletes = result.drop.filter { it.deleteOnServer }.map { it.row.reg } + result.deleteUnknown
            assertEquals(name, UpFixtures.strings(case["delete"]), deletes)
        }
    }

    private var now = 8000L

    /** The engine's own view is the cache's; [policyAccepted] gets the fresh policy's (code QA M3). */
    private fun engine(store: UpStore, server: FakeUpServer, phone: FakeUpPhone, cached: UpView = UpFixtures.view("on")) =
        UpEngine({ true }, { store }, { cached }, { server }, phone, { now })

    @Test
    fun `the engine tells the apps, rewrites a moved origin, drops an uninstalled app and sends the deletes`() {
        val store = UpFixtures.store()
        store.write(UpFixtures.rows)
        val server = FakeUpServer()
        val phone = FakeUpPhone()
        phone.states["org.unifiedpush.example"] = UpAppState.UNINSTALLED
        engine(store, server, phone).policyAccepted(
            UpFixtures.view("moved"),
            listOf("c0ffee00000000000000000000000001", "c0ffee00000000000000000000000002", "c0ffee00000000000000000000000009"),
            requestStartMs = 7000,
        )
        val rows = (store.read() as UpRead.Rows).rows
        assertEquals(listOf("c0ffee00000000000000000000000002", "c0ffee00000000000000000000000003"), rows.map { it.reg })
        assertEquals("https://push.example.net/up/FakeSecretForTheContract002", rows[0].endpoint)
        assertEquals(listOf("NEW_ENDPOINT io.element.android.x tok-element-1 https://push.example.net/up/FakeSecretForTheContract002"), phone.sent)
        assertEquals(listOf("c0ffee00000000000000000000000001", "c0ffee00000000000000000000000009"), server.unregistered)
        assertEquals(emptyList<String>(), store.deletes())
    }

    @Test
    fun `the fresh policy's view decides, never the cache read back (M3)`() {
        val store = UpFixtures.store()
        store.write(UpFixtures.rows)
        val phone = FakeUpPhone()
        // A cache that reads off (a failed commit, a corrupt blob) while the fresh policy is on: nothing is dropped.
        engine(store, FakeUpServer(), phone, cached = UpFixtures.view("off"))
            .policyAccepted(UpFixtures.view("on"), listOf("c0ffee00000000000000000000000001", "c0ffee00000000000000000000000002"), 7000)
        assertEquals(3, (store.read() as UpRead.Rows).rows.size)
        assertTrue(phone.sent.isEmpty())
    }

    @Test
    fun `off tells every app and a failed delete stays queued for the next sync`() {
        val store = UpFixtures.store()
        store.write(UpFixtures.rows)
        val server = FakeUpServer().apply { unregisterWorks = false }
        val phone = FakeUpPhone()
        engine(store, server, phone).policyAccepted(UpFixtures.view("off"), emptyList(), 7000)
        assertEquals(3, phone.sent.count { it.startsWith("UNREGISTERED") })
        assertEquals(emptyList<UpRow>(), (store.read() as UpRead.Rows).rows)
        // An app removed: its DELETE fails offline, kept.
        store.write(UpFixtures.rows)
        val on = engine(store, server, phone)
        on.packageGone("io.element.android.x")
        assertEquals(listOf("c0ffee00000000000000000000000002", "c0ffee00000000000000000000000003"), store.deletes())
        server.unregisterWorks = true
        on.policyAccepted(UpFixtures.view("on"), listOf("c0ffee00000000000000000000000001"), 7000)
        assertEquals(emptyList<String>(), store.deletes())
    }

    @Test
    fun `a store that doesn't open changes nothing for a day, then is dropped and the server's rows deleted (L4)`() {
        val prefs = ParentPinStoreTest.FakePrefs()
        prefs.map[UpStore.REGS] = "ks1:AAAA"
        val store = UpFixtures.store(prefs)
        val server = FakeUpServer()
        val phone = FakeUpPhone()
        val engine = engine(store, server, phone)
        engine.policyAccepted(UpFixtures.view("on"), listOf("c0ffee00000000000000000000000009"), 7000)
        now += UpEngine.UNREADABLE_GRACE_MS - 1
        engine.policyAccepted(UpFixtures.view("on"), listOf("c0ffee00000000000000000000000009"), now)
        assertTrue(server.unregistered.isEmpty())
        assertTrue(phone.sent.isEmpty())
        assertEquals("ks1:AAAA", prefs.map[UpStore.REGS])
        now += 2
        engine.policyAccepted(UpFixtures.view("on"), listOf("c0ffee00000000000000000000000009"), now)
        assertEquals(listOf("c0ffee00000000000000000000000009"), server.unregistered)
        assertEquals(UpRead.Rows(emptyList()), store.read())
        assertTrue(UpStore.UNREADABLE_SINCE !in prefs.map)
    }
}
