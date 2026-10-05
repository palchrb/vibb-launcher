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
}
