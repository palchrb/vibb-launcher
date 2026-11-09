package com.kidslauncher.mdm.calls

import com.kidslauncher.mdm.server.dto.PolicyContactRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Design 79 §5.4-§5.5: the kid's own pictures - what shows, the prune, the caps, the sweeps, the request's picture. */
class KidPhotosTest {
    private val mamma = RuleContact(1, "Mamma", "+4790000001", inbound = true, outbound = true, photo = "a".repeat(64))
    private val pappa = RuleContact(2, "Pappa", "+4790000002", inbound = true, outbound = true)
    private val tekst = RuleContact(3, "Tekst", "+4790000003", smsOut = true) // on the phone, not in the phone book
    private val rules = CallRules(callsEnabled = true, contacts = listOf(mamma, pappa, tekst))
    private val file1 = newKidPhotoFileName()
    private val file2 = newKidPhotoFileName()
    private val file3 = newKidPhotoFileName()

    @Test
    fun `what shows - the kid's picture, else the parent's, else the initial, an emergency number never one`() {
        val kid = KidPhotoRecord(file1, mamma.number, 0)
        assertEquals(ShownPhoto.Kid(file1, mamma.photo), shownPhoto(mamma, kid, emergency = false))
        assertEquals(ShownPhoto.Parent(mamma.photo!!), shownPhoto(mamma, null, emergency = false))
        assertEquals(ShownPhoto.None, shownPhoto(pappa, null, emergency = false))
        assertEquals(ShownPhoto.None, shownPhoto(mamma, kid, emergency = true))
        // A re-pointed contact shows the parent's again until the prune drops the record (QA L9).
        assertEquals(ShownPhoto.Parent(mamma.photo!!), shownPhoto(mamma.copy(number = "+4799999999"), kid, emergency = false))
        // A record naming anything but one of our random files is never read.
        assertEquals(ShownPhoto.Parent(mamma.photo!!), shownPhoto(mamma, kid.copy(file = "../contact_photos/x.jpg"), emergency = false))
        assertTrue(isKidPhotoFileName(file1) && file1 != file2)
        assertFalse(isKidPhotoFileName("a".repeat(64) + ".jpg"))
    }

    @Test
    fun `the prune - on removal and a number change, only on a CE refresh of managed rules`() {
        val records = mapOf(
            1L to KidPhotoRecord(file1, mamma.number, 0),
            2L to KidPhotoRecord(file2, "+4711111111", 0), // Pappa was re-pointed
            3L to KidPhotoRecord(file3, tekst.number, 0), // out of the phone book, still on the phone: kept
            9L to KidPhotoRecord(newKidPhotoFileName(), "+4790000009", 0), // gone from the phone
        )
        val managed = CallPolicyState.Managed(rules)
        assertEquals(setOf(1L, 3L), kidPhotosAfterRefresh(records, managed, ceReadable = true)?.keys)
        // Calls off: the contacts are still on the phone - nothing goes.
        val off = CallPolicyState.Managed(rules.copy(callsEnabled = false))
        assertEquals(setOf(1L, 3L), kidPhotosAfterRefresh(records, off, ceReadable = true)?.keys)
        // Unknown, unmanaged, before the unlock: never.
        assertNull(kidPhotosAfterRefresh(records, CallPolicyState.UnknownFailClosed, ceReadable = true))
        assertNull(kidPhotosAfterRefresh(records, CallPolicyState.Unmanaged, ceReadable = true))
        assertNull(kidPhotosAfterRefresh(records, managed, ceReadable = false))
    }

    @Test
    fun `the caps - one per contact, at most the phone book, never past 100`() {
        val one = mapOf(1L to KidPhotoRecord(file1, mamma.number, 0))
        assertTrue(kidPhotoMayAdd(one, 1L, contactsOnPhone = 1)) // a new one replaces it
        assertFalse(kidPhotoMayAdd(one, 2L, contactsOnPhone = 1))
        assertTrue(kidPhotoMayAdd(one, 2L, contactsOnPhone = 2))
        val full = (1L..100L).associateWith { KidPhotoRecord(newKidPhotoFileName(), "+47$it", 0) }
        assertFalse(kidPhotoMayAdd(full, 101L, contactsOnPhone = 500))
        // Code QA L7: the cap counts every contact on the phone - Mamma moved to text-only keeps her picture and her
        // place, so Bestemor, new in the phone book, still gets one (3 pictures, 4 contacts on the phone).
        val three = (1L..3L).associateWith { KidPhotoRecord(newKidPhotoFileName(), "+47$it", 0) }
        assertTrue(kidPhotoMayAdd(three, 4L, contactsOnPhone = 4))
        assertFalse(kidPhotoMayAdd(three, 4L, contactsOnPhone = 3))
        assertEquals(100, KID_PHOTOS_MAX)
        assertEquals(150 * 1024, KID_PHOTO_MAX_BYTES)
        assertEquals(com.kidslauncher.mdm.sms.parse.PictureEncoder.CONTACT_MAX_BYTES, KID_PHOTO_MAX_BYTES)
    }

    @Test
    fun `the orphan sweeps - pictures and request pictures no record names`() {
        val records = listOf(KidPhotoRecord(file1, mamma.number, 0))
        assertEquals(setOf(file2, ".x.tmp"), kidPhotoOrphans(records, listOf(file1, file2, ".x.tmp")))
        val open = ContactRequestRecord("aa", "Lina", "+4791111111", null, 0, ContactRequestPhase.ASKED, photo = true)
        val noPhoto = open.copy(id = "bb", photo = false)
        val declined = open.copy(id = "cc", phase = ContactRequestPhase.DECLINED)
        val pending = open.copy(id = "dd", phase = ContactRequestPhase.PENDING)
        assertEquals(setOf("aa.jpg", "dd.jpg"), requestPhotosToKeep(listOf(open, noPhoto, declined, pending)))
    }

    @Test
    fun `the request's picture - handed only to a contact that arrives in the phone book`() {
        val now = 1_000_000L
        fun request(id: String, number: String, photo: Boolean = true) =
            ContactRequestRecord(id, "Lina", number, null, now - 10, ContactRequestPhase.ASKED, photo = photo)
        val arrives = request("r1", "+4791111111")
        val textOnly = request("r2", "+4792222222")
        val noPicture = request("r3", "+4793333333", photo = false)
        val otherNumber = request("r4", "+4794444444")
        val declined = request("r5", "+4795555555")
        val expired = request("r6", "+4791111111").copy(askedAtMs = now - CONTACT_REQUEST_KEEP_MS - 1)
        val contacts = listOf(
            ArrivedContact(11, "+4791111111", inPhoneBook = true),
            ArrivedContact(12, "+4792222222", inPhoneBook = false),
            ArrivedContact(13, "+4793333333", inPhoneBook = true),
        )
        val rows = listOf(PolicyContactRequest("r4", "added"), PolicyContactRequest("r5", "declined"))
        val result = reconcileContactRequests(listOf(arrives, textOnly, noPicture, otherNumber, declined, expired), rows, contacts, now)
        assertEquals(mapOf("r1" to 11L), result.handOvers)
        // Only the declined answer stays (shown once); its picture is the sweep's (not open).
        assertEquals(listOf("r5"), result.records.map { it.id })
        assertEquals(emptySet<String>(), requestPhotosToKeep(result.records))
        // Calls not managed: nothing arrives, nothing is handed over.
        assertEquals(emptyMap<String, Long>(), reconcileContactRequests(listOf(arrives), emptyList(), null, now).handOvers)
    }

    @Test
    fun `offered only for a phone-book contact, not an emergency number, after the unlock, with a way to pick`() {
        assertTrue(kidPhotoOffered(mamma, rules, emergency = false, unlocked = true, canPick = true))
        assertFalse(kidPhotoOffered(tekst, rules, emergency = false, unlocked = true, canPick = true))
        assertFalse(kidPhotoOffered(mamma, rules, emergency = true, unlocked = true, canPick = true))
        assertFalse(kidPhotoOffered(mamma, rules, emergency = false, unlocked = false, canPick = true))
        assertFalse(kidPhotoOffered(mamma, rules, emergency = false, unlocked = true, canPick = false))
        assertFalse(kidPhotoOffered(mamma, null, emergency = false, unlocked = true, canPick = true))
    }

    private fun source(path: String) = listOf("src/main/java/com/kidslauncher/mdm/$path", "app/src/main/java/com/kidslauncher/mdm/$path")
        .map { File(it) }.first { it.exists() }.readText()

    /** QA H2: a binding never reads the disk - the avatar asks the snapshot and the decoded cache, the snapshot is
     * loaded on the store's own thread after the unlock (empty before it), and every file the store keeps is in CE. */
    @Test
    fun `no disk in a binding, the snapshot empty before the unlock, CE only`() {
        val avatars = source("ui/home/KidAvatars.kt")
        val bind = avatars.substringAfter("fun bindContact(").substringBefore("\n    }")
        assertTrue(bind.contains("ContactPhotos.cachedShown(context, shownPhotoOf(context, contact, isEmergency), thumb)"))
        val shown = avatars.substringAfter("fun shownPhotoOf(").substringBefore("\n\n")
        assertTrue(shown.contains("KidContactPhotos.recordFor(context, contact)"))
        val store = source("calls/KidContactPhotos.kt")
        val recordFor = store.substringAfter("fun recordFor(").substringBefore("\n    }")
        for (disk in listOf("File(", "prefs(", "read(", "getSharedPreferences")) assertFalse(disk, recordFor.contains(disk))
        val load = store.substringAfter("fun ensureLoaded(").substringBefore("\n    /**")
        assertTrue(load.contains("io.execute") && load.contains("isUserUnlocked == true"))
        assertTrue(store.contains("var snapshot: Map<Long, KidPhotoRecord> = emptyMap()"))
        for (de in listOf("createDeviceProtectedStorageContext", "deviceProtected")) assertFalse(de, store.contains(de))
        val photos = source("calls/ContactPhotos.kt")
        val cachedFile = photos.substringAfter("internal fun cachedFile(").substringBefore("decoder.execute")
        assertFalse(cachedFile.contains(".isFile") || cachedFile.contains("decodeFile"))
        // The prune runs on the CE refresh only, after the state is set.
        val refresh = source("calls/CallPolicyStore.kt")
        assertTrue(refresh.substringAfter("if (plan.source != PolicySource.CE) return").contains("KidContactPhotos.afterRefresh(context, newState)"))
    }
}
