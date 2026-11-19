package com.kidslauncher.mdm.logins

import com.kidslauncher.mdm.play.PLAY_NEVER_RESTRICT

/*
 * Design 119 §4.3 step 5 and §5: which app a web link may open, pure (`MessageLinksTest`, `LoginBrowserTest`'s
 * `target` rows). Meldinger's links and Vibb One's link gate share it. The lookup that feeds it is [LinkLookup].
 */

/** Never a link's target: Android's chooser, Play services and its framework, and Vibb One itself (its own components -
 * the link gate, the map handler - are reached by Vibb's own rules, never as "an app that declares the link"). */
fun neverLinkTarget(pkg: String, own: String): Boolean = pkg == "android" || pkg in PLAY_NEVER_RESTRICT || pkg == own

/**
 * The allowed app that opens a link (§4.3 step 5, H1): [approved] - Android's own answer with no package, which names an
 * app only when that app is approved for the host - if it is allowed, else the first of [declaring] (the allowed
 * packages in the allowlist's order whose own filters take the link, found per package) that is. [allowed] is `null`
 * on an unmanaged phone (nothing is restricted). Never Vibb One, Play services, or a full browser ([browsers]: an app
 * that takes any web link is a browser, not "an app for this link"); nothing during a time rule ([locked]).
 */
fun linkTargetOf(
    approved: String?,
    declaring: List<String>,
    allowed: Set<String>?,
    locked: Boolean,
    own: String,
    browsers: Set<String>,
): String? {
    if (locked) return null
    val ok = { p: String -> !neverLinkTarget(p, own) && p !in browsers && (allowed == null || p in allowed) }
    return approved?.takeIf(ok) ?: declaring.firstOrNull(ok)
}

/** The first free browser (§4.1): a full browser among [browsers] (in the allowlist's order) the parent allows - a
 * ticked Chrome or Firefox -, any on an unmanaged phone ([allowed] `null`); never Vibb One. */
fun freeBrowserOf(browsers: List<String>, allowed: Set<String>?, own: String): String? =
    browsers.firstOrNull { !neverLinkTarget(it, own) && (allowed == null || it in allowed) }
