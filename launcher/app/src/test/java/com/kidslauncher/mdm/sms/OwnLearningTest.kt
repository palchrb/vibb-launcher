package com.kidslauncher.mdm.sms

import com.kidslauncher.mdm.calls.OwnNumberRule
import com.kidslauncher.mdm.calls.RuleContact
import com.kidslauncher.mdm.sms.engine.MmsGate
import com.kidslauncher.mdm.sms.engine.OwnLearned
import com.kidslauncher.mdm.sms.engine.OwnLearning
import com.kidslauncher.mdm.sms.mms.MmsAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Design 80 §4.4: the own number learned from the first MMS from an approved contact addressed to the kid alone - the
 * conditions, the conflict that fails closed, what clears it - and D13's group switch on [MmsGate.group]. The numbers
 * are made up (`+479000xxxx`).
 */
class OwnLearningTest {
    private val mamma = RuleContact(1, "Mamma", "+4790000001", smsIn = true, smsOut = true)
    private val bestemor = RuleContact(5, "Bestemor", "+4790000005", smsIn = true, smsOut = true)
    private val contacts = listOf(mamma, bestemor)
    private val kid = "+4790000010"

    private fun plmn(vararg numbers: String) = numbers.map { MmsAddress("$it/TYPE=PLMN") }

    private fun learn(
        current: OwnLearned? = null,
        known: Set<String> = emptySet(),
        allowed: Boolean = true,
        to: List<MmsAddress> = plmn(kid),
        cc: List<MmsAddress> = emptyList(),
    ) = OwnLearning.learn(current, known, mamma.number, allowed, to, cc, contacts, { it == "112" || it == "113" }, "47", 1000L, "7")

    @Test
    fun `an allowed contact's MMS to the kid alone teaches the number`() {
        assertEquals(OwnLearned(kid, mamma.number, 1000L, emptyList(), "7"), learn())
        // The same number again changes nothing.
        assertNull(learn(current = learn()))
    }

    @Test
    fun `it doesn't learn from anything else`() {
        // A sender that isn't allowed (not a contact with sms_in, an emergency number - judged by the flow).
        assertNull(learn(allowed = false))
        // Two To addresses, or any Cc.
        assertNull(learn(to = plmn(kid, bestemor.number)))
        assertNull(learn(cc = plmn(kid)))
        assertNull(learn(to = emptyList()))
        // A short code, an emergency number, the sender or a contact as the To; an address that isn't a PLMN number.
        assertNull(learn(to = plmn("2160")))
        assertNull(learn(to = plmn("113")))
        assertNull(learn(to = plmn(mamma.number)))
        assertNull(learn(to = plmn(bestemor.number)))
        assertNull(learn(to = listOf(MmsAddress("kid@example.invalid"))))
        // A phone that already knows a number (the parent's or a SIM's).
        assertNull(learn(known = setOf("+4790000011")))
    }

    @Test
    fun `a second message naming another number drops it, reports both and stops learning`() {
        val first = learn()!!
        val conflict = learn(current = first, to = plmn("+4790000020"))!!
        assertNull(conflict.number)
        assertEquals(listOf(kid, "+4790000020"), conflict.conflict)
        // Nothing more is learned, not even the first number again.
        assertNull(learn(current = conflict))
        assertNull(learn(current = conflict, to = plmn("+4790000030")))
    }

    @Test
    fun `a SIM change, the parent's number and a contact with the number clear it`() {
        val learned = learn()!!
        assertEquals(learned, OwnLearning.current(learned, "7", emptyList(), contacts))
        assertNull(OwnLearning.current(learned, "7,8", emptyList(), contacts))
        assertNull(OwnLearning.current(learned, "7", listOf(OwnNumberRule(1, "+4790000011")), contacts))
        assertNull(OwnLearning.current(learned, "7", emptyList(), contacts + RuleContact(9, "Meg", kid)))
        assertNull(OwnLearning.current(OwnLearning.decode("not json"), "7", emptyList(), contacts))
        assertEquals(learned, OwnLearning.decode(OwnLearning.encode(learned)))
    }

    @Test
    fun `the parent's and the SIMs' numbers win, the learned one is us only while they are missing`() {
        assertEquals(setOf("+4790000011"), MmsGate.ownNumbers(listOf(OwnNumberRule(1, "+4790000011")), emptyList(), kid))
        assertEquals(setOf("+4790000012"), MmsGate.ownNumbers(emptyList(), listOf(1 to "+4790000012"), kid))
        assertEquals(setOf(kid), MmsGate.ownNumbers(emptyList(), listOf(1 to null), kid))
        assertEquals(emptySet<String>(), MmsGate.ownNumbers(emptyList(), listOf(1 to null), null))
    }

    @Test
    fun `group texts off block every group and keep a one-to-one message (D13)`() {
        val group = MmsGate.group(mamma.number, plmn(kid, bestemor.number), emptyList(), setOf(kid), contacts, "47")
        assertEquals(MmsGate.Group.Many(listOf(bestemor.number)), group)
        assertEquals(MmsGate.Group.Blocked, MmsGate.group(mamma.number, plmn(kid, bestemor.number), emptyList(), setOf(kid), contacts, "47", groups = false))
        assertEquals(MmsGate.Group.OneToOne, MmsGate.group(mamma.number, plmn(kid), emptyList(), setOf(kid), contacts, "47", groups = false))
        // A group judged with the learned number as "us".
        val own = MmsGate.ownNumbers(emptyList(), emptyList(), kid)
        assertEquals(MmsGate.Group.Many(listOf(bestemor.number)), MmsGate.group(mamma.number, plmn(kid, bestemor.number), emptyList(), own, contacts, "47"))
    }
}
