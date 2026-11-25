package com.kidslauncher.mdm.server

import android.content.SharedPreferences
import com.kidslauncher.mdm.preferences.LauncherPreferences

/**
 * The Tailscale auth key waiting for the first join (default prefs `mdm.tailscale_auth_key`), sealed like the device
 * token (design r10): written by a setup QR or Settings, read by [TsnetClient]'s connect, deleted once the node is up
 * (security review M12) or the phone leaves the tailnet. The only code that names the generated getter, setter or key
 * name (`SealedPrefsTest`'s source scan).
 *
 * No migration: a plain value left by an older build is deleted at its first read, and the phone needs a new key (no
 * backwards compatibility on the phones yet).
 */
object TailnetAuthKey {
    private const val AAD = "default/mdm.tailscale_auth_key"

    private fun prefs(): SharedPreferences = LauncherPreferences.getSharedPreferences()

    /** The preference's key: Settings' row and the removes in [TsnetClient]'s own commits. */
    fun prefKey(): String = LauncherPreferences.mdm().keys().tailscaleAuthKey()

    /** The key, or `null`: none, blank, or not openable. A plain (unsealed) value is deleted here. */
    fun get(): String? = read(Secrets.prefs, prefs(), prefKey())

    /** Something is stored, no Keystore call. */
    fun present(): Boolean = !prefs().getString(prefKey(), null).isNullOrBlank()

    /** Sealed and committed; a blank [value] deletes it. False when it couldn't be sealed or written. */
    fun set(value: String): Boolean = write(Secrets.prefs, prefs(), prefKey(), value)

    /** Adds the key's removal to an edit of the default prefs (the joined mark's or the leave's one commit). */
    fun remove(editor: SealedPrefs.Editor) {
        editor.remove(AAD, prefKey())
    }

    internal fun read(sealed: SealedPrefs, prefs: SharedPreferences, key: String): String? {
        val stored = prefs.getString(key, null) ?: return null
        if (!stored.startsWith(SealedPrefs.PREFIX)) {
            sealed.edit(prefs) { it.remove(AAD, key) }
            return null
        }
        return (sealed.read(prefs, AAD, key) as? Sealed.Value)?.plain?.takeIf { it.isNotBlank() }
    }

    internal fun write(sealed: SealedPrefs, prefs: SharedPreferences, key: String, value: String): Boolean =
        sealed.edit(prefs) { if (value.isBlank()) it.remove(AAD, key) else it.putSealed(AAD, key, value.trim()) }
}
