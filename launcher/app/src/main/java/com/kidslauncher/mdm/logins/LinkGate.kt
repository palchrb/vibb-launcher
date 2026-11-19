package com.kidslauncher.mdm.logins

import kotlinx.serialization.Serializable

/*
 * Design 119: Chrome only for a login, never for a link - Vibb One's link gate, pure and JVM-tested (`LoginBrowserTest`
 * over `login_rules.json`'s `gate`, `link_match`, `target`, `window`, `plans` and `order` rows; `MessageLinksTest`).
 * The glue is `LinkGateActivity` (the gate and the window's anchor), `LoginWindow` (the record, its lock, the ends),
 * `LinkGateRuntime` (the component, the role, the repair) and `BrowserConfig` (the report).
 */

/** §4.4: a login window lasts at most this long from its opening (the user's ASK 3). The launcher's alone - apart from
 * Part A's 10 minutes (`logins.rs` `MINUTES`). */
const val LOGIN_WINDOW_MAX_MS = 300_000L

/** §4.4 (M4, the user): the tab left - to fetch a code, Home, another app - keeps Chrome this long before it hides. */
const val LOGIN_TAB_GRACE_MS = 60_000L

/** §4.1 (M1): the role repair's nudge at most this often, so a phone where it can't work doesn't loop. */
const val ROLE_NUDGE_EVERY_MS = 3_600_000L

/** §7 (L9): the report's stopped hosts - the last 10, none older than 7 days. */
const val REFUSED_MAX = 10
const val REFUSED_MAX_AGE_MS = 7L * 24 * 3_600_000

/**
 * A login site's entry ([loginEntry]'s form) matches [url] as Chrome reads `URLAllowlist` (§4.3 step 4): http or https;
 * a host includes its subdomains, a leading dot means that host only; a given port must equal the URL's (its scheme's
 * default when it names none); an optional path is a prefix of the URL's.
 */
fun loginLinkMatches(entry: String, url: String): Boolean {
    val uri = try {
        java.net.URI(url.trim())
    } catch (e: Exception) {
        return false
    }
    val scheme = uri.scheme?.lowercase() ?: return false
    if (scheme != "http" && scheme != "https") return false
    val host = uri.host?.lowercase()?.trimEnd('.') ?: return false
    val port = if (uri.port >= 0) uri.port else if (scheme == "https") 443 else 80
    val path = uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/"
    val exact = entry.startsWith('.')
    val rest = entry.removePrefix(".")
    val slash = rest.indexOf('/')
    val authority = if (slash >= 0) rest.substring(0, slash) else rest
    val entryPath = if (slash >= 0) rest.substring(slash) else ""
    val colon = authority.indexOf(':')
    val entryHost = (if (colon >= 0) authority.substring(0, colon) else authority).lowercase()
    val entryPort = if (colon >= 0) authority.substring(colon + 1).toIntOrNull() ?: return false else null
    if (entryHost.isEmpty()) return false
    val hostOk = if (exact) host == entryHost else host == entryHost || host.endsWith(".$entryHost")
    return hostOk && (entryPort == null || entryPort == port) && path.startsWith(entryPath)
}

/** The URL is one of the parent's login sites ([hosts]: `login_browser.hosts`, each through [loginEntry]). */
fun loginLinkMatchesAny(hosts: List<String>, url: String): Boolean = hosts.any { loginLinkMatches(it, url) }

/** A tapped link's host for the report's `refused` (§7: a host only, never a path or a query), `null` for none. */
fun refusedHostOf(url: String): String? = try {
    java.net.URI(url.trim()).host?.lowercase()?.trimEnd('.')?.takeIf { it.isNotEmpty() && it.length <= 253 }
} catch (e: Exception) {
    null
}

/** One stopped host in the report's `login_browser.refused`. */
@Serializable
data class RefusedHost(val atMs: Long, val host: String)

/** [list] with [host] stopped at [nowMs] (§7, L9): the newest last, each host once (its latest), at most [REFUSED_MAX],
 * none older than [REFUSED_MAX_AGE_MS] - and none from a clock set forward. */
fun refusedWith(list: List<RefusedHost>, host: String?, nowMs: Long): List<RefusedHost> {
    val added = if (host == null) list else list.filter { it.host != host } + RefusedHost(nowMs, host)
    return refusedKept(added, nowMs)
}

/** The stopped hosts still reported at [nowMs]. */
fun refusedKept(list: List<RefusedHost>, nowMs: Long): List<RefusedHost> =
    list.filter { nowMs - it.atMs in 0..REFUSED_MAX_AGE_MS }.takeLast(REFUSED_MAX)

/** §4.3's inputs, read by the gate on its worker (and by Meldinger with [fromMessages]). */
data class GateInput(
    /** The keyguard, the PIN lock or a time rule in force. */
    val locked: Boolean,
    /** A pause or the parent code's override. */
    val paused: Boolean,
    /** In a pause: the browser a link goes to (every app is unhidden then, Chrome too); `null` for none. */
    val pauseBrowser: String?,
    /** A map link while the map switch is on. */
    val mapLink: Boolean,
    /** The login role is on and its lockdown written and read back. */
    val roleReady: Boolean,
    /** Vibb One holds Android's browser role. */
    val roleHeld: Boolean,
    /** The link is from Meldinger (D3: never a login window). */
    val fromMessages: Boolean,
    /** The URL matches one of `login_browser.hosts`. */
    val loginMatch: Boolean,
    /** [linkTargetOf]'s answer. */
    val target: String?,
    /** A browser the parent allows as an app ([freeBrowserOf]); with the gate on there is none - Meldinger's alone. */
    val freeBrowser: String?,
)

/** What the gate does with a link. */
sealed interface GateStep {
    /** Nothing: the gate finishes showing nothing. */
    data object Nothing : GateStep

    /** The link as a plain `VIEW` to [pkg]. */
    data class Browser(val pkg: String) : GateStep

    /** `MapLinkActivity` with the link (design 110's rules from there). */
    data object Map : GateStep

    /** A login window (§4.4). */
    data object Login : GateStep

    /** The allowed app that declares the link, by package. */
    data class App(val pkg: String) : GateStep

    /** The kid's note; [refused] = the host goes into the report's `refused` (a login link while Vibb One isn't the
     * browser is the report's `failed`/`role`, not a stopped host). */
    data class Note(val refused: Boolean) : GateStep
}

/**
 * §4.3's order: the keyguard, the PIN lock or a time rule - nothing; a pause - the browser; a map link - the map
 * handler; a login link - a window, only while the role is ready, Vibb One holds the browser role (fail closed: the
 * note) and the link isn't from Meldinger (D3); an allowed app that declares the link; a free browser (only Meldinger
 * meets one: with one allowed the gate is off); else the note, the host stopped.
 */
fun linkGatePlan(i: GateInput): GateStep = when {
    i.locked -> GateStep.Nothing
    i.paused -> i.pauseBrowser?.let { GateStep.Browser(it) } ?: GateStep.Note(refused = false)
    i.mapLink -> GateStep.Map
    i.loginMatch && !i.fromMessages && i.roleReady -> if (i.roleHeld) GateStep.Login else GateStep.Note(refused = false)
    i.target != null -> GateStep.App(i.target)
    i.freeBrowser != null -> GateStep.Browser(i.freeBrowser)
    else -> GateStep.Note(refused = true)
}

/** §4.1 (D2): the gate is on while the phone is managed (an allowlist) and no free browser is allowed - role on or off. */
fun linkGateOn(managed: Boolean, freeBrowsers: Collection<String>): Boolean = managed && freeBrowsers.isEmpty()

/**
 * §4.1 (M1): the repair - flip the no-op `logins.RoleNudge` alias once, so the role's package hash changes and
 * Android's controller drops a hidden holder and grants the fallback, the gate. Due when the gate is on and was on
 * before this apply (enabling it is a hash change of its own), Chrome hidden, the role not Vibb One's, and the last
 * nudge an hour ago (or the clock set back) - or at once when a pause just ended (it may have moved the role).
 */
fun roleRepairDue(
    gateOn: Boolean,
    justEnabled: Boolean,
    chromeVisible: Boolean,
    roleHeld: Boolean,
    lastNudgeMs: Long?,
    nowMs: Long,
    pauseEnded: Boolean,
): Boolean {
    if (!gateOn || justEnabled || chromeVisible || roleHeld) return false
    if (pauseEnded || lastNudgeMs == null) return true
    return nowMs - lastNudgeMs !in 0 until ROLE_NUDGE_EVERY_MS
}

/** §4.4: a window's record - kept by the main process, read under [LoginWindow]'s own lock. */
@Serializable
data class WindowRecord(
    val openedElapsed: Long,
    val openedMs: Long,
    val boot: Int,
    /** The main process this window belongs to (its pid and start): another process's record is stale. */
    val process: String,
    /** Vibb's own Custom Tabs session held at the opening (H2). */
    val session: Boolean,
)

/** What happened to a window (§4.4's table). */
enum class WindowEvent { CHECK, TAB_HIDDEN, TAB_SHOWN, ANCHOR_DESTROYED, ANCHOR_RESUMED, RESULT, SCREEN_LOCKED, RULE, TIGHTENED }

/** A window after an event: [end] its `end` when it ends now, else the grace's start (`null`: none running). */
data class WindowStep(val end: String?, val graceSince: Long?)

/**
 * §4.4: one event on an open window at [nowElapsed]. A record of another boot or main process is `stale`; past
 * [LOGIN_WINDOW_MAX_MS] from the opening `timeout`; a grace past [LOGIN_TAB_GRACE_MS] `left`. Then the event: the tab
 * hidden starts the grace (a running one keeps its start), shown cancels it; the anchor destroyed by the system is
 * `done`, resumed after the tab `closed`; the result `result`; the keyguard `locked`; the apply's `rule`/`tightened`.
 */
fun loginWindowStep(
    record: WindowRecord?,
    graceSince: Long?,
    event: WindowEvent,
    nowElapsed: Long,
    boot: Int,
    process: String,
): WindowStep {
    record ?: return WindowStep(null, null)
    if (record.boot != boot || record.process != process) return WindowStep("stale", null)
    if (nowElapsed - record.openedElapsed !in 0 until LOGIN_WINDOW_MAX_MS) return WindowStep("timeout", null)
    if (graceSince != null && nowElapsed - graceSince >= LOGIN_TAB_GRACE_MS) return WindowStep("left", null)
    return when (event) {
        WindowEvent.CHECK -> WindowStep(null, graceSince)
        WindowEvent.TAB_HIDDEN -> WindowStep(null, graceSince ?: nowElapsed)
        WindowEvent.TAB_SHOWN -> WindowStep(null, null)
        WindowEvent.ANCHOR_DESTROYED -> WindowStep("done", null)
        WindowEvent.ANCHOR_RESUMED -> WindowStep("closed", null)
        WindowEvent.RESULT -> WindowStep("result", null)
        WindowEvent.SCREEN_LOCKED -> WindowStep("locked", null)
        WindowEvent.RULE -> WindowStep("rule", null)
        WindowEvent.TIGHTENED -> WindowStep("tightened", null)
    }
}

/** §4.4: the record a login link opens with - a second link while a window is open keeps the first's opening (the
 * 5-minute ceiling stays the first window's); a stale or ended one is replaced. */
fun windowOpenRecord(
    existing: WindowRecord?,
    nowElapsed: Long,
    nowMs: Long,
    boot: Int,
    process: String,
    session: Boolean,
): WindowRecord {
    val live = existing != null &&
        loginWindowStep(existing, null, WindowEvent.CHECK, nowElapsed, boot, process).end == null
    return if (live) existing!!.copy(session = existing.session && session) else WindowRecord(nowElapsed, nowMs, boot, process, session)
}

/** When the next end falls due ([LoginWindow]'s timer): the ceiling, or the grace's end if sooner. */
fun windowDeadline(record: WindowRecord, graceSince: Long?): Long {
    val ceiling = record.openedElapsed + LOGIN_WINDOW_MAX_MS
    return if (graceSince != null) minOf(ceiling, graceSince + LOGIN_TAB_GRACE_MS) else ceiling
}

/** §4.2: the login role's Chrome is hidden unless a window is open (it stays pinned in the kiosk while ready). */
fun loginChromeHidden(roleReady: Boolean, windowOpen: Boolean): Boolean = !(roleReady && windowOpen)

/** The report's `state` and `reason` (§7): the role `on` while the gate is on and Vibb One isn't Android's browser
 * ([browserRole] `false`) is `failed`/`role` - no login window opens then (fail closed). */
fun reportedRoleState(state: String, reason: String?, browserRole: Boolean?): Pair<String, String?> =
    if (state == "on" && browserRole == false) "failed" to "role" else state to reason

/** The report's `last_window` (§7): no host - the end and whether Vibb's session held. */
@Serializable
data class LastWindow(
    val openedMs: Long,
    val closedMs: Long,
    val end: String,
    val session: Boolean,
)

/** §4.3 (L8): the CORS-safelisted request headers the gate copies from `Browser.EXTRA_HEADERS` into Chrome's tab. */
val SAFE_HEADERS: Set<String> = setOf("accept", "accept-language", "content-language")

/** [headers] cut to [SAFE_HEADERS] (names compared in lower case), values without line breaks. */
fun safeHeaders(headers: Map<String, String>): Map<String, String> =
    headers.filter { (name, value) -> name.lowercase() in SAFE_HEADERS && '\n' !in value && '\r' !in value }
