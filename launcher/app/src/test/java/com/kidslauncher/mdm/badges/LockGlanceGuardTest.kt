package com.kidslauncher.mdm.badges

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Design 25 §5: what the lock's glance page reads, keeps and does is guarded by source scans (like
 * ElementRoomsTest for ElementDmReader). Part 1 (step 2): the reader reads only the allowlisted
 * fields, text only at [GlanceLevel.TEXT], never an action, remote input or picture; nothing glance
 * cancels, snoozes or marks shown; nothing is logged but package names, counts and exception
 * classes; nothing is persisted or reaches the sync, the API, a DTO or the crash report; every entry
 * point catches everything (QA Medium 2).
 */
class LockGlanceGuardTest {

    private val sources = listOf("src/main/java", "app/src/main/java").map(::File).first { it.isDirectory }

    private fun file(name: String): File = sources.walkTopDown().first { it.name == name }

    /** The code without comments (KDoc may name what is *not* read). */
    private fun code(name: String): String = strip(file(name).readText())

    private fun strip(text: String): String =
        text.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "").replace(Regex("""//[^\n]*"""), "")

    /** The glance's own files (step 3 adds the lock's). */
    private val glanceFiles = listOf("GlanceReader.kt", "GlanceStore.kt", "GlanceRows.kt")

    private val reader by lazy { code("GlanceReader.kt") }

    /** A function's body: from `fun name(` to the next line that closes a member at its indent. */
    private fun body(code: String, name: String): String {
        val start = code.indexOf("fun $name(")
        assertTrue("fun $name exists", start >= 0)
        val rest = code.substring(start)
        val end = Regex("""\n    }\n|\n    (private |internal )?(inline )?fun |\n}""").find(rest, 1)?.range?.first ?: rest.length
        return rest.substring(0, end)
    }

    // ---- the reader -----------------------------------------------------------------------------

    @Test
    fun `the reader's imports are exactly these`() {
        val imports = Regex("""^import (\S+)""", RegexOption.MULTILINE).findAll(reader).map { it.groupValues[1] }.toSet()
        assertEquals(
            setOf(
                "android.app.Notification", "android.app.PendingIntent", "android.app.Person", "android.os.Bundle",
                "android.os.Parcelable", "android.os.Process", "android.service.notification.NotificationListenerService",
                "android.service.notification.StatusBarNotification", "android.util.Log", "java.lang.ref.WeakReference",
            ),
            imports,
        )
    }

    /** Every identifier the reader may use (imports, strings and comments aside). Anything new -
     * another Notification field, Bundle method, Person member or helper - fails until reviewed here. */
    private val readerIdentifiers = setOf(
        // Kotlin
        "object", "fun", "val", "var", "private", "const", "inline", "if", "else", "return", "try", "catch", "null", "true",
        "this", "for", "in", "and", "when", "class", "java", "it", "to", "first", "second", "let", "takeIf", "orEmpty",
        "filter", "filterIsInstance", "lastOrNull", "isNotEmpty", "size", "mutableListOf", "add", "get",
        "String", "Boolean", "Unit", "Pair", "Throwable", "WeakReference", "e",
        // ours
        "GlanceReader", "LOG_TAG", "SENDER_PERSON", "SENDER", "MESSAGE_TEXT", "SWEEP", "service", "connected",
        "disconnected", "resweep", "posted", "removed", "rankingUpdate", "sweep", "read", "senderOf", "readTexts",
        "guarded", "source", "work", "entry", "entries", "active", "rankingMap", "ranking", "ranked", "candidate",
        "secret", "extras", "messages", "fromOthers", "level", "newest", "group", "sender", "title", "text", "notice",
        "message", "messaging", "policy", "n", "sbn", "w", "Log",
        "GlanceStore", "clearNotices", "remove", "put", "updateSuspended", "replaceAll", "apps", "showText",
        "GlanceCandidateFacts", "glanceCandidate", "groupSummary", "ongoing", "clearable", "media", "importance",
        "category", "glanceLevel", "ticked", "GlanceLevel", "TEXT", "glanceTitle", "conversationTitle", "newestSender",
        "cleanGlanceText", "GLANCE_TITLE_MAX", "GLANCE_TEXT_MAX", "glanceGroupText", "glanceCount", "isMediaNotification",
        "glanceSuspended", "missedCall", "glanceMissedCall",
        "GlanceNotice", "key", "packageName", "conversationKey", "count", "whenMs", "postTimeMs", "suspended",
        // the platform: only these members
        "NotificationListenerService", "RankingMap", "Ranking", "getRanking", "isSuspended", "channel",
        "lockscreenVisibility", "activeNotifications", "currentRanking",
        "StatusBarNotification", "notification", "user", "isOngoing", "isClearable", "postTime", "Process", "myUserHandle",
        "Notification", "flags", "FLAG_GROUP_SUMMARY", "visibility", "VISIBILITY_SECRET", "number", "shortcutId",
        "contentIntent", "PendingIntent", "EXTRA_MESSAGES", "EXTRA_IS_GROUP_CONVERSATION", "EXTRA_CONVERSATION_TITLE",
        "EXTRA_TITLE", "EXTRA_TEXT", "Bundle", "EMPTY", "Parcelable", "getParcelableArray", "getParcelable",
        "getCharSequence", "getBoolean", "containsKey", "Person", "name",
    )

    @Test
    fun `the reader reads only the allowlisted fields`() {
        val body = reader.lines().filterNot { it.startsWith("import ") || it.startsWith("package ") }.joinToString("\n")
        val literal = Regex(""""[^"]*"""")
        assertEquals(
            setOf("\"GlanceReader\"", "\"sender_person\"", "\"sender\"", "\"text\"", "\"a sweep\"", "\"Glance: skipped \$source: \${e.javaClass.simpleName}\""),
            literal.findAll(body).map { it.value }.toSet(),
        )
        val code = body.replace(literal, "\"\"")
        val identifiers = Regex("""[A-Za-z_][A-Za-z0-9_]*""").findAll(code).map { it.value }.toSet()
        assertEquals("not allowlisted", emptySet<String>(), identifiers - readerIdentifiers)
        // Bundle reads: only these calls on these keys.
        val bundleReads = Regex("""\b(getParcelableArray|getParcelable|getBoolean|containsKey|getCharSequence|getString|getInt|get)\(\s*([A-Za-z_.]+)""")
            .findAll(code).map { it.groupValues[1] to it.groupValues[2] }.toSet()
        assertEquals(
            setOf(
                "getParcelableArray" to "Notification.EXTRA_MESSAGES",
                "containsKey" to "SENDER_PERSON",
                "containsKey" to "SENDER",
                "getBoolean" to "Notification.EXTRA_IS_GROUP_CONVERSATION",
                "getCharSequence" to "Notification.EXTRA_CONVERSATION_TITLE",
                "getCharSequence" to "Notification.EXTRA_TITLE",
                "getParcelable" to "SENDER_PERSON",
                "getCharSequence" to "SENDER",
                "getCharSequence" to "MESSAGE_TEXT",
                "getCharSequence" to "Notification.EXTRA_TEXT",
            ),
            bundleReads,
        )
        // Of a Person only the name: every Person read ends in `?.name`, and `.name` is read nowhere else.
        val personReads = Regex("""Person::class\.java\)(\??\.[A-Za-z_]+)?""").findAll(code).map { it.groupValues[1] }.toList()
        assertEquals(listOf("?.name"), personReads)
        assertEquals(1, Regex("""\.name\b""").findAll(code).count())
        // `.key` only of the StatusBarNotification (the notification key - never a Person's).
        assertTrue(Regex("""(\w+)\??\.key\b""").findAll(code).all { it.groupValues[1] == "sbn" })
    }

    /** QA Medium 1, code QA Low 4: both suspension reads go through the pure rule (GlanceRowsTest). */
    @Test
    fun `the reader's suspended flag is the pure rule's - no ranking counts as suspended`() {
        assertTrue(reader.contains("suspended = glanceSuspended(ranked, ranking.isSuspended)"))
        assertTrue(reader.contains("glanceSuspended(rankingMap?.getRanking(key, ranking) == true, ranking.isSuspended)"))
        assertEquals(2, Regex("""\bisSuspended\b""").findAll(reader).count())
    }

    @Test
    fun `text is read only inside readTexts, and readTexts only at TEXT`() {
        val texts = body(reader, "readTexts")
        val outside = reader.replace(texts, "")
        // The key constant's declaration is the one mention outside.
        assertEquals(1, Regex("""\bMESSAGE_TEXT\b""").findAll(outside).count())
        assertTrue(outside.contains("private const val MESSAGE_TEXT = \"text\""))
        assertFalse(Regex("""\bEXTRA_TEXT\b""").containsMatchIn(outside))
        assertTrue(texts.contains("MESSAGE_TEXT") && texts.contains("Notification.EXTRA_TEXT"))
        val calls = Regex("""^.*\breadTexts\(.*$""", RegexOption.MULTILINE).findAll(outside).map { it.value }.toList()
        assertEquals(1, calls.size)
        assertTrue(calls.single(), calls.single().contains("if (level == GlanceLevel.TEXT) readTexts("))
    }

    @Test
    fun `none of the never-read members`() {
        for (member in listOf(
            "actions", "RemoteInput", "remoteInputs", "deleteIntent", "fullScreenIntent", "publicVersion", "EXTRA_PICTURE",
            "largeIcon", "smallIcon", "getLargeIcon", "getSmallIcon", "icon", "uri", "EXTRA_BIG_TEXT", "EXTRA_TEXT_LINES",
            "EXTRA_SUB_TEXT", "EXTRA_SUMMARY_TEXT", "EXTRA_INFO_TEXT", "tickerText", "EXTRA_MESSAGING_PERSON",
            "EXTRA_HISTORIC_MESSAGES", "EXTRA_PEOPLE_LIST", "extractMessagingStyleFromNotification", "MessagingStyle",
            "keySet", "getString", "bubbleMetadata", "getIntent", "creatorPackage",
        )) {
            assertFalse("GlanceReader reads $member", Regex("""\b$member\b""", RegexOption.IGNORE_CASE).containsMatchIn(reader))
        }
    }

    // ---- what the glance never does -------------------------------------------------------------

    @Test
    fun `nothing glance cancels, snoozes or marks a notification shown`() {
        for (name in glanceFiles) {
            for (call in listOf("cancelNotification", "cancelAllNotifications", "snoozeNotification", "setNotificationsShown", "migrateNotificationFilter")) {
                assertFalse("$name: $call", code(name).contains(call))
            }
        }
    }

    /** A log line in a glance file names a package, a count or an exception class - never a title,
     * name, text, key or shortcut id. */
    private val allowedInterpolations = setOf(
        "\$source", "\${e.javaClass.simpleName}", "\${next.apps.size}", "\${if (next.showText) \"on\" else \"off\"}",
        "\$what", "\$pkg", "\${next.candidate}", "\${next.rows}", "\${next.more}",
    )

    @Test
    fun `log lines carry only package names, counts and exception classes`() {
        for (name in glanceFiles + "GlancePanel.kt") {
            val lines = Regex("""\bLog\.\w\(.*""").findAll(code(name)).map { it.value }.toList()
            for (line in lines) {
                assertFalse("$name: $line logs a throwable", Regex(""",\s*e\)""").containsMatchIn(line))
                val interpolations = Regex("""\$\{[^}]*\}|\$[A-Za-z_]+""").findAll(line).map { it.value }.toSet()
                assertTrue("$name: $line", allowedInterpolations.containsAll(interpolations))
            }
        }
        assertFalse("GlanceRows logs", code("GlanceRows.kt").contains("Log."))
    }

    @Test
    fun `nothing glance is persisted`() {
        for (name in glanceFiles) {
            for (store in listOf(
                "SharedPreferences", "getSharedPreferences", "edit()", "PreferenceManager", "LauncherPreferences",
                "File", "openFileOutput", "DataStore", "createDeviceProtectedStorageContext", "PinLockStore",
            )) {
                assertFalse("$name: $store", code(name).contains(store))
            }
        }
    }

    @Test
    fun `no glance facts or rows in the sync, the API, a DTO, push or the crash report`() {
        val glanceTypes = Regex("""\b(GlanceNotice|GlanceRow|GlanceMessageRow|GlanceMissedRow|GlanceConversation|GlanceReader|GlanceLevel|GlancePolicy|GlanceCandidateFacts)\b""")
        val offenders = sources.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { f -> listOf("/mdm/server/", "/mdm/push/", "/mdm/crash/").any { it in f.invariantSeparatorsPath } }
            .filter { f ->
                val c = strip(f.readText())
                glanceTypes.containsMatchIn(c) || Regex("""GlanceStore\.(?!refreshPolicy\()""").containsMatchIn(c)
            }
            .map { it.name }
            .toList()
        assertTrue("Found in $offenders", offenders.isEmpty())
        // The sync only hands the policy over.
        val sync = code("MdmSyncWorker.kt")
        assertEquals(1, Regex("""GlanceStore\.""").findAll(sync).count())
        assertTrue(sync.contains("com.kidslauncher.mdm.badges.GlanceStore.refreshPolicy(freshPolicy.lockGlance, fresh = true)"))
        // The status report and the call state never name the glance.
        for (name in listOf("StatusReportRequest.kt", "CallStateReport.kt")) {
            assertFalse(name, Regex("""(?i)glance""").containsMatchIn(code(name)))
        }
    }

    // ---- part 2: the lock (step 3) ------------------------------------------------------------

    private val panel by lazy { code("GlancePanel.kt") }

    @Test
    fun `the glance panel opens nothing - a row only leads to the keypad`() {
        for (forbidden in listOf(
            "startActivity", ".send(", "sessionActivity", "LaunchIntent", "cancelNotification",
            "snoozeNotification", "setNotificationsShown", "Settings.", "getSharedPreferences", "edit()",
        )) {
            assertFalse("GlancePanel uses $forbidden", panel.contains(forbidden))
        }
        assertFalse("GlancePanel builds an Intent", Regex("""\bIntent\(""").containsMatchIn(panel))
        // Rows can't be swiped, long-pressed or dismissed from the lock.
        for (forbidden in listOf("setOnLongClickListener", "setOnTouchListener", "ItemTouchHelper", "Swipe")) {
            assertFalse("GlancePanel uses $forbidden", panel.contains(forbidden))
        }
    }

    @Test
    fun `the flashlight button toggles the torch and nothing else (design 41 §2)`() {
        val wiring = Regex("""glance\.lockGlanceTorch\.setOnClickListener \{([^}]*)\}""").findAll(panel).map { it.groupValues[1].trim() }.toList()
        assertEquals(listOf("torch?.toggle()"), wiring)
        // The port's toggle is the phone's Torch and nothing else (design 41).
        assertEquals("the button, the adapter and its Torch call", 3, Regex("""\btoggle\(""").findAll(panel).count())
        assertTrue(panel.contains("override fun toggle() = Torch.toggle(context.applicationContext)"))
        assertTrue(panel.contains("private val torch: TorchPort? = lockTorch(activity),"))
        // The panel never reaches the camera itself: no camera manager, torch mode or camera policy.
        for (forbidden in listOf("CameraManager", "setTorchMode", "setCameraDisabled", "CameraLock", "DevicePolicyManager")) {
            assertFalse("GlancePanel uses $forbidden", panel.contains(forbidden))
        }
        // The port has nothing that could open something: its members are the state, the toggle and
        // the listener.
        val port = code("LockGlance.kt").substringAfter("interface TorchPort {").substringBefore("\n}")
        val members = Regex("""\b(?:val|fun) (\w+)""").findAll(port).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("hasFlash", "lit", "refusedWhileLocked", "toggle", "addListener", "removeListener"), members)
    }

    @Test
    fun `the clock reader reads only the pinned clock app's three channels - title, text and the ended timer's action (design 42 §2)`() {
        val clockReader = code("ClockReader.kt")
        // Only after the package, the signer and the channel (clockKind) is anything read.
        assertTrue(clockReader.contains("clockKind(sbn.packageName, ClockApp.verified, n.channelId) ?: return null"))
        assertEquals(setOf("EXTRA_TITLE", "EXTRA_TEXT"), Regex("""Notification\.(EXTRA_\w+)""").findAll(clockReader).map { it.groupValues[1] }.toSet())
        assertEquals(2, Regex("""\.getCharSequence\(""").findAll(clockReader).count())
        // The actions only of an ended timer, judged by the pure clockStopAction (a broadcast, never an activity).
        assertTrue(clockReader.contains("if (kind == ClockKind.ENDED) n.actions.orEmpty().map { it.actionIntent } else emptyList()"))
        assertTrue(clockReader.contains("clockStopAction(kind, intents.map { ClockAction(it?.isActivity != false, it?.isBroadcast == true) })"))
        for (forbidden in listOf(
            "contentIntent", "fullScreenIntent", "deleteIntent", "contentView", "RemoteViews", "getParcelable", "remoteInputs",
            ".send(", "startActivity", "Intent(", "getString(", "SharedPreferences", "cancelNotification",
        )) {
            assertFalse("ClockReader uses $forbidden", clockReader.contains(forbidden))
        }
        for (line in Regex("""\bLog\.\w\(.*""").findAll(clockReader).map { it.value }) {
            val interpolations = Regex("""\$\{[^}]*\}|\$[A-Za-z_]+""").findAll(line).map { it.value }.toSet()
            assertEquals(line, setOf("\${e.javaClass.simpleName}"), interpolations)
        }
        val listener = code("BadgeListenerService.kt")
        for (call in listOf("ClockReader.connected(active)", "ClockReader.disconnected()", "ClockReader.posted(it)", "ClockReader.removed(it)")) {
            assertTrue(call, listener.contains(call))
        }
    }

    @Test
    fun `Stopp sends one broadcast with background starts denied, never an activity (design 42)`() {
        val actions = code("ClockActions.kt")
        assertEquals(1, Regex("""\.send\(""").findAll(actions).count())
        assertTrue(actions.contains("if (intent == null || intent.isActivity || !intent.isBroadcast) return"))
        assertTrue(actions.contains(".setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_DENIED)"))
        assertTrue(actions.contains("intent.send(context, 0, null, null, null, null, options)"))
        assertTrue(body(actions, "stop").contains("catch (e: Throwable)"))
        // The panel only hands the reader's intent over; it sends nothing itself.
        assertTrue(panel.contains("glance.lockTimerStop.setOnClickListener { ClockActions.stop(activity, ClockReader.stopIntent) }"))
        assertFalse(panel.contains(".send("))
    }

    @Test
    fun `the timer tile opens nothing without the keypad (design 42 §2)`() {
        assertTrue(panel.contains("glance.lockTimerTile.setOnClickListener { tapClock() }"))
        val tap = body(panel, "tapClock")
        assertTrue(tap.contains("captured = null"))
        assertTrue(tap.contains("step(GlanceStageEvent.Row(target.takeIf { taken }))"))
        val tile = code("ClockLockPanel.kt")
        for (forbidden in listOf("startActivity", ".send(", "PendingIntent", "Intent(", "setOnClickListener", "LaunchIntent")) {
            assertFalse("ClockLockPanel uses $forbidden", tile.contains(forbidden))
        }
        // The clock app opens only through the opener's existing launch, after the code.
        assertEquals(2, Regex("""\bstartActivity\(""").findAll(code("GlanceOpener.kt")).count())
    }

    @Test
    fun `the clock app counts only with its pinned signer - in every glue that decides it (qa-r6-b-code M3)`() {
        // ClockApp.installed goes through the signer check.
        val installed = body(code("ClockApp.kt"), "installed")
        assertTrue(installed, installed.contains("= clockAppVerified("))
        assertTrue(installed.contains("PackageManager.GET_SIGNING_CERTIFICATES"))
        // The alarm exemption's clock packages: Fossify only while verified.
        assertTrue(code("PinLockRuntime.kt").contains("fun clockPackages(): Set<String> = lockClockPackages(systemClockPackage, ClockApp.verified)"))
        // The kiosk-off helper pin: Fossify only when installed with the signer.
        assertTrue(code("AppEnforcer.kt").contains("clockApp = com.kidslauncher.mdm.lock.ClockApp.PACKAGE.takeIf { com.kidslauncher.mdm.lock.ClockApp.installed(context) },"))
        // And the system clock is resolved from the system's own handlers, never the next alarm's creator (L8).
        val runtime = code("PinLockRuntime.kt")
        assertTrue(runtime.contains("systemClockPackage = systemClockApp(app)"))
        assertTrue(body(runtime, "systemClockApp").contains("MATCH_SYSTEM_ONLY"))
    }

    @Test
    fun `the tiles' callbacks go through the coalescer, never a whole render (qa-r6-b-code L5)`() {
        assertTrue(panel.contains("private val music = MusicLockPanel(activity, glance) { musicDirty = true; tileChanges.request() }"))
        assertTrue(panel.contains("private val clock = ClockLockPanel(activity, glance) { clockDirty = true; tileChanges.request() }"))
        assertTrue(panel.contains("GlanceCoalescer({ run -> Choreographer.getInstance().postFrameCallback { run() } }) { tileChanged() }"))
        // A tick of one tile never shows the other: tileChanged renders only the source that changed.
        val changed = body(panel, "tileChanged")
        assertTrue(changed.contains("if (musicChanged) {") && changed.contains("if (clockChanged) {"))
        assertFalse(changed.contains("renderNow()"))
    }

    @Test
    fun `FLAG_SECURE is on the lock's window before its content`() {
        val activity = code("PinLockActivity.kt")
        val onCreate = body(activity, "onCreate")
        val secure = onCreate.indexOf("window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)")
        assertTrue(secure >= 0)
        assertTrue(secure < onCreate.indexOf("setContentView("))
        assertTrue(secure < onCreate.indexOf("ActivityPinLockBinding.inflate("))
    }

    @Test
    fun `every row view is accessibility-data-sensitive`() {
        val newRow = body(panel, "newRow")
        assertTrue(newRow.contains("sensitive(view.root)"))
        val sensitive = body(panel, "sensitive")
        assertTrue(sensitive.contains("view.setAccessibilityDataSensitive(View.ACCESSIBILITY_DATA_SENSITIVE_YES)"))
        assertTrue("and every child", sensitive.contains("sensitive(view.getChildAt(i))"))
    }

    @Test
    fun `the panel catches everything, and the lock survives a panel that can't be built (QA Medium 2)`() {
        for (name in listOf("start", "resume", "stop", "step", "render")) {
            assertTrue("$name is guarded", Regex("""fun $name\([^)]*\) = guarded\(""").containsMatchIn(panel))
        }
        assertTrue(body(panel, "back").contains("step(GlanceStageEvent.Back)"))
        assertTrue(body(panel, "guarded").contains("catch (e: Throwable)"))
        assertTrue(panel.contains("guarded(\"fit\") { fitNow() }"))
        assertTrue("the icon job", body(panel, "renderIcons").contains("catch (e: Throwable)"))
        assertTrue("a row fault hides the rows", body(panel, "renderNow").contains("catch (e: Throwable)"))
        val onCreate = body(code("PinLockActivity.kt"), "onCreate")
        assertTrue(Regex("""glance = try \{\s*GlancePanel\(""").containsMatchIn(onCreate))
        assertTrue(onCreate.contains("} catch (e: Throwable) {"))
    }

    // ---- part 2: the open after the code (step 3b) ------------------------------------------------

    private val opener by lazy { code("GlanceOpener.kt") }

    @Test
    fun `the opener has one send and two starts, from the visible sender`() {
        assertEquals(1, Regex("""\.send\(""").findAll(opener).count())
        assertTrue(opener.contains("intent.send(activity, 0, null, null, null, null, VisibleSender.options())"))
        assertEquals(2, Regex("""\bstartActivity\(""").findAll(opener).count())
        assertTrue(opener.contains("activity.startActivity(PhoneBookActivity.missedCallsIntent(activity, number))"))
        assertTrue(opener.contains("activity.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))"))
        // Only the app's own content intent - never an action, a reply or a delete intent.
        for (forbidden in listOf("actions", "actionIntent", "RemoteInput", "deleteIntent", "fullScreenIntent", "cancelNotification", "snoozeNotification", "setNotificationsShown")) {
            assertFalse("GlanceOpener uses $forbidden", opener.contains(forbidden))
        }
        assertTrue(opener.contains("intentFromApp = pkg != null && intent?.creatorPackage == pkg"))
        // One place for the visible sender's options; VoipCalls and MusicSessions use it too.
        for (name in listOf("VoipCalls.kt", "MusicSessions.kt")) {
            val c = code(name)
            assertTrue(name, c.contains("VisibleSender.options()"))
            assertFalse(name, c.contains("setPendingIntentBackgroundActivityStartMode"))
        }
    }

    @Test
    fun `the open runs only from the unlock path - or from the time-rule screen the unlock hands it to`() {
        val sources = sources.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        val opens = sources.filter { "GlanceOpener.open(" in strip(it.readText()) }.map { it.name }
        assertEquals(listOf("PinLockActivity.kt"), opens)
        val handOvers = sources.filter { "GlanceOpener.openHandedOver(" in strip(it.readText()) }.map { it.name }
        assertEquals(listOf("LockActivity.kt"), handOvers)
        assertTrue(body(code("LockActivity.kt"), "onResume").contains("GlanceOpener.openHandedOver(this)"))
        val activity = code("PinLockActivity.kt")
        // Opened in go(), after Home, before the finish, only when UNLOCKED.
        val go = body(activity, "go")
        val open = go.indexOf("pendingOpen?.takeIf { PinLockRuntime.mode == LockMode.UNLOCKED }?.let { GlanceOpener.open(this, it, homeBelow = plan.homeFirst) }")
        assertTrue(go, open >= 0)
        assertTrue(go.indexOf("HomeFront.bring(") in 0 until open)
        assertTrue(open < go.indexOf("finishAndRemoveTask()"))
        assertEquals(1, Regex("""GlanceOpener\.open\(""").findAll(activity).count())
        // pendingOpen gets a target only in unlock (the right code), before the lock task stops.
        val sets = Regex("""pendingOpen = (?!null)""").findAll(activity).count()
        assertEquals(1, sets)
        val unlock = body(activity, "unlock")
        val take = unlock.indexOf("pendingOpen = glance?.takeTarget(lockWasRoot = LockTaskChrome.kioskOn && startedLockTask)")
        assertTrue(unlock, take >= 0)
        assertTrue(take < unlock.indexOf("stopOwnLockTask()"))
        assertTrue(go.contains("pendingOpen = null"))
        // The panel only captures a content intent; it never sends or starts anything.
        assertFalse(panel.contains("GlanceOpener.open"))
        assertTrue(panel.contains("GlanceStore.contentIntent(it.notificationKey)"))
    }

    @Test
    fun `the opener never throws (QA Medium 2)`() {
        for (name in listOf("open", "openHandedOver")) {
            assertTrue("$name is guarded", Regex("""fun $name\([^)]*\) = guarded \{""").containsMatchIn(opener))
        }
        assertTrue(body(opener, "guarded").contains("catch (e: Throwable)"))
        assertTrue(body(opener, "send").contains("catch (e: Exception)"))
        assertTrue(body(code("GlancePanel.kt"), "takeTarget").contains("catch (e: Throwable)"))
        // Its log lines name the package (or "the missed calls") and the reason only.
        for (line in Regex("""\bLog\.\w\(.*""").findAll(opener).map { it.value }) {
            val interpolations = Regex("""\$\{[^}]*\}|\$[A-Za-z_]+""").findAll(line).map { it.value }.toSet()
            assertTrue(line, setOf("\$what", "\${step.reason}", "\${conversation.packageName}", "\$how", "\${e.javaClass.simpleName}").containsAll(interpolations))
        }
    }

    // ---- wiring and fault containment ---------------------------------------------------------

    @Test
    fun `the listener forwards every callback to the reader and reads nothing itself`() {
        val listener = code("BadgeListenerService.kt")
        for (call in listOf(
            "GlanceReader.connected(this)", "GlanceReader.disconnected()", "GlanceReader.posted(this, it, rankingMap)",
            "GlanceReader.removed(it)", "GlanceReader.rankingUpdate(rankingMap)",
        )) {
            assertTrue(call, listener.contains(call))
        }
        assertFalse("extras" in listener)
    }

    @Test
    fun `every glance entry point catches everything (QA Medium 2)`() {
        // The reader: every public function is guarded (or only re-sweeps, which is).
        val publicFuns = Regex("""^    fun (\w+)\(""", RegexOption.MULTILINE).findAll(reader).map { it.groupValues[1] }.toList()
        assertEquals(listOf("connected", "disconnected", "resweep", "posted", "removed", "rankingUpdate"), publicFuns)
        for (name in publicFuns) {
            val b = body(reader, name)
            assertTrue("$name is guarded", b.contains("guarded(") || b.contains("sweep)") || b.contains("sweep(service)"))
        }
        assertTrue(body(reader, "sweep").contains("= guarded(SWEEP)"))
        assertTrue(body(reader, "guarded").contains("catch (e: Throwable)"))
        // The store's listeners and its policy pass; the missed-call notifier's listeners.
        val store = code("GlanceStore.kt")
        assertTrue(body(store, "changed").contains("catch (e: Throwable)"))
        assertTrue(body(store, "refreshPolicy").split("catch (e: Throwable)").size == 3)
        assertTrue(body(store, "loadAtStart").contains("catch (e: Throwable)"))
        val notifier = code("MissedCallNotifier.kt")
        assertTrue(body(notifier, "guarded").contains("catch (e: Throwable)"))
        assertTrue(notifier.contains("main.post { listeners.forEach { guarded(it) } }"))
    }

    /** Code QA Low 3/4: `current` follows our notification - a removal only clears it when ours is
     * really gone (a removal can trail our own re-post), our own re-post or a time-lock change learns
     * it again, and the re-learning pass never posts anew or clears. */
    @Test
    fun `the missed-call notifier's current follows the shade`() {
        val notifier = code("MissedCallNotifier.kt")
        val removed = body(notifier, "removedFromShade")
        assertTrue(removed, removed.contains("runThenDone(null) {") && removed.contains("if (!shown(app)) lastPosted = null"))
        val posted = body(notifier, "postedToShade")
        assertTrue(posted, posted.contains("runThenDone(null) {") && posted.contains("if (lastPosted == null) relearn(app)"))
        assertTrue(body(notifier, "relearn").contains("recompute(context, mayPost = false, mayClear = false)"))
        assertTrue(body(notifier, "refreshAtStart").contains("relearn(context)"))
        assertTrue(notifier.contains("noticeState(CallPolicyStore.state, CallPolicyStore.effectiveState(), relearnOnly = !mayClear)"))
        val listener = code("BadgeListenerService.kt")
        assertTrue(listener.contains("MissedCallNotifier.removedFromShade(this)"))
        assertTrue(listener.contains("MissedCallNotifier.postedToShade(this)"))
        assertTrue(code("TimeRulesRuntime.kt").contains("if (changedTo != null) com.kidslauncher.mdm.calls.MissedCallNotifier.relearn(context)"))
    }
}
