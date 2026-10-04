package com.kidslauncher.mdm.calls

/*
 * Which app a contact's Message button opens - pure, tested in MessageButtonsTest. The parent picks
 * the app per contact (default per device) on the server: SMS, Element X (Matrix ID) or
 * Signal/Molly (phone number). The button is hidden when it can't work: the app isn't usable here
 * (not installed, suspended, or not on the allowlist), SMS is off, or the address is missing.
 * Deep-link handling per app is a device check.
 */

object MessagePackages {
    const val ELEMENT_X = "io.element.android.x"

    /** Signal-protocol apps that open `https://signal.me/#p/<number>`, in order of preference:
     * our own Molly fork (kids-mdm-im), Molly, Signal. */
    val SIGNAL = listOf("com.kidsmdm.im", "im.molly.app", "org.thoughtcrime.securesms")
}

/** An explicit intent: [action] on [uri], for [packageName] only. */
data class MessageIntent(val action: String, val uri: String, val packageName: String)

const val ACTION_VIEW = "android.intent.action.VIEW"
const val ACTION_SENDTO = "android.intent.action.SENDTO"

/** `@localpart:server`, no whitespace - the same check as the server's `valid_matrix_id`. */
fun isMatrixId(value: String?): Boolean {
    if (value == null || value.length > 255 || !value.startsWith("@")) return false
    val colon = value.indexOf(':')
    return colon > 1 && colon < value.length - 1 && value.none { it.isWhitespace() }
}

/**
 * [usable]: the package is installed, not suspended and allowed on this phone (AppEnforcer's
 * allowlist, or unmanaged). [defaultSmsPackage]: `Telephony.Sms.getDefaultSmsPackage`.
 */
fun resolveMessageButton(
    contact: RuleContact,
    smsEnabled: Boolean,
    defaultSmsPackage: String?,
    usable: (String) -> Boolean,
): MessageIntent? = when (contact.messageApp) {
    "sms" -> defaultSmsPackage
        ?.takeIf { smsEnabled && contact.number.isNotEmpty() && usable(it) }
        ?.let { MessageIntent(ACTION_SENDTO, "smsto:${contact.number}", it) }
    "element" -> contact.messageAddress
        ?.takeIf { isMatrixId(it) && usable(MessagePackages.ELEMENT_X) }
        ?.let { MessageIntent(ACTION_VIEW, "https://matrix.to/#/$it", MessagePackages.ELEMENT_X) }
    "signal" -> if (contact.number.startsWith("+")) {
        MessagePackages.SIGNAL.firstOrNull(usable)
            ?.let { MessageIntent(ACTION_VIEW, "https://signal.me/#p/${contact.number}", it) }
    } else {
        null
    }
    else -> null
}
