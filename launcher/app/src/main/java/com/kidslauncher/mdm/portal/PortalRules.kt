package com.kidslauncher.mdm.portal

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.time.LocalDate

/*
 * Design 102 Part A: the Wi-Fi sign-in window's rules, pure and JVM-tested (`PortalRulesTest` over
 * `login_rules.json`): where the page may go ([portalNavigation], [portalFrame], [frameDocument]), when the window opens
 * ([portalApprove]) and closes ([portalClose], [portalPasses]), the parent's grants ([grantsToday] and its friends)
 * and the Wi-Fi page's row ([wifiRowState], [revisitMatches]). The Android parts are [CaptivePortalEntry],
 * [PortalWindowActivity] and [PortalRuntime].
 */

/** A sign-in (Android's notification, the Wi-Fi page, Home's line) or a reopen of the recorded page (§3.8). */
enum class PortalKind(val wire: String) {
    SIGN_IN("sign_in"),
    REVISIT("revisit"),
    ;

    companion object {
        fun of(wire: String?): PortalKind? = entries.firstOrNull { it.wire == wire }
    }
}

/** How a main-frame navigation moves, as the WebView's hooks show it (§1b R5): a tap on a GET (`hasGesture()`), the
 * page's own move (a meta refresh, a script's `location`, `hasGesture() == false`), a server redirect hop
 * (`isRedirect()`), a main-frame POST (seen in `shouldInterceptRequest` only), or a sub-frame document (never in
 * `shouldOverrideUrlLoading`, R6). */
enum class StepKind(val wire: String) {
    TAP("tap"),
    PAGE("page"),
    REDIRECT("redirect"),
    POST("post"),
    FRAME("frame"),
    ;

    companion object {
        fun of(wire: String): StepKind = entries.first { it.wire == wire }
    }
}

/** How the main-frame chain in flight began (R3-M2): the window remembers it, so a redirect hop is judged by its chain's
 * start and not by the flags Chromium carries across hops. */
enum class ChainStart(val wire: String) {
    /** The window's own first load and its redirects (Android's probe URL or the portal's URL). */
    FIRST("first"),
    TAP("tap"),
    PAGE("page"),
    POST("post"),
    ;

    companion object {
        fun of(wire: String): ChainStart = entries.first { it.wire == wire }
    }
}

/**
 * The window's sites (§3.3): [portal] the portal sites - the first page's site, plus every site a form post from a
 * portal site reached; [current] the top page's site. Until the kid first acts ([settled] false: no tap, no post yet)
 * the first page's site follows the page's own moves and redirects, so a portal that refreshes from the probe's host
 * to its login page has that page as its first site (a rig gains nothing: its probe's own 302 could point anywhere).
 */
data class PortalSites(
    val portal: Set<String> = emptySet(),
    val current: String? = null,
    val settled: Boolean = false,
    /** The first page's site, the reopen's record (§3.8): where the portal's moves landed when the kid first acted. */
    val first: String? = null,
)

/** The main-frame chain in flight: how it began, from which site, whether that was a portal site, and a tap's own
 * site (the tapped link's). */
data class PortalChain(
    val start: ChainStart,
    val from: String?,
    val fromPortal: Boolean,
    val target: String?,
)

/** One step to judge: its [kind] and the site it goes to. */
data class NavStep(val kind: StepKind, val site: String)

/** The verdict and the window's state after it. */
data class NavOutcome(val allow: Boolean, val sites: PortalSites, val chain: PortalChain?)

/** An IP literal (v4 dotted, or anything with a colon: v6) - its own site. */
fun isIpLiteral(host: String): Boolean =
    ':' in host || host.split('.').let { parts -> parts.size == 4 && parts.all { p -> p.isNotEmpty() && p.length <= 3 && p.all(Char::isDigit) } }

/** The registrable domain of [host] (OkHttp's public suffix list, `topPrivateDomain()`); an IP literal, or a host that
 * is itself a public suffix or has none, is its own site. */
fun siteOf(host: String): String {
    val h = host.trim().trimEnd('.').lowercase().removePrefix("[").removeSuffix("]")
    if (h.isEmpty() || isIpLiteral(h)) return h
    return "http://$h/".toHttpUrlOrNull()?.topPrivateDomain() ?: h
}

/**
 * Where the page may go (§3.3, D3 (i)). A pause lifts the site rule; a reopen keeps to [recordedSite] alone, by any
 * means (R3-M3). A sign-in:
 * - a tap may stay on its page's site or go to a portal site, and its chain's redirect hops may not leave the tapped
 *   link's site except to a portal site - a portal site's own redirector leads nowhere (R3-M2);
 * - the page's own move and a POST from a portal site may go anywhere; from a leaf only to its own site or a portal
 *   site; a POST's chain from a portal site makes every site it reaches a portal site;
 * - a sub-frame document ([StepKind.FRAME]) only of the top page's site or a portal site; it moves nothing.
 * The filter's lists are judged apart, on every request.
 */
fun portalNavigation(
    kind: PortalKind,
    pause: Boolean,
    recordedSite: String?,
    sites: PortalSites,
    chain: PortalChain?,
    step: NavStep,
): NavOutcome {
    val site = step.site
    if (step.kind == StepKind.FRAME) {
        val allow = when {
            pause -> true
            kind == PortalKind.REVISIT -> site == recordedSite
            else -> site == sites.current || site in sites.portal
        }
        return NavOutcome(allow, sites, chain)
    }
    if (pause || kind == PortalKind.REVISIT) {
        val allow = pause || site == recordedSite
        val next = if (allow) sites.copy(current = site) else sites
        return NavOutcome(allow, next, chain)
    }
    // Before the kid acts, the page's own moves and redirects carry the first site along (§3.3, [PortalSites]).
    if (!sites.settled && (step.kind == StepKind.PAGE || step.kind == StepKind.REDIRECT)) {
        return NavOutcome(true, PortalSites(setOf(site), site, settled = false, first = site), PortalChain(ChainStart.FIRST, sites.current, true, null))
    }
    val settled = if (sites.settled) sites else sites.copy(settled = true, portal = setOfNotNull(sites.current), first = sites.current)
    val current = settled.current
    val portal = settled.portal
    val fromPortal = current != null && current in portal
    return when (step.kind) {
        StepKind.TAP -> {
            val allow = site == current || site in portal
            NavOutcome(allow, if (allow) settled.copy(current = site) else settled, PortalChain(ChainStart.TAP, current, fromPortal, site))
        }
        StepKind.PAGE -> {
            val allow = site == current || site in portal || fromPortal
            NavOutcome(allow, if (allow) settled.copy(current = site) else settled, PortalChain(ChainStart.PAGE, current, fromPortal, site))
        }
        StepKind.POST -> {
            val allow = site == current || site in portal || fromPortal
            val next = when {
                !allow -> settled
                fromPortal -> settled.copy(current = site, portal = portal + site)
                else -> settled.copy(current = site)
            }
            NavOutcome(allow, next, PortalChain(ChainStart.POST, current, fromPortal, site))
        }
        StepKind.REDIRECT -> {
            val c = chain ?: PortalChain(ChainStart.PAGE, current, fromPortal, null)
            val allow = when (c.start) {
                ChainStart.FIRST -> true
                ChainStart.TAP -> site == c.target || site in portal
                ChainStart.PAGE, ChainStart.POST -> c.fromPortal || site == c.from || site in portal
            }
            val next = when {
                !allow -> settled
                c.start == ChainStart.POST && c.fromPortal -> settled.copy(current = site, portal = portal + site)
                else -> settled.copy(current = site)
            }
            NavOutcome(allow, next, c)
        }
        StepKind.FRAME -> error("handled above")
    }
}

/** Only http and https load in the window; `tel:` goes to Vibb's own dialer; anything else is refused in place. */
enum class SchemeVerdict { LOAD, DIAL, REFUSE }

fun schemeVerdict(scheme: String?): SchemeVerdict = when (scheme?.lowercase()) {
    "http", "https" -> SchemeVerdict.LOAD
    "tel" -> SchemeVerdict.DIAL
    else -> SchemeVerdict.REFUSE
}

/** A sub-frame's document request (R6): not the main frame, and an `Accept` that starts with `text/html` - an image's
 * or a script's is a sub-resource, judged by the filter's lists only. */
fun frameDocument(accept: String?, isMainFrame: Boolean): Boolean =
    !isMainFrame && accept?.trim()?.lowercase()?.startsWith("text/html") == true

/** One answer of the https check (§3.3): its status, whether its body was empty; `null` for no answer (no network, a
 * refused or invalid TLS chain, a timeout). */
data class CheckAnswer(val status: Int, val emptyBody: Boolean)

/**
 * "The network works" (R2-M3): two operators - Google's and Cloudflare's 204s, or either one and the phone's own
 * server (any answer over a valid chain; `null` when the server isn't a public https address, so a tailnet-only
 * family needs both, R3-L4). A 204 must be exact, with an empty body.
 */
fun portalPasses(google: CheckAnswer?, cloudflare: CheckAnswer?, server: CheckAnswer?): Boolean {
    fun ok(a: CheckAnswer?) = a != null && a.status == 204 && a.emptyBody
    val g = ok(google)
    val c = ok(cloudflare)
    return (g && c) || ((g || c) && server != null)
}

/** What a PIN keypad answered (ParentPin's results, folded). */
enum class PinOutcome(val wire: String) {
    OK("ok"),
    WRONG("wrong"),
    LOCKED_OUT("locked_out"),
    NOT_CONFIGURED("not_configured"),
    FOREIGN_ACCESS("foreign_access"),
    UNAVAILABLE("unavailable"),
    ;

    companion object {
        fun of(wire: String?): PinOutcome? = entries.firstOrNull { it.wire == wire }
    }
}

/** The entry's inputs (§3.2): the network (Wi-Fi, `CAPTIVE_PORTAL`, not `VALIDATED`; for a reopen a matching record),
 * Vibb's https check, the PIN lock, a window open already (read from the session files, R3-L5), the parent code
 * required, a running PIN pause, and an unused grant opening today. */
data class ApproveInput(
    val kind: PortalKind,
    val networkOk: Boolean,
    val passes: Boolean,
    val locked: Boolean,
    val windowOpen: Boolean,
    val requireCode: Boolean,
    val pause: Boolean,
    val grantLeft: Boolean,
)

/** The entry's next move. */
sealed interface Approval {
    /** Ask the parent code. */
    data object Keypad : Approval

    /** Open the window, approved by `code`, `grant`, `pause` or `none` (the requirement off). */
    data class Open(val approved: String) : Approval

    /** Open nothing: `locked`, `network`, `works` (the network passes), `no_code`, `foreign_access`, `unavailable`. */
    data class Refuse(val reason: String) : Approval

    /** A window is open already: bring it forward, start none. */
    data object Forward : Approval
}

/**
 * The order on every way in (§3.2): the PIN lock; one window at a time; the network; Vibb's https check (a sign-in);
 * then the approval - a running pause, a correct parent code, the requirement off, a grant, else the keypad (no daily
 * count since 2026-11-18: each opening is bounded by its own 10 minutes). [pin] is the keypad's answer, `null` before it.
 * No parent code set fails closed (R3-M4); an app that could read the code, or the Keystore key's fault, is refused.
 */
fun portalApprove(input: ApproveInput, pin: PinOutcome?): Approval = when {
    input.locked -> Approval.Refuse("locked")
    input.windowOpen -> Approval.Forward
    !input.networkOk -> Approval.Refuse("network")
    input.kind == PortalKind.SIGN_IN && input.passes -> Approval.Refuse("works")
    input.pause -> Approval.Open("pause")
    pin == PinOutcome.OK -> Approval.Open("code")
    !input.requireCode -> Approval.Open("none")
    input.grantLeft -> Approval.Open("grant")
    pin == null || pin == PinOutcome.WRONG || pin == PinOutcome.LOCKED_OUT -> Approval.Keypad
    pin == PinOutcome.NOT_CONFIGURED -> Approval.Refuse("no_code")
    pin == PinOutcome.FOREIGN_ACCESS -> Approval.Refuse("foreign_access")
    else -> Approval.Refuse("unavailable")
}

/** The window's inputs for its close (§3.3): what Android and Vibb's check say, the time open, and the pause. */
data class CloseInput(
    val kind: PortalKind,
    val validated: Boolean,
    val passes: Boolean,
    val elapsedMs: Long,
    val minutes: Int,
    val openedInPause: Boolean,
    val pauseOn: Boolean,
)

/** The outcome the window closes with now, or `null` to stay open: a sign-in closes once the network works
 * (`validated`, `passes`); a window opened in a pause closes when the pause ends (`closed_by_vibb`, R2-M4); a pause
 * lifts the limits while it lasts; else 10 minutes from the opening (`timeout`, every opening). A reopen never closes on
 * a working network (§3.8). */
fun portalClose(input: CloseInput): String? = when {
    input.kind == PortalKind.SIGN_IN && input.validated -> "validated"
    input.kind == PortalKind.SIGN_IN && input.passes -> "passes"
    input.openedInPause && !input.pauseOn -> "closed_by_vibb"
    input.pauseOn -> null
    input.elapsedMs >= input.minutes * 60_000L -> "timeout"
    else -> null
}

/**
 * The parent's "one more sign-in now" (§3.4, D14), kept by the main process: the local [date] (the latest seen - a clock
 * set back gives nothing, R2-L2), the grant openings not yet taken ([left]) and the policy's counter already taken
 * ([seen]). An unused grant ends with the day. No daily cap (2026-11-18): each step is one opening.
 */
data class PortalGrants(
    val date: String,
    val left: Int = 0,
    val seen: Long = 0L,
)

/** The grants for [today] and the policy's [grant]: a fresh install (nothing [stored]) takes the current counter as
 * seen; a new day drops what was left; an earlier date than the stored one is no new day; each step not seen adds one
 * opening. A counter that went back (another server) is taken as seen. */
fun grantsToday(stored: PortalGrants?, today: LocalDate, grant: Long): PortalGrants {
    if (stored == null) return PortalGrants(date = today.toString(), seen = grant)
    val storedDate = runCatching { LocalDate.parse(stored.date) }.getOrNull()
    val day = if (storedDate == null || today.isAfter(storedDate)) {
        PortalGrants(date = today.toString(), seen = stored.seen)
    } else {
        stored
    }
    return when {
        grant > day.seen -> day.copy(
            left = (day.left.toLong() + (grant - day.seen)).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            seen = grant,
        )
        grant < day.seen -> day.copy(seen = grant)
        else -> day
    }
}

/** An unused grant opening today. */
fun grantLeft(g: PortalGrants): Boolean = g.left > 0

/** An opening taken at the entry: a grant's uses one grant; any other uses nothing. */
fun takeGrant(g: PortalGrants, approved: String): PortalGrants =
    if (approved == "grant") g.copy(left = (g.left - 1).coerceAtLeast(0)) else g

/** The row Vibb's Wi-Fi page shows for the connected network (§3.6, §3.8). */
enum class WifiPortalRow { NONE, SIGN_IN, REVISIT }

/** The connected network's row: a captive Wi-Fi asks for a sign-in; a network with today's record (and not captive)
 * offers the reopen. */
fun wifiRowState(
    partOn: Boolean,
    captive: Boolean,
    revisit: Boolean,
): WifiPortalRow = when {
    !partOn -> WifiPortalRow.NONE
    captive -> WifiPortalRow.SIGN_IN
    revisit -> WifiPortalRow.REVISIT
    else -> WifiPortalRow.NONE
}

/** The reopen's record of one network (§3.8): never logged, kept in `noBackupFilesDir`, gone at the day's end, at
 * boot and with Part A off. */
data class RevisitRecord(
    val url: String,
    val site: String,
    val ssid: String?,
    val handle: Long,
    val bootCount: Int,
    val date: String,
)

/** The record matches the connected network (R3-L3): today, this boot, and the same `Network` handle (whose SSID, when
 * both are known, is the same) - or, after a reconnect, the same SSID. */
fun revisitMatches(record: RevisitRecord, today: String, bootCount: Int, handle: Long?, ssid: String?): Boolean {
    if (record.date != today || record.bootCount != bootCount || bootCount < 0) return false
    val sameSsid = record.ssid != null && ssid != null && record.ssid == ssid
    val ssidAgrees = record.ssid == null || ssid == null || record.ssid == ssid
    return (handle != null && handle == record.handle && ssidAgrees) || sameSsid
}
