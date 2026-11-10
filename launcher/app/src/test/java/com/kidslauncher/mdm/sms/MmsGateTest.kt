package com.kidslauncher.mdm.sms

import com.kidslauncher.mdm.calls.MmscRule
import com.kidslauncher.mdm.calls.OwnNumberRule
import com.kidslauncher.mdm.calls.RuleContact
import com.kidslauncher.mdm.sms.engine.MmsGate
import com.kidslauncher.mdm.sms.engine.SenderClass
import com.kidslauncher.mdm.sms.mms.MmsAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Design 77 D10 (the sender and the host, before any fetch; the list's entries and ports by design 83) and D12 (the
 * group rule) - §10.1's `MmsGateTest`. The hosts are fake (`.invalid`): no real carrier's server is named in a test. */
class MmsGateTest {
    private val table = listOf(
        MmscRule("24299", listOf("mmsc.carrier.invalid", "10.0.0.9", "mmsc", "mms.carrier.invalid:8002")),
        MmscRule("24298", emptyList()),
    )

    private fun location(url: String, operator: String? = "24299") = MmsGate.location(url, operator, table)

    @Test
    fun `the sender must be digits of TYPE=PLMN`() {
        assertEquals(MmsGate.Sender.Number("+4790000001"), MmsGate.sender(MmsAddress("+4790000001/TYPE=PLMN")))
        assertEquals(MmsGate.Sender.Number("90000001"), MmsGate.sender(MmsAddress("90000001/TYPE=PLMN")))
        // The hidden sender (insert-address-token) and a missing From: no allow path.
        assertEquals(MmsGate.Sender.Blocked(SenderClass.EMPTY), MmsGate.sender(null))
        for (raw in listOf("someone@example.invalid", "+4790000001/TYPE=IPV4", "+4790000001", "DNB/TYPE=PLMN", "+47９０００００１/TYPE=PLMN", "/TYPE=PLMN")) {
            assertEquals(raw, MmsGate.Sender.Blocked(SenderClass.MALFORMED), MmsGate.sender(MmsAddress(raw)))
        }
    }

    @Test
    fun `the content location must be an entry of the SIM's list`() {
        assertEquals(MmsGate.Location.Ok("mmsc.carrier.invalid"), location("http://mmsc.carrier.invalid/mms/abc"))
        assertEquals(MmsGate.Location.Ok("mmsc.carrier.invalid"), location("https://MMSC.Carrier.Invalid:443/x?y=1"))
        assertEquals(MmsGate.Location.Ok("mmsc.carrier.invalid"), location("http://mmsc.carrier.invalid.:8080/x"))
        assertEquals(MmsGate.Location.Ok("10.0.0.9"), location("http://10.0.0.9/mms"))
        assertEquals("a single label", MmsGate.Location.Ok("mmsc"), location("http://mmsc/mms"))
        // Design 83 §1: another port is part of the entry - only `host:port` matches it, and it matches nothing else.
        assertEquals(MmsGate.Location.Ok("mms.carrier.invalid"), location("http://mms.carrier.invalid:8002/x"))
        assertEquals(MmsGate.Location.Unlisted("mms.carrier.invalid"), location("http://mms.carrier.invalid/x"))
        assertEquals(MmsGate.Location.Unlisted("mms.carrier.invalid:8003"), location("http://mms.carrier.invalid:8003/x"))
        assertEquals(MmsGate.Location.Unlisted("mmsc.carrier.invalid:81"), location("http://mmsc.carrier.invalid:81/x"))
        // Enea's "MMS Fingerprint", a push pointing anywhere else: the entry it names, no suffix matched.
        assertEquals(MmsGate.Location.Unlisted("attacker.invalid"), location("http://attacker.invalid/mms"))
        assertEquals(
            MmsGate.Location.Unlisted("mmsc.carrier.invalid.attacker.invalid"),
            location("http://mmsc.carrier.invalid.attacker.invalid/x"),
        )
        // Not a plain http(s) URL to a host of the entry grammar: no entry, so nothing a parent could allow.
        for (bad in listOf(
            "ftp://mmsc.carrier.invalid/x", "http://user:pw@mmsc.carrier.invalid/x", "http://mmsc.carrier.invalid\r\nHost: x/",
            "http://mmsc.carrier.invalid/a b", "http://mmsc.carrier.invalid\t/x", "mmsc.carrier.invalid/x", "http:mmsc", "",
            "http://mmsc_carrier.invalid/x", "http://mmsc.carrier.invalid/é", "http://[fd00::9]/x", "http://[::ffff:10.0.0.9]/x",
            "http://010.0.0.9/x", "http://2130706433/x", "http://0x7f000001/x", "http://10.0.0.9.1/x", "http://mmsc.carrier.invalid:0/x",
        )) {
            assertEquals(bad, MmsGate.Location.Bad, location(bad))
        }
    }

    @Test
    fun `an unknown SIM operator fails closed, and a bad location is bad before that`() {
        val unlisted = MmsGate.Location.Unlisted("mmsc.carrier.invalid")
        assertEquals(unlisted, location("http://mmsc.carrier.invalid/x", "24201"))
        assertEquals(unlisted, location("http://mmsc.carrier.invalid/x", null))
        assertEquals("a row without hosts", unlisted, location("http://mmsc.carrier.invalid/x", "24298"))
        assertEquals(unlisted, MmsGate.location("http://mmsc.carrier.invalid/x", "24299", emptyList()))
        assertEquals(MmsGate.Location.Bad, location("http://user@x.invalid/", "24201"))
        // Design 83 §7: the debug hook's host joins the SIM's own row - a second row for it would never be read.
        val merged = MmsGate.merged(table, listOf(MmscRule("24299", listOf("10.0.2.2")), MmscRule("24201", listOf("10.0.2.2"))))
        assertEquals(listOf("24299", "24298", "24201"), merged.map { it.mccmnc })
        assertEquals(MmsGate.Location.Ok("10.0.2.2"), MmsGate.location("http://10.0.2.2:8080/x", "24299", merged))
        assertEquals(MmsGate.Location.Ok("10.0.0.9"), MmsGate.location("http://10.0.0.9/x", "24299", merged))
        assertTrue(MmsGate.known("24299", table))
        assertFalse(MmsGate.known("24298", table))
        assertFalse(MmsGate.known(null, table))
    }

    @Test
    fun `the size is within the carrier's maximum`() {
        assertTrue(MmsGate.sizeOk(300_000, 307_200))
        assertTrue(MmsGate.sizeOk(307_200, 307_200))
        assertFalse(MmsGate.sizeOk(307_201, 307_200))
        assertFalse(MmsGate.sizeOk(-1, 307_200))
    }

    @Test
    fun `the fetched message's From must be the notification's`() {
        assertTrue(MmsGate.sameSender("+4790000001", MmsAddress("90000001/TYPE=PLMN"), "47"))
        assertTrue(MmsGate.sameSender("+4790000001", MmsAddress("+4790000001/TYPE=PLMN"), "47"))
        assertFalse(MmsGate.sameSender("+4790000001", MmsAddress("+4790000002/TYPE=PLMN"), "47"))
        assertFalse(MmsGate.sameSender("+4790000001", MmsAddress("+4790000001"), "47"))
        assertFalse(MmsGate.sameSender("+4790000001", null, "47"))
    }

    private val mamma = RuleContact(1, "Mamma", "+4790000001", smsIn = true, smsOut = true)
    private val pappa = RuleContact(2, "Pappa", "+4790000002", smsIn = true)
    private val tante = RuleContact(3, "Tante", "+4790000003", inbound = true, outbound = true)
    private val contacts = listOf(mamma, pappa, tante)
    private val kid = "+4790000010"

    private fun plmn(vararg numbers: String) = numbers.map { MmsAddress("$it/TYPE=PLMN") }

    @Test
    fun `the group rule (D12)`() {
        val own = setOf(kid)
        assertEquals(MmsGate.Group.OneToOne, MmsGate.group(mamma.number, plmn(kid), emptyList(), own, contacts, "47"))
        assertEquals(MmsGate.Group.OneToOne, MmsGate.group(mamma.number, plmn(kid, mamma.number), emptyList(), own, contacts, "47"))
        assertEquals(MmsGate.Group.Many(listOf(pappa.number)), MmsGate.group(mamma.number, plmn(kid), plmn("90000002"), own, contacts, "47"))
        // A stranger, a contact with no text switch, an address that isn't a number: blocked, no allow path.
        assertEquals(MmsGate.Group.Blocked, MmsGate.group(mamma.number, plmn(kid, "+4790000099"), emptyList(), own, contacts, "47"))
        assertEquals(MmsGate.Group.Blocked, MmsGate.group(mamma.number, plmn(kid, tante.number), emptyList(), own, contacts, "47"))
        assertEquals(MmsGate.Group.Blocked, MmsGate.group(mamma.number, plmn(kid) + MmsAddress("x@y.invalid"), emptyList(), own, contacts, "47"))
    }

    @Test
    fun `with no own number every group is blocked - one recipient is still us`() {
        assertEquals(MmsGate.Group.OneToOne, MmsGate.group(mamma.number, plmn(kid), emptyList(), emptySet(), contacts, "47"))
        assertEquals(MmsGate.Group.OneToOne, MmsGate.group(mamma.number, emptyList(), emptyList(), emptySet(), contacts, "47"))
        // To = [kid, stranger]: "the one unknown address must be us" would let the stranger in.
        assertEquals(MmsGate.Group.Blocked, MmsGate.group(mamma.number, plmn(kid, "+4790000099"), emptyList(), emptySet(), contacts, "47"))
        assertEquals(MmsGate.Group.Blocked, MmsGate.group(mamma.number, plmn(kid), plmn(pappa.number), emptySet(), contacts, "47"))
    }

    @Test
    fun `own numbers - the parent's per slot, else what the SIM knows`() {
        val parent = listOf(OwnNumberRule(1, "+4790000010"))
        assertEquals(setOf("+4790000010"), MmsGate.ownNumbers(parent, listOf(1 to "+4790000011")))
        assertEquals(setOf("+4790000010", "+4790000012"), MmsGate.ownNumbers(parent, listOf(1 to "+4790000011", 2 to "+4790000012")))
        assertEquals(setOf("+4790000012"), MmsGate.ownNumbers(emptyList(), listOf(1 to null, 2 to "+4790000012")))
        assertEquals(emptySet<String>(), MmsGate.ownNumbers(listOf(OwnNumberRule(1, "")), listOf(1 to null)))
    }

    @Test
    fun `an answer needs a transaction id the composer takes`() {
        assertTrue(MmsGate.answerableId("T1a2b3c"))
        assertFalse(MmsGate.answerableId(""))
        assertFalse(MmsGate.answerableId("a b"))
        assertFalse(MmsGate.answerableId("a\u0000b"))
        assertFalse(MmsGate.answerableId("x".repeat(65)))
    }
}
