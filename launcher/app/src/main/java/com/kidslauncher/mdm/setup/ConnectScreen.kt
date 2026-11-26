package com.kidslauncher.mdm.setup

import com.kidslauncher.mdm.ui.settings.SettingsAccess
import com.kidslauncher.mdm.ui.settings.settingsAccess

/**
 * "Koble til" (2026-11-20, the user): a Device Owner phone with no enrolment - a QR whose extras never arrived, any
 * failed QR - leads from Home to a screen with the server address, the setup code and the enrol button. Pure: due only
 * while the phone is Device Owner, neither enrolled nor holding a token (an unreadable one counts, as the first-launch
 * reset's rule), no hardware key adopted, and Settings itself is open without a PIN (no policy ever applied) - never
 * wider than the Settings gate ([settingsAccess]).
 *
 * The one exception: a phone whose enrolment witness says enrolled while its preferences say never enrolled (the
 * preferences lost or cut short; `enrolled` is only ever set) - Settings stays behind the parent code, and this screen,
 * with its emergency button, is the way back with a new code from the parent. An adopted key ([keyAdopted]) doesn't
 * hold it back: the key book lives in its own files and outlives the cut, but the server address and the enrolment
 * went with the preferences, so the key can't reach its server (§6bp row 6 of the 2026-11-25 gate). The key stays
 * until a new enrolment succeeds, which binds a fresh one and drops it (`Enrollment`, `enrolmentDrops`). A stored
 * token still holds it back (it is in the preferences, so they weren't lost - or an enrolment is mid-write).
 */
fun connectScreenDue(
    deviceOwner: Boolean,
    enrolled: Boolean,
    tokenStored: Boolean,
    keyAdopted: Boolean,
    policyEverApplied: Boolean,
    witnessEnrolled: Boolean,
): Boolean = deviceOwner && !enrolled && !tokenStored &&
    (witnessEnrolled || (!keyAdopted && settingsAccess(policyEverApplied, pinConfigured = false, lockedOut = false) == SettingsAccess.OPEN))

/** On a phone the enrolment's witness names a server for, Koble til only takes an address on that host (another
 * server is refused with the address rule's own wording); with no witnessed address, any address the rule takes. */
fun connectUrlAllowed(typed: String, witnessUrl: String?): Boolean {
    if (witnessUrl == null) return true
    val witnessed = urlHost(witnessUrl) ?: return false
    return urlHost(typed) == witnessed
}

/** Why Koble til refuses a typed address: the address rule's own reason, or another server than the witnessed one. */
enum class ConnectUrlRefusal { ADDRESS_RULE, OTHER_SERVER }

/** Gate rc.4 finding 4: the address rule first (its line says the whole rule), then the witnessed host (a line of its
 * own - an https address on another host broke no address rule); `null` when the address is taken. */
fun connectUrlRefusal(usable: Boolean, allowed: Boolean): ConnectUrlRefusal? = when {
    !usable -> ConnectUrlRefusal.ADDRESS_RULE
    !allowed -> ConnectUrlRefusal.OTHER_SERVER
    else -> null
}

/**
 * Gate rc.4 finding 2: a phone whose preferences were lost still has its adopted key, and once an address on the
 * witnessed server is back (Koble til keeps the typed one, also after a refused code) its signed sync works - the
 * phone is enrolled in all but the flag. A policy accepted on a request the adopted key signed ([keySigned]), from
 * the server the witness named before this sync ([witnessUrl], read before the sync rewrites it - [connectUrlAllowed]'s
 * host rule), restores `enrolled`, and Koble til closes. Never without a witnessed address (an unreadable witness names
 * none): another host never restores anything. A lost key signs nothing (no credential, no sync), and a key the
 * server doesn't know gets no policy, so design 129's key-lost path is never crossed.
 */
fun enrolmentRestoredBySync(
    enrolled: Boolean,
    keySigned: Boolean,
    witnessEnrolled: Boolean,
    syncedUrl: String,
    witnessUrl: String?,
): Boolean = !enrolled && keySigned && witnessEnrolled && witnessUrl != null && connectUrlAllowed(syncedUrl, witnessUrl)

private fun urlHost(url: String): String? = try {
    java.net.URI(url.trim()).host?.lowercase()?.trimEnd('.')?.takeIf { it.isNotEmpty() }
} catch (e: Exception) {
    null
}

/** The button's state: both fields filled and no enrolment running. */
fun connectButtonEnabled(serverUrl: String, code: String, busy: Boolean): Boolean =
    !busy && serverUrl.isNotBlank() && code.isNotBlank()
