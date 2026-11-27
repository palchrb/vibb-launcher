package com.kidslauncher.mdm.server

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Time rules keep their own clock ([TrustedClock]): a new wall-clock read in the code that decides - the time rules,
 * calls, zones, Play, the assistant, the logins, the lock and the server files below - fails here, until it goes through
 * [TrustedClock] or is added to [knownReads] by its line, with its reason. The reads kept are display and report stamps,
 * Android's own stamps compared with Android's own clock (the call log, a package's install time), and windows started
 * and checked on the wall clock and bounded by elapsed realtime (they fail closed).
 */
class TrustedClockScanTest {
    private val read = Regex("""System\.currentTimeMillis\(\)|\b(LocalDateTime|LocalTime|LocalDate|Instant|ZonedDateTime|OffsetDateTime)\.now\(|\bDate\(\)|Calendar\.getInstance\(|\bClock\.system""")

    private val dirs = listOf("timerules", "calls", "zones", "play", "assistant", "logins", "lock")
    private val serverFiles = listOf(
        "ParentPin.kt", "ParentPinLockout.kt", "TrustedClock.kt", "TrustedClockPlan.kt", "LocateCommands.kt",
        "MdmSyncWorker.kt", "UpdateFence.kt", "WindowRelock.kt", "PowerEvents.kt",
        // Design 129 §4.3: the signer's time is the server's signature clock (elapsed realtime + the answers' offset),
        // else TrustedClock's - never a wall-clock read; the key book's challenge times are TrustedClock's too.
        "DeviceCredential.kt", "ServerAuth.kt", "KeyRegistration.kt", "Enrollment.kt",
    )

    /** The places' pure rules (design 125, 125b): the cell report's 4 h and the Apple rows' times decide on elapsed
     * realtime or a passed-in minute, never a clock read of their own (code QA L4). */
    private val placesFiles = listOf("PlacesPlan.kt", "ApplePlan.kt", "CellPlan.kt")

    /** The wall-clock reads that stay: per file, each line's text (trimmed). */
    private val knownReads: Map<String, List<String>> = mapOf(
        // Design 139 §4.1: Android's usage events are stamped by the wall clock, so the fold's read window is too (the
        // clock is locked to automatic time while the phone has a group; the fold caps each span by elapsed realtime)
        "timerules/AppGroupUsage.kt" to listOf(
            "val planned = planRead(ledger.foldPoint, System.currentTimeMillis(), SystemClock.elapsedRealtime(), BootClock.bootCount(), checked)",
        ),
        // Android's call log stamps (an emergency call) and the route's own stamps
        "assistant/AirplaneRoute.kt" to listOf(
            "val now = System.currentTimeMillis()",
            "AirplaneRuntime.recordKidOnStart(context, if (by == AirplaneBy.KID) System.currentTimeMillis() else null)",
        ),
        // Android's call log stamps (an emergency call) and the route's own stamps
        "assistant/AirplaneRuntime.kt" to listOf(
            "fun emergencyNow(context: Context, nowMs: Long = System.currentTimeMillis()): Boolean {",
            "if (error == null) edit.remove(KEY_LAST_ERROR).remove(KEY_LAST_ERROR_AT) else edit.putString(KEY_LAST_ERROR, error).putLong(KEY_LAST_ERROR_AT, System.currentTimeMillis())",
            "val now = System.currentTimeMillis()",
            "val now = System.currentTimeMillis()",
            "val now = System.currentTimeMillis()",
        ),
        // Android's call log and the emergency records are stamped by Android's clock and compared with it (the windows bounded by elapsed realtime); the rest display and report stamps
        "calls/BlockedCallLog.kt" to listOf(
            "val now = System.currentTimeMillis()",
        ),
        // Android's call log and the emergency records are stamped by Android's clock and compared with it (the windows bounded by elapsed realtime); the rest display and report stamps
        "calls/BlockedNumbersRuntime.kt" to listOf(
            "putLong(KEY_LAST, System.currentTimeMillis())",
        ),
        // Android's call log and the emergency records are stamped by Android's clock and compared with it (the windows bounded by elapsed realtime); the rest display and report stamps
        "calls/CallStateReport.kt" to listOf(
            "val now = System.currentTimeMillis()",
        ),
        // Android's call log and the emergency records are stamped by Android's clock and compared with it (the windows bounded by elapsed realtime); the rest display and report stamps
        "calls/CallSystem.kt" to listOf(
            "val placedAtMs = System.currentTimeMillis()",
            "val now = System.currentTimeMillis()",
            "readOutgoingCalls(context, System.currentTimeMillis()) != null",
            "fun callbackWindowUntil(context: Context, nowMs: Long = System.currentTimeMillis()): Long? =",
            "fun lastEmergencyCallMs(context: Context, nowMs: Long = System.currentTimeMillis()): Long? {",
            "emergencyRecord(System.currentTimeMillis(), SystemClock.elapsedRealtime(), bootCount(context), atMs, windowMs, placed),",
            "window, System.currentTimeMillis(), SystemClock.elapsedRealtime(), bootCount(context), storedWindowMs(context),",
            "fun recordInCallUiFailed(context: Context, nowMs: Long = System.currentTimeMillis()) {",
        ),
        // Android's call log and the emergency records are stamped by Android's clock and compared with it (the windows bounded by elapsed realtime); the rest display and report stamps
        "calls/ContactRequests.kt" to listOf(
            "fun add(context: Context, ok: RequestCheck.Ok, draft: File? = null, nowMs: Long = System.currentTimeMillis()) {",
            "fun sweepDrafts(context: Context, keep: String?, nowMs: Long = System.currentTimeMillis()) {",
            "val reconciled = reconcileContactRequests(records, rows, contacts, System.currentTimeMillis())",
            "val now = System.currentTimeMillis()",
        ),
        // Android's call log and the emergency records are stamped by Android's clock and compared with it (the windows bounded by elapsed realtime); the rest display and report stamps
        "calls/ElementCallLogStore.kt" to listOf(
            "val nowMs = System.currentTimeMillis()",
            "val nowMs = System.currentTimeMillis()",
        ),
        // Android's call log and the emergency records are stamped by Android's clock and compared with it (the windows bounded by elapsed realtime); the rest display and report stamps
        "calls/EmergencyCall.kt" to listOf(
            "val openedAtMs = System.currentTimeMillis()",
        ),
        // Android's call log and the emergency records are stamped by Android's clock and compared with it (the windows bounded by elapsed realtime); the rest display and report stamps
        "calls/InCallActivity.kt" to listOf(
            "if (since > 0) DateUtils.formatElapsedTime((System.currentTimeMillis() - since) / 1000) else \"\"",
        ),
        // Android's call log and the emergency records are stamped by Android's clock and compared with it (the windows bounded by elapsed realtime); the rest display and report stamps
        "calls/KidContactPhotos.kt" to listOf(
            "records + (contact.id to KidPhotoRecord(name, contact.number, System.currentTimeMillis()))",
            "val after = records + (contactId to KidPhotoRecord(name, number, System.currentTimeMillis()))",
        ),
        // Android's call log and the emergency records are stamped by Android's clock and compared with it (the windows bounded by elapsed realtime); the rest display and report stamps
        "calls/KidInCallService.kt" to listOf(
            "emergencyConnects.connected(call, details.connectTimeMillis, details.creationTimeMillis, System.currentTimeMillis()) {",
        ),
        // Android's call log and the emergency records are stamped by Android's clock and compared with it (the windows bounded by elapsed realtime); the rest display and report stamps
        "calls/MissedCallsRepo.kt" to listOf(
            "fun summaries(context: Context, state: CallPolicyState, nowMs: Long = System.currentTimeMillis()): Map<String, MissedSummary> {",
            "fun contactMissed(context: Context, state: CallPolicyState, nowMs: Long = System.currentTimeMillis()): Map<String, ContactMissed> {",
            "nowMs: Long = System.currentTimeMillis(),",
            "fun recentLog(context: Context, nowMs: Long = System.currentTimeMillis()): List<CallLogEntry> =",
            "fun markSeen(context: Context, number: String, upToMs: Long = System.currentTimeMillis()) =",
            "fun markDismissed(context: Context, number: String, upToMs: Long, nowMs: Long = System.currentTimeMillis()): Boolean = try {",
        ),
        // Android's call log and the emergency records are stamped by Android's clock and compared with it (the windows bounded by elapsed realtime); the rest display and report stamps
        "calls/PhoneBookActivity.kt" to listOf(
            "val items = recentItems(loaded.recent, java.time.LocalDate.now(), inputs?.emergency.orEmpty())",
        ),
        // display, the glance's calendar and alarm, the boot cover's and the legacy lock's guards and the VoIP cap window (started and checked on the wall clock, bounded by elapsed realtime)
        "lock/BootCover.kt" to listOf(
            "prefs(app).edit().putLong(KEY_LAUNCHER_HANDOVER_AT, System.currentTimeMillis()).apply()",
            "prefs(app).edit().putLong(KEY_LAUNCHER_HANDOVER_AT, System.currentTimeMillis()).apply()",
            "val next = coverArmed(arms(app), BootCoverGuard.bootCount(app), System.currentTimeMillis())",
        ),
        // display, the glance's calendar and alarm, the boot cover's and the legacy lock's guards and the VoIP cap window (started and checked on the wall clock, bounded by elapsed realtime)
        "lock/BootCoverGuard.kt" to listOf(
            "update(context) { coverShown(it, bootCount(context), System.currentTimeMillis(), elapsedMs) }.shownElapsedMs",
            "update(context) { it.copy(handedOverAtMs = System.currentTimeMillis()) }",
        ),
        // display, the glance's calendar and alarm, the boot cover's and the legacy lock's guards and the VoIP cap window (started and checked on the wall clock, bounded by elapsed realtime)
        "lock/GlanceCalendar.kt" to listOf(
            "if (LocalDate.now() != loadedFor) load() else changed()",
            "LocalDate.now().let { day ->",
            "fun reminderRow(now: Long = System.currentTimeMillis()): GlanceCalendarRow? {",
            "return GlanceCalendarRow(up.eventId, up.title, up.beginMs, up.minutesLeft, loadedFor?.toEpochDay() ?: LocalDate.now().toEpochDay(), up.color, up.calendarName)",
            "fun next(now: Long = System.currentTimeMillis()): GlanceNext? {",
            "fun armBoundary(now: Long = System.currentTimeMillis()) {",
        ),
        // display, the glance's calendar and alarm, the boot cover's and the legacy lock's guards and the VoIP cap window (started and checked on the wall clock, bounded by elapsed realtime)
        "lock/GlancePanel.kt" to listOf(
            "return when (relativeDay(day, LocalDate.now(zone).toEpochDay())) {",
        ),
        // display, the glance's calendar and alarm, the boot cover's and the legacy lock's guards and the VoIP cap window (started and checked on the wall clock, bounded by elapsed realtime)
        "lock/LockClockFace.kt" to listOf(
            "val now = System.currentTimeMillis()",
        ),
        // display, the glance's calendar and alarm, the boot cover's and the legacy lock's guards and the VoIP cap window (started and checked on the wall clock, bounded by elapsed realtime)
        "lock/PinLockActivity.kt" to listOf(
            "val guard = guardOnCreate(stored, System.currentTimeMillis())",
        ),
        // display, the glance's calendar and alarm, the boot cover's and the legacy lock's guards and the VoIP cap window (started and checked on the wall clock, bounded by elapsed realtime)
        "lock/PinLockRuntime.kt" to listOf(
            "com.kidslauncher.mdm.badges.AlarmNoticeReader.ringing(clockPackages()), rememberedAlarmMs, System.currentTimeMillis(),",
            "val guard = guardRearm(PinLockStore.guard(app), System.currentTimeMillis())",
            "rememberedAlarmMs, next?.triggerTime, next?.showIntent?.creatorPackage, clockPackages(), System.currentTimeMillis(),",
        ),
        // display, the glance's calendar and alarm, the boot cover's and the legacy lock's guards and the VoIP cap window (started and checked on the wall clock, bounded by elapsed realtime)
        "lock/PinLockStore.kt" to listOf(
            "saveGuard(context, guardOnCrash(guard(context), System.currentTimeMillis()))",
        ),
        // display, the glance's calendar and alarm, the boot cover's and the legacy lock's guards and the VoIP cap window (started and checked on the wall clock, bounded by elapsed realtime)
        "lock/VoipCalls.kt" to listOf(
            "nowWallMs = System.currentTimeMillis(),",
            "val next = voipRangAfter(stored, pkg, System.currentTimeMillis())",
            "val entries = voipRangEntries(decodeVoipRang(raw), System.currentTimeMillis())",
        ),
        // display, the glance's calendar and alarm, the boot cover's and the legacy lock's guards and the VoIP cap window (started and checked on the wall clock, bounded by elapsed realtime)
        "lock/WeatherLockPanel.kt" to listOf(
            "val recent = WeatherStore.answer?.checkedAtMs?.let { System.currentTimeMillis() - it <= WEATHER_HIDE_AFTER_MS + HOLD_EXTRA_MS } == true",
            "val now = System.currentTimeMillis()",
            "val hasCard = line?.card != null && WeatherStore.answer?.let { a -> line?.let { weatherCard(a, it, System.currentTimeMillis(), ZoneId.systemDefault()) } } != null",
        ),
        // the window's record and report stamps, bounded by elapsed realtime
        "logins/LinkGateRuntime.kt" to listOf(
            "val now = System.currentTimeMillis()",
        ),
        // the window's record and report stamps, bounded by elapsed realtime
        "logins/LoginWindow.kt" to listOf(
            "val record = windowOpenRecord(readRecord(context), now, System.currentTimeMillis(), BootClock.bootCount(), processToken(), session)",
            "val last = LastWindow(record.openedMs, System.currentTimeMillis(), end, record.session)",
        ),
        // install mode: a window on the wall clock bounded by elapsed realtime, moved onto the decisions' clock for the alarm
        "play/PlayRuntime.kt" to listOf(
            "installModeActive(installModeStart(context), System.currentTimeMillis(), SystemClock.elapsedRealtime(), BootClock.bootCount())",
            "installModeUntil(context)?.let { edges += Instant.ofEpochMilli(it - System.currentTimeMillis() + TrustedClock.nowMs()) }",
        ),
        // a fix without an elapsed time, the active fetch's stamp
        "server/LocateCommands.kt" to listOf(
            "?: (System.currentTimeMillis() - fix.time),",
            "saveLastActiveFetchAt(context, System.currentTimeMillis())",
            "saveLastActiveFetchAt(context, System.currentTimeMillis())",
        ),
        // report and log stamps, the zone heartbeat's own stamps, a fix without an elapsed time
        "server/MdmSyncWorker.kt" to listOf(
            "val policyGetStartedMs = System.currentTimeMillis()",
            "?: (System.currentTimeMillis() - it.timeMs)",
            "seenOf(policyHandshake, ServerPinStore.network(context), System.currentTimeMillis()),",
            "val now = System.currentTimeMillis()",
            "?: (System.currentTimeMillis() - location.time)) / 1000,",
            "val now = System.currentTimeMillis()",
            "val now = System.currentTimeMillis()",
            "val now = System.currentTimeMillis()",
        ),
        // the boot's and the upload's stamps
        "server/PowerEvents.kt" to listOf(
            "nowMs = System.currentTimeMillis(),",
            "val now = System.currentTimeMillis()",
            "val now = System.currentTimeMillis()",
        ),
        // the clock itself
        "server/TrustedClock.kt" to listOf(
            "* `System.currentTimeMillis()` (`TrustedClockScanTest`). Before [init] - JVM tests, a process that never ran",
            "val wall = System.currentTimeMillis()",
            "fun skewMs(): Long = System.currentTimeMillis() - nowMs()",
            "val taken = takeAnchor(load(context), anchor, System.currentTimeMillis(), SystemClock.elapsedRealtime(), BootClock.bootCount())",
            "val wall = System.currentTimeMillis()",
            "timeSetAnchor(state, System.currentTimeMillis(), SystemClock.elapsedRealtime(), BootClock.bootCount())?.let {",
            "val wall = System.currentTimeMillis()",
        ),
        // a package's install time is stamped by Android's clock; the fence is bounded by elapsed realtime
        "server/UpdateFence.kt" to listOf(
            "nowWallMs = System.currentTimeMillis(),",
            "startedWallMs = System.currentTimeMillis(),",
            ".copy(reason = reason.wire, releasedAtMs = System.currentTimeMillis()),",
            "(lastSummary(app) ?: FenceSummary()).copy(reason = FenceReleaseReason.ORPHAN.wire, releasedAtMs = System.currentTimeMillis()),",
        ),
        // the relock report's stamps
        "server/WindowRelock.kt" to listOf(
            "atMs = System.currentTimeMillis(),",
            "val now = System.currentTimeMillis()",
            "override fun now() = System.currentTimeMillis()",
        ),
        // the override's and the pause's windows on the wall clock, moved onto the decisions' clock for the alarm
        "timerules/TimeRulesRuntime.kt" to listOf(
            "val wall = System.currentTimeMillis()",
        ),
        // a unique id, not a time
        "timerules/ZenRuleRuntime.kt" to listOf(
            "if (fresh) builder.appendPath(System.currentTimeMillis().toString())",
        ),
        // the moving series' delays and request stamp
        "zones/ZoneSeries.kt" to listOf(
            "val first = nextCheckDelay(LocateCommands.newestKnown(), zones, System.currentTimeMillis()) ?: return",
            "val next = nextCheckDelay(LocateCommands.newestKnown(), zones, System.currentTimeMillis()) ?: CHECK_MAX_DELAY_MS",
            "val requestedAt = System.currentTimeMillis()",
        ),
    )

    private fun root(): File = listOf(File("src/main/java/com/kidslauncher/mdm"), File("app/src/main/java/com/kidslauncher/mdm")).first { it.isDirectory }

    @Test
    fun `no new wall-clock read where the phone decides`() {
        val root = root()
        val files = dirs.flatMap { d -> File(root, d).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() } +
            serverFiles.map { File(root, "server/$it") } + placesFiles.map { File(root, "places/$it") }
        val found = files.associate { file ->
            file.relativeTo(root).path to file.readLines().map { it.trim() }.filter { read.containsMatchIn(it) }.sorted()
        }.filterValues { it.isNotEmpty() }
        for ((path, lines) in found) {
            val known = knownReads[path].orEmpty()
            for (line in lines.toSet()) {
                assertEquals("$path reads the wall clock here: use TrustedClock, or add the line to knownReads with its reason\n$line",
                    known.count { it == line }, lines.count { it == line })
            }
        }
        for ((path, lines) in knownReads) {
            assertEquals("$path: a known read went or changed - update knownReads", lines.sorted(), found[path].orEmpty())
        }
    }
}
