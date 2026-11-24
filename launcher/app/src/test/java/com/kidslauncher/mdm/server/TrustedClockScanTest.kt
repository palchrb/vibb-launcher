package com.kidslauncher.mdm.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Time rules keep their own clock ([TrustedClock]): a new wall-clock read in the code that decides - the time rules,
 * calls, zones, Play, the assistant, the logins and the server files below - fails here, until it goes through
 * [TrustedClock] or is added to [knownReads] with its reason. The reads kept are display and log stamps, Android's own
 * stamps compared with Android's own clock (the call log, a package's install time), and windows started and checked
 * on the wall clock and bounded by elapsed realtime (they fail closed).
 */
class TrustedClockScanTest {
    private val read = Regex("""System\.currentTimeMillis\(\)|(LocalDateTime|LocalTime|LocalDate|Instant|ZonedDateTime)\.now\(""")

    private val dirs = listOf("timerules", "calls", "zones", "play", "assistant", "logins")
    private val serverFiles = listOf(
        "ParentPin.kt", "ParentPinLockout.kt", "TrustedClock.kt", "TrustedClockPlan.kt", "LocateCommands.kt",
        "MdmSyncWorker.kt", "UpdateFence.kt", "WindowRelock.kt", "PowerEvents.kt",
    )

    /** The wall-clock reads that stay, per file. */
    private val knownReads = mapOf(
        // Android's call log and the emergency records are stamped by Android's clock and compared with it; the
        // emergency windows are bounded by elapsed realtime.
        "calls/CallSystem.kt" to 8,
        "assistant/AirplaneRuntime.kt" to 5,
        "assistant/AirplaneRoute.kt" to 2,
        "calls/MissedCallsRepo.kt" to 6,
        "calls/BlockedCallLog.kt" to 1,
        "calls/ElementCallLogStore.kt" to 2,
        "calls/EmergencyCall.kt" to 1,
        "calls/KidInCallService.kt" to 1,
        "calls/CallStateReport.kt" to 1,
        // Display and log stamps: a call's duration, the phone book's recent list, the parent's report rows.
        "calls/InCallActivity.kt" to 1,
        "calls/PhoneBookActivity.kt" to 1,
        "calls/ContactRequests.kt" to 4,
        "calls/KidContactPhotos.kt" to 2,
        "calls/BlockedNumbersRuntime.kt" to 1,
        "logins/LoginWindow.kt" to 4,
        "logins/LinkGateRuntime.kt" to 1,
        "timerules/ZenRuleRuntime.kt" to 1,
        "zones/ZoneSeries.kt" to 3,
        "zones/ZoneWatch.kt" to 2,
        // Windows on the wall clock bounded by elapsed realtime (install mode, the override and the pause), moved onto
        // the decisions' clock for the alarm.
        "play/PlayRuntime.kt" to 2,
        "timerules/TimeRulesRuntime.kt" to 1,
        // The clock itself.
        "server/TrustedClock.kt" to 6,
        // A fix without an elapsed time (the wall clock against its own time), the report's stamps.
        "server/LocateCommands.kt" to 3,
        "server/MdmSyncWorker.kt" to 8,
        // A package's install time is stamped by Android's clock; the fence is bounded by elapsed realtime.
        "server/UpdateFence.kt" to 4,
        // The relock report's stamps.
        "server/WindowRelock.kt" to 3,
        "server/PowerEvents.kt" to 3,
    )

    private fun root(): File = listOf(File("src/main/java/com/kidslauncher/mdm"), File("app/src/main/java/com/kidslauncher/mdm")).first { it.isDirectory }

    @Test
    fun `no new wall-clock read where the phone decides`() {
        val root = root()
        val files = dirs.flatMap { d -> File(root, d).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() } +
            serverFiles.map { File(root, "server/$it") }
        val found = files.associate { it.relativeTo(root).path to read.findAll(it.readText()).count() }.filterValues { it > 0 }
        for ((path, count) in found) {
            val known = knownReads[path] ?: 0
            assertTrue("$path reads the wall clock $count times (known: $known): use TrustedClock", count <= known)
        }
        assertEquals("a known read went: lower its count in knownReads", knownReads.filterKeys { it in found }.toSortedMap(), found.filterKeys { it in knownReads }.toSortedMap())
    }
}
