package com.kidslauncher.mdm.calls

import kotlinx.serialization.Serializable
import java.security.SecureRandom

/*
 * The kid's own picture per contact (design 79 §5, `KidPhotosTest`) - pure. A picture the kid chose or took, framed and
 * re-encoded on the phone, kept on the phone only (never in DE storage, a status report, an upload or a backup). It
 * wins over the parent's picture on the kid's phone; an emergency number never shows one. Nothing here is a
 * restriction (§5.6).
 */

/** At most this many kid pictures, whatever the phone book says (a bound on a bug, §5.4): about 15 MB at worst. */
const val KID_PHOTOS_MAX = 100

/** A kid picture's file is at most this big (`PictureEncoder.CONTACT_MAX_BYTES`). */
const val KID_PHOTO_MAX_BYTES = 150 * 1024

/** A request's picture draft (before "Send" gives the request an id) is swept after a day (§5.5). */
const val REQUEST_PHOTO_DRAFT_KEEP_MS = 24 * 60 * 60_000L

/** One kid picture: its [file] (a random name in `filesDir/kid_contact_photos/`), the contact's [number] when it was set
 * (a re-pointed contact drops it, QA L9) and when. */
@Serializable
data class KidPhotoRecord(val file: String, val number: String, val setAtMs: Long)

private val FILE_NAME = Regex("^k[0-9a-f]{32}\\.jpg$")

/** A new picture's file name: random, so it never names a parent's photo (a SHA-256) nor a stale bitmap in memory. */
fun newKidPhotoFileName(random: SecureRandom = SecureRandom()): String =
    "k" + ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) } + ".jpg"

fun isKidPhotoFileName(name: String): Boolean = FILE_NAME.matches(name)

/** What an avatar shows (§5.4). */
sealed interface ShownPhoto {
    data object None : ShownPhoto

    /** The kid's own picture: its file. */
    data class Kid(val file: String) : ShownPhoto

    /** The parent's picture: its hash ([ContactPhotos]). */
    data class Parent(val hash: String) : ShownPhoto
}

/** Emergency: none; the kid's picture (only while the contact's number is the one it was set for); else the parent's;
 * else none (the initial). Every avatar binding goes through it. */
fun shownPhoto(contact: RuleContact, kid: KidPhotoRecord?, emergency: Boolean): ShownPhoto = when {
    emergency -> ShownPhoto.None
    kid != null && kid.number == contact.number && isKidPhotoFileName(kid.file) -> ShownPhoto.Kid(kid.file)
    contact.photo != null -> ShownPhoto.Parent(contact.photo)
    else -> ShownPhoto.None
}

/**
 * The records after a refresh of the call rules (§5.4, QA M1's rule): only a CE refresh ([ceReadable]) of a `Managed`
 * state prunes - never `UnknownFailClosed`, never before the unlock, never unmanaged (`null` = keep everything). A
 * record goes when its contact is no longer **on the phone** (`rules.contacts`) or its number changed; a contact out of
 * "I telefonboken" but still on the phone keeps it (Meldinger shows it).
 */
fun kidPhotosAfterRefresh(records: Map<Long, KidPhotoRecord>, state: CallPolicyState, ceReadable: Boolean): Map<Long, KidPhotoRecord>? {
    if (!ceReadable) return null
    val rules = (state as? CallPolicyState.Managed)?.rules ?: return null
    val byId = rules.contacts.associateBy { it.id }
    return records.filter { (id, record) -> byId[id]?.number == record.number }
}

/** The files in the pictures' directory no record names (swept at the same pass). */
fun kidPhotoOrphans(records: Collection<KidPhotoRecord>, files: Collection<String>): Set<String> =
    files.toSet() - records.mapTo(mutableSetOf()) { it.file }

/** Whether a picture may be set for [contactId] (§5.4): one per contact (a new one replaces it); at most as many as the
 * phone book has contacts, never more than [KID_PHOTOS_MAX]. */
fun kidPhotoMayAdd(records: Map<Long, KidPhotoRecord>, contactId: Long, phoneBookSize: Int): Boolean =
    contactId in records || records.size < minOf(phoneBookSize, KID_PHOTOS_MAX)

/**
 * Whether the card offers "Bilde av …" for [contact] (§5.1): a contact in the phone book ([CallRules.phoneBook]), not
 * an emergency number, after the first unlock, and with the photo picker or an allowed camera to take it from.
 */
fun kidPhotoOffered(contact: RuleContact, rules: CallRules?, emergency: Boolean, unlocked: Boolean, canPick: Boolean): Boolean =
    rules != null && !emergency && unlocked && canPick &&
        rules.phoneBook.any { it.id == contact.id && it.number == contact.number }
