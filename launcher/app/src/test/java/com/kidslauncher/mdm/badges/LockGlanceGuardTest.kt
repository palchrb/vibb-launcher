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
    private val allowedInterpolations = setOf("\$source", "\${e.javaClass.simpleName}", "\${next.apps.size}", "\${if (next.showText) \"on\" else \"off\"}")

    @Test
    fun `log lines carry only package names, counts and exception classes`() {
        for (name in glanceFiles) {
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
}
