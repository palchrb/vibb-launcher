package com.kidslauncher.mdm.logins

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/*
 * Design 102 Part B: Chrome only for logins, pure and JVM-tested (`LoginBrowserTest` over `login_rules.json`'s
 * `bundles`, `plans` and `tighten` rows). The glue is `server/BrowserConfig.kt`.
 */

/** Chromium's policy keys the role writes (§4.1's table; the types are Chrome's schema, M10). */
object ChromePolicy {
    const val URL_BLOCKLIST = "URLBlocklist"
    const val URL_ALLOWLIST = "URLAllowlist"
    const val INCOGNITO = "IncognitoModeAvailability"
    const val BROWSER_SIGNIN = "BrowserSignin"
    const val SEARCH_PROVIDER = "DefaultSearchProviderEnabled"
    const val SEARCH_SUGGEST = "SearchSuggestEnabled"
    const val CONTEXTUAL_SEARCH = "ContextualSearchEnabled"
    const val NTP_SUGGESTIONS = "NTPContentSuggestionsEnabled"
    const val PROMO_TABS = "PromotionalTabsEnabled"
    const val DINOSAUR = "AllowDinosaurEasterEgg"
    const val TOS_DIALOG = "TosDialogBehavior"
    const val DOWNLOADS = "DownloadRestrictions"
    const val PASSWORDS = "PasswordManagerEnabled"
    const val CREDIT_CARDS = "AutofillCreditCardEnabled"
    const val PRINTING = "PrintingEnabled"
    const val SAFE_SEARCH = "ForceGoogleSafeSearch"
    const val YOUTUBE_RESTRICT = "ForceYouTubeRestrict"
    const val DEV_TOOLS = "DeveloperToolsAvailability"
}

/** `chrome://` is exempt from `*` since Chrome 147, so it is listed itself. */
val CHROME_BLOCKLIST: List<String> = listOf("*", "chrome://*")

/**
 * The captchas a login page may ask for (PLAN "Captchas in the login window", approved 2026-11-20: the user's Authentik
 * needed reCAPTCHA v3 through www.recaptcha.net), added to every login window's `URLAllowlist` after the parent's
 * sites and never shown as entries in the PWA. Chrome's filter format: a host includes its subdomains, a path is a
 * prefix - so Google's reCAPTCHA is its `/recaptcha` paths only and no Google search is reachable. The
 * `URLBlocklist` stops navigations and frames, not scripts or images (a captcha's widget is a frame), and the DNS
 * filter is the parent's category lists, which name none of these. Only Chrome's bundle: never a login link for the
 * gate, never Meldinger's link plan. Pinned by `login_rules.json`'s `captcha` rows.
 */
val LOGIN_CAPTCHA_PATHS: List<String> = listOf(
    // reCAPTCHA v2, v3 and Enterprise: the widget's frames on Google or recaptcha.net, its files on gstatic.
    "www.google.com/recaptcha",
    "www.recaptcha.net/recaptcha",
    "www.gstatic.com/recaptcha",
    // Cloudflare Turnstile.
    "challenges.cloudflare.com",
    // hCaptcha: only its widget's frame hosts (the default asset host and the older one; its script and API calls are
    // subresources), never hcaptcha.com's own site or dashboard (code QA L1).
    "newassets.hcaptcha.com",
    "assets.hcaptcha.com",
    // Arkose Labs: client-api. and each customer's own *-api. host (Roblox, Microsoft, GitHub ...), which can't be
    // listed one by one - so the domain, with its own site blocked by [LOGIN_CAPTCHA_BLOCKS].
    "arkoselabs.com",
    // Friendly Captcha v2 (global., eu. ...): an API domain with no site of its own.
    "frcapi.com",
)

/**
 * What [LOGIN_CAPTCHA_PATHS] opens too much of, blocked again in `URLBlocklist` (code QA L1): Arkose Labs' own site,
 * `www.arkoselabs.com` and the bare `arkoselabs.com`. Chrome picks the filter with the longest host match, then the
 * longest path, and an allow only wins a full tie: `.www.arkoselabs.com` (that host only) is longer than
 * `arkoselabs.com`, and `.arkoselabs.com/` matches the bare host as long and with a longer path ("/" against none) -
 * while `client-api.arkoselabs.com` matches neither and stays allowed. Pinned by `login_rules.json`'s `captcha` rows
 * through a model of that rule (`LoginBrowserTest`).
 */
val LOGIN_CAPTCHA_BLOCKS: List<String> = listOf(".www.arkoselabs.com", ".arkoselabs.com/")

/** The lockdown's `URLAllowlist`: the parent's [hosts] (each through [loginEntry]) and then [LOGIN_CAPTCHA_PATHS] -
 * what [chromeTightened] compares (code QA L2). */
fun chromeAllowlist(hosts: List<String>): List<String> = (hosts + LOGIN_CAPTCHA_PATHS).distinct()

/**
 * The role's lockdown (§4.1's table), each value in its Bundle type: strings (the two lists as JSON arrays in a
 * string, Chrome's schema), ints (CHOICE) and booleans. [hosts] are the policy's sites, each through [loginEntry];
 * the allowlist is [chromeAllowlist], the blocklist [CHROME_BLOCKLIST] and [LOGIN_CAPTCHA_BLOCKS].
 */
fun chromeLockdown(hosts: List<String>): Map<String, Any> = linkedMapOf(
    ChromePolicy.URL_BLOCKLIST to JsonArray((CHROME_BLOCKLIST + LOGIN_CAPTCHA_BLOCKS).map(::JsonPrimitive)).toString(),
    ChromePolicy.URL_ALLOWLIST to JsonArray(chromeAllowlist(hosts).map(::JsonPrimitive)).toString(),
    ChromePolicy.INCOGNITO to 1,
    ChromePolicy.BROWSER_SIGNIN to 0,
    ChromePolicy.SEARCH_PROVIDER to false,
    ChromePolicy.SEARCH_SUGGEST to false,
    ChromePolicy.CONTEXTUAL_SEARCH to false,
    ChromePolicy.NTP_SUGGESTIONS to false,
    ChromePolicy.PROMO_TABS to false,
    ChromePolicy.DINOSAUR to false,
    ChromePolicy.TOS_DIALOG to 2,
    ChromePolicy.DOWNLOADS to 3,
    ChromePolicy.PASSWORDS to false,
    ChromePolicy.CREDIT_CARDS to false,
    ChromePolicy.PRINTING to false,
    ChromePolicy.SAFE_SEARCH to true,
    ChromePolicy.YOUTUBE_RESTRICT to 2,
    ChromePolicy.DEV_TOOLS to 2,
)

/**
 * Each installed listed browser's managed config (design 67's `DnsOverHttpsMode` while the filter is on), and for
 * Chrome while the role's lockdown is in force ([lockdownHosts] not `null`: the role on, no pause or override) the
 * lockdown over it. Pure; `LoginBrowserTest`'s `bundles` rows and `BrowserDohTest`.
 */
fun browserConfigPlan(filterOn: Boolean, lockdownHosts: List<String>?, installed: Set<String>): Map<String, Map<String, Any>> =
    com.kidslauncher.mdm.server.browserDohPlan(filterOn, installed).mapValues { (pkg, doh) ->
        if (pkg == CHROME_PACKAGE && lockdownHosts != null) chromeLockdown(lockdownHosts) + doh else doh
    }

/** The role as the policy and the allowlist give it: off (no role, an unmanaged phone, Chrome ticked), refused for another
 * package (D8), or Chrome's with its sites. */
sealed interface LoginRole {
    /** No `login_browser`, or Chrome ticked as a normal app (a ticked Chrome ignores the role). */
    data object Off : LoginRole

    /** `login_browser.package` isn't [CHROME_PACKAGE]: `failed`, reason `package`, nothing unhidden. */
    data object WrongPackage : LoginRole

    data class On(val hosts: List<String>) : LoginRole
}

fun loginRoleOf(policy: LoginBrowserPolicy?, allowlist: List<String>?): LoginRole = when {
    policy == null -> LoginRole.Off
    // An unmanaged phone (no allowlist): every app is free, so no lockdown of Chrome (code QA L8).
    allowlist == null -> LoginRole.Off
    policy.packageName != CHROME_PACKAGE -> LoginRole.WrongPackage
    allowlist?.contains(CHROME_PACKAGE) == true -> LoginRole.Off
    else -> LoginRole.On(loginHostsOf(policy).orEmpty())
}

/** The role's package for the lists while it is on (whatever the pause): never on Home or in the drawer, in any state
 * (§4.1) - [com.kidslauncher.mdm.apps.AppFilter] asks [loginRoleListable]. */
fun loginRolePackage(role: LoginRole): String? = if (role is LoginRole.On) CHROME_PACKAGE else null

/** An app may be listed unless it is the login role's package (Q102-L7: the GPL rule passed into the MIT filter). */
fun loginRoleListable(packageName: String?, rolePackage: String?): Boolean = packageName == null || packageName != rolePackage

/**
 * A tightening of Chrome (Q102-M4, R2-L4): the lockdown going from absent to present (the role's first turn-on, a
 * pause's or the override's end), or any old allowlist entry gone (a set difference: swapping one site for another
 * counts). Then the same apply hides Chrome after writing and verifying the new bundle - hiding stops the package and
 * its tasks - and the plan unhides it again. (The role turned off while Chrome isn't ticked needs nothing here: the plan
 * hides it.) [old] is the lockdown last written, [new] the one now; `null` = none.
 */
fun chromeTightened(old: List<String>?, new: List<String>?): Boolean = when {
    new == null -> false
    old == null -> true
    else -> (old.toSet() - new.toSet()).isNotEmpty()
}

/** D13 (R2-L4): Chrome's data is cleared once on the role's turn-on - after a phase where Chrome was a free app, or with
 * no record (every phone at its first version with the role). A ticked Chrome forgets the record. */
fun chromeClearDue(roleOn: Boolean, cleared: Boolean): Boolean = roleOn && !cleared
