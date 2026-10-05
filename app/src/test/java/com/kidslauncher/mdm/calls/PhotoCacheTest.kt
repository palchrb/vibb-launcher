package com.kidslauncher.mdm.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoCacheTest {

    private val a = "a".repeat(64)
    private val b = "0123456789abcdef".repeat(4)
    private val c = "c".repeat(64)

    @Test
    fun `only lowercase sha-256 hex is a photo name`() {
        assertTrue(isValidPhotoHash(a))
        assertTrue(isValidPhotoHash(b))
        for (bad in listOf(null, "", "A".repeat(64), "a".repeat(63), "a".repeat(65), "../../shared_prefs/x", "g".repeat(64))) {
            assertFalse(bad.toString(), isValidPhotoHash(bad))
        }
    }

    @Test
    fun `wanted photos come from managed rules only`() {
        val rules = CallRules(
            callsEnabled = true,
            contacts = listOf(
                RuleContact(id = 1, number = "+4790000001", photo = a),
                RuleContact(id = 2, number = "+4790000002", photo = null),
                RuleContact(id = 3, number = "+4790000003", photo = "../evil"),
                RuleContact(id = 4, number = "+4790000004", photo = a),
            ),
        )
        assertEquals(setOf(a), wantedPhotoHashes(CallPolicyState.Managed(rules)))
        assertEquals(emptySet<String>(), wantedPhotoHashes(CallPolicyState.Unmanaged))
        assertEquals(emptySet<String>(), wantedPhotoHashes(CallPolicyState.UnknownFailClosed))
    }

    @Test
    fun `download missing, delete stale and junk`() {
        val plan = photoCachePlan(
            wanted = setOf(a, b),
            cachedFiles = setOf("$a.jpg", "$c.jpg", ".$b.tmp", "notes.txt", a),
        )
        assertEquals(setOf(b), plan.download)
        assertEquals(setOf("$c.jpg", ".$b.tmp", "notes.txt", a), plan.delete)
    }

    @Test
    fun `unknown rules keep the cache`() {
        val plan = photoCachePlan(emptySet(), setOf("$a.jpg"), keepWhenUnknown = true)
        assertEquals(PhotoCachePlan(emptySet(), emptySet()), plan)
        assertEquals(setOf("$a.jpg"), photoCachePlan(emptySet(), setOf("$a.jpg")).delete)
    }

    @Test
    fun `downloads must match their name`() {
        assertTrue(photoMatches(a, a))
        assertTrue(photoMatches(a, a.uppercase()))
        assertFalse(photoMatches(a, b))
        assertFalse(photoMatches("x", "x"))
    }

    @Test
    fun `hashes that 404 are skipped until the policy names another photo`() {
        val plan = photoCachePlan(wanted = setOf(a, b), cachedFiles = emptySet(), notFound = setOf(b))
        assertEquals(setOf(a), plan.download)
        // Still wanted: remembered; no longer in the policy: forgotten; new 404s added.
        assertEquals(setOf(b, c), rememberNotFound(previous = setOf(b, "x".repeat(64)), wanted = setOf(a, b), newlyNotFound = setOf(c)))
        assertEquals(emptySet<String>(), rememberNotFound(previous = setOf(b), wanted = setOf(a), newlyNotFound = emptySet()))
    }

    @Test
    fun `only small declared bitmaps are decoded`() {
        assertTrue(photoBoundsOk(512, 512))
        assertTrue(photoBoundsOk(1, 1))
        assertTrue(photoBoundsOk(MAX_PHOTO_SIDE, MAX_PHOTO_SIDE))
        for ((w, h) in listOf(0 to 512, 512 to 0, -1 to -1, 1025 to 10, 10 to 1025, 16000 to 16000)) {
            assertFalse("${w}x$h", photoBoundsOk(w, h))
        }
    }
}
