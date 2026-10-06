package com.kidslauncher.mdm.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Design 13 (catalog downloads: Wi-Fi only, resumable) with its QA review and decisions. */
class AppDownloadPlanTest {
    private val now = 1_791_244_800_000L
    private val mb = 1024L * 1024

    /** Our VPN over Wi-Fi with setMetered(false): the underlying network's NOT_METERED. */
    private val vpnOnWifi = NetCaps(internet = true, notMetered = true, notRoaming = true, physical = true)
    /** Our VPN over cellular: metered whatever the VPN says. */
    private val vpnOnCellular = vpnOnWifi.copy(notMetered = false)
    private val roaming = vpnOnCellular.copy(notRoaming = false)
    /** QA #1: the VPN without an underlying network keeps INTERNET and NOT_ROAMING. */
    private val vpnWithoutNetwork = NetCaps(internet = true, notMetered = false, notRoaming = true, physical = false)

    private fun gate(
        caps: NetCaps?,
        wifiOnly: Boolean = true,
        launcher: Boolean = false,
        firstSeen: Long = now - 60_000,
        at: Long = now,
        have: Long = 0,
        total: Long? = null,
        allocatable: Long? = null,
    ) = downloadGate(caps, wifiOnly, anyNetworkAtMs(launcher, firstSeen), at, have, total, allocatable)

    @Test
    fun `wifi only waits for an unmetered network`() {
        assertEquals(DownloadGate.GO, gate(vpnOnWifi))
        assertEquals(DownloadGate.WAIT_WIFI, gate(vpnOnCellular))
        // Switch off: any non-roaming network, no size rule (decision after QA review).
        assertEquals(DownloadGate.GO, gate(vpnOnCellular, wifiOnly = false))
    }

    @Test
    fun `no default network or a VPN without one waits for a network`() {
        assertEquals(DownloadGate.WAIT_NETWORK, gate(null))
        assertEquals(DownloadGate.WAIT_NETWORK, gate(vpnWithoutNetwork, wifiOnly = false))
        assertEquals(DownloadGate.WAIT_NETWORK, gate(vpnOnWifi.copy(internet = false)))
    }

    @Test
    fun `roaming always waits - switch on or off, the launcher included`() {
        assertEquals(DownloadGate.WAIT_ROAMING, gate(roaming))
        assertEquals(DownloadGate.WAIT_ROAMING, gate(roaming, wifiOnly = false))
        assertEquals(DownloadGate.WAIT_ROAMING, gate(roaming, launcher = true, firstSeen = now - 10 * LAUNCHER_WIFI_GRACE_MS))
    }

    @Test
    fun `the launcher takes any network 3 days after it first saw the release`() {
        val seen = now - LAUNCHER_WIFI_GRACE_MS
        assertEquals(DownloadGate.GO, gate(vpnOnCellular, launcher = true, firstSeen = seen))
        assertEquals(DownloadGate.WAIT_WIFI, gate(vpnOnCellular, launcher = true, firstSeen = seen + 1))
        // Catalog apps have no grace: they wait for Wi-Fi as long as the switch is on.
        assertEquals(DownloadGate.WAIT_WIFI, gate(vpnOnCellular, firstSeen = now - 30 * LAUNCHER_WIFI_GRACE_MS))
        assertEquals(seen + LAUNCHER_WIFI_GRACE_MS, anyNetworkAtMs(true, seen))
        assertNull(anyNetworkAtMs(false, seen))
    }

    @Test
    fun `space is the rest of the download plus a session copy plus 100 MB`() {
        val total = 326 * mb
        val have = 120 * mb
        val needed = (total - have) + total + DOWNLOAD_SPACE_MARGIN_BYTES
        assertEquals(DownloadGate.GO, gate(vpnOnWifi, have = have, total = total, allocatable = needed))
        assertEquals(DownloadGate.WAIT_SPACE, gate(vpnOnWifi, have = have, total = total, allocatable = needed - 1))
        // Unknown size or unknown free space: checked once known.
        assertEquals(DownloadGate.GO, gate(vpnOnWifi, total = null, allocatable = 0))
        assertEquals(DownloadGate.GO, gate(vpnOnWifi, total = total, allocatable = null))
        // The network comes first.
        assertEquals(DownloadGate.WAIT_WIFI, gate(vpnOnCellular, total = total, allocatable = 0))
    }

    @Test
    fun `the status words are the server's`() {
        assertEquals(
            listOf("downloading", "waiting_network", "waiting_wifi", "waiting_roaming", "waiting_space"),
            DownloadGate.entries.map { it.wire },
        )
        assertEquals("unmetered", networkLabel(vpnOnWifi))
        assertEquals("metered", networkLabel(vpnOnCellular))
        assertEquals("roaming", networkLabel(roaming))
        assertEquals("none", networkLabel(vpnWithoutNetwork))
        assertEquals("none", networkLabel(null))
    }

    // ---- resuming ----------------------------------------------------------------------------------

    @Test
    fun `responses map to resume actions`() {
        assertEquals(ResumeAction.TRUNCATE, resumeAction(200, 0, null, null))
        // An older server ignores Range: start from the beginning.
        assertEquals(ResumeAction.TRUNCATE, resumeAction(200, 500, 1000, null))
        assertEquals(ResumeAction.APPEND, resumeAction(206, 500, 1000, "bytes 500-999/1000"))
        assertEquals(ResumeAction.RESTART, resumeAction(206, 500, 1000, "bytes 0-999/1000"))
        assertEquals(ResumeAction.RESTART, resumeAction(206, 500, 1000, null))
        assertEquals(ResumeAction.RESTART, resumeAction(412, 500, 1000, null))
        assertEquals(ResumeAction.COMPLETE, resumeAction(416, 1000, 1000, "bytes */1000"))
        assertEquals(ResumeAction.COMPLETE, resumeAction(416, 1000, 1000, null))
        assertEquals(ResumeAction.RESTART, resumeAction(416, 1200, 1000, "bytes */1000"))
        assertEquals(ResumeAction.RESTART, resumeAction(416, 1000, null, "bytes */900"))
        assertEquals(ResumeAction.GONE, resumeAction(404, 500, 1000, null))
        assertEquals(ResumeAction.RETRY, resumeAction(500, 500, 1000, null))
        assertEquals(ResumeAction.RETRY, resumeAction(401, 0, null, null))
    }

    @Test
    fun `content range parses both forms`() {
        assertEquals(500L to 1000L, parseContentRange("bytes 500-999/1000"))
        assertEquals(null to 1000L, parseContentRange("bytes */1000"))
        assertEquals(0L to null, parseContentRange("bytes 0-9/*"))
        assertNull(parseContentRange(null))
        assertNull(parseContentRange("bytes x-9/10"))
        assertNull(parseContentRange("garbage"))
    }

    /** The same vectors as kid-phone-server's `the_release_tag_header_is_plain_ascii`. */
    @Test
    fun `the release tag header is encoded like the server's`() {
        assertEquals("launcher-v0.32.0@99", releaseTagHeader("launcher-v0.32.0@99"))
        assertEquals("1.0%20beta", releaseTagHeader("1.0 beta"))
        assertEquals("bl%C3%A5b%C3%A6r", releaseTagHeader("blåbær"))
        assertEquals("100%25", releaseTagHeader("100%"))
        assertFalse(releaseTagMismatch("v1.0@14", "v1.0@14"))
        assertTrue(releaseTagMismatch("v1.1@15", "v1.0@14"))
        // An older server sends none: not checkable, not a mismatch.
        assertFalse(releaseTagMismatch(null, "v1.0@14"))
    }

    // ---- files and records ---------------------------------------------------------------------

    @Test
    fun `one stable partial per app and release`() {
        val name = partialFileName(7, "v26.09.4@14")
        assertTrue(name, Regex("""7-[0-9a-f]{16}\.part""").matches(name))
        assertEquals(name, partialFileName(7, "v26.09.4@14"))
        assertTrue(name != partialFileName(7, "v26.09.5@15"))
        assertTrue(name != partialFileName(8, "v26.09.4@14"))
    }

    private fun wanted(id: Long, tag: String, launcher: Boolean = false, sha: String? = "ab") =
        WantedDownload(id, tag, "App $id", launcher, "/api/devices/apps/$id/download", sha)

    @Test
    fun `the records become exactly what the list wants`() {
        val kept = DownloadRecord(1, "v1@1", "App 1", downloadUrl = "/old", etag = "\"e\"", total = 99, firstSeenMs = 5, hashFailures = 1)
        val replaced = DownloadRecord(2, "v1@2", firstSeenMs = 5)
        val deselected = DownloadRecord(3, "v1@3", firstSeenMs = 5)
        val after = reconcileRecords(
            listOf(kept, replaced, deselected),
            listOf(wanted(1, "v1@1"), wanted(2, "v2@2"), wanted(4, "v1@4", launcher = true, sha = null)),
            now,
        )
        assertEquals(
            listOf(
                // Same release: resume state and first sight kept, the list's details refreshed.
                kept.copy(downloadUrl = "/api/devices/apps/1/download", sha256 = "ab"),
                DownloadRecord(2, "v2@2", "App 2", false, "/api/devices/apps/2/download", "ab", firstSeenMs = now),
                DownloadRecord(4, "v1@4", "App 4", true, "/api/devices/apps/4/download", null, firstSeenMs = now),
            ),
            after,
        )
    }

    @Test
    fun `the sweep keeps record partials and the active file`() {
        val records = listOf(DownloadRecord(1, "v1@1"), DownloadRecord(2, "v2@2"))
        val files = listOf(partialFileName(1, "v1@1"), partialFileName(2, "v2@2"), partialFileName(2, "v1@2"), "junk.tmp", "9-0000.part")
        assertEquals(
            listOf(partialFileName(2, "v1@2"), "junk.tmp", "9-0000.part"),
            sweepFiles(files, records, activeFile = null),
        )
        // The runner's current file is never swept; it notices a dropped record itself.
        assertEquals(listOf("junk.tmp", "9-0000.part"), sweepFiles(files, records, activeFile = partialFileName(2, "v1@2")))
        assertEquals(files, sweepFiles(files, emptyList(), null))
    }

    @Test
    fun `without a list only installed releases are dropped`() {
        val records = listOf(DownloadRecord(1, "v1@1"), DownloadRecord(2, "v2@2"), DownloadRecord(3, "v1@3"))
        assertEquals(
            listOf(DownloadRecord(2, "v2@2")),
            installedRecords(records, mapOf(1L to "v0@0", 2L to "v2@2", 3L to null)),
        )
    }

    @Test
    fun `old cache files go once an hour old`() {
        val files = listOf(
            "tracked_app_5_123.apk" to now - LEGACY_FILE_AGE_MS,
            "tracked_app_6_456.apk" to now - LEGACY_FILE_AGE_MS + 1,
            "other.apk" to 0L,
            "tracked_app_7_789.tmp" to 0L,
        )
        assertEquals(listOf("tracked_app_5_123.apk"), legacyCacheFilesToDelete(files, now))
    }

    @Test
    fun `catalog apps go first, our own update last`() {
        val records = listOf(DownloadRecord(1, "l", isLauncher = true), DownloadRecord(2, "a"), DownloadRecord(3, "b"))
        assertEquals(listOf(2L, 3L, 1L), downloadOrder(records).map { it.appId })
    }

    @Test
    fun `a record decodes with defaults for anything missing`() {
        val record = ServerJson.decodeFromString(DownloadRecord.serializer(), """{"app_id":3,"tag":"v1","future":1}""")
        assertEquals(DownloadRecord(3, "v1"), record)
        val round = ServerJson.decodeFromString(
            DownloadRecord.serializer(),
            ServerJson.encodeToString(DownloadRecord.serializer(), DownloadRecord(3, "v1", etag = "\"x\"", total = 5, firstSeenMs = 9)),
        )
        assertEquals(DownloadRecord(3, "v1", etag = "\"x\"", total = 5, firstSeenMs = 9), round)
    }
}
