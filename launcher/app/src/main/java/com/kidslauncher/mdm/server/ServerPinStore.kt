package com.kidslauncher.mdm.server

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.preference.PreferenceManager

/**
 * Design 76's Android half: the stored flag `server_pin_enforce` and the ring `server_pins_outside`, next to the
 * server address in the default preferences, each written with `commit()`. [pinningNow] is what every server client's
 * [toServer] reads, so the flag is read where the path is read (each `createMdmApi`, each SSE connect, each
 * `createMusicServer`). The rules are [ServerPins.kt]'s.
 */
object ServerPinStore {
    private const val LOG_TAG = "ServerPins"
    const val ENFORCE_KEY = "server_pin_enforce"
    const val OUTSIDE_KEY = "server_pins_outside"
    const val RECOVERY_KEY = "server_pin_recovery"

    /** The last refusal in this process and when (elapsed-free wall time), for "Sync now"'s dialog. */
    @Volatile
    var lastRefusal: OutsideRow? = null
        private set

    private fun prefs(context: Context) = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

    private fun app(): Context? = com.kidslauncher.mdm.Application.instance

    /** The stored flag. Without a context (never in practice: `Application.instance` is set before any server client,
     * and the boot cover's process has none) it reads as on - closed, never open (code QA nit). */
    fun enforce(context: Context? = app()): Boolean = context?.let { prefs(it).getBoolean(ENFORCE_KEY, false) } ?: true

    /** Sets the flag (the sync's [nextFlag], or a setup QR's clear); true when stored. */
    fun setEnforce(context: Context, on: Boolean): Boolean {
        if (enforce(context) == on) return true
        val ok = prefs(context).edit().putBoolean(ENFORCE_KEY, on).commit()
        Log.i(LOG_TAG, if (on) "The certificate check is on" else "The certificate check is off")
        return ok
    }

    /** The server's plain-http tailnet address ([nextRecovery]), shown in Settings for a refused server; `null`
     * when the server didn't name one. */
    fun recovery(context: Context): String? = prefs(context).getString(RECOVERY_KEY, null)

    fun setRecovery(context: Context, url: String?) {
        if (recovery(context) == url) return
        val edit = prefs(context).edit()
        if (url == null) edit.remove(RECOVERY_KEY) else edit.putString(RECOVERY_KEY, url)
        edit.commit()
    }

    fun ring(context: Context? = app()): List<OutsideRow> = context?.let { decodeRing(prefs(it).getString(OUTSIDE_KEY, null)) } ?: emptyList()

    @Synchronized
    fun addOutside(context: Context, row: OutsideRow) {
        val ring = ring(context)
        val next = addOutside(ring, row)
        if (next != ring) prefs(context).edit().putString(OUTSIDE_KEY, encodeRing(next)).commit()
    }

    /** After a 2xx status report that carried [sent] ([dropOutside]). */
    @Synchronized
    fun reported(context: Context, sent: List<OutsideRow>, connection: PinConnection) {
        if (sent.isEmpty()) return
        val ring = ring(context)
        val next = dropOutside(ring, sent, connection)
        if (next != ring) prefs(context).edit().putString(OUTSIDE_KEY, encodeRing(next)).commit()
    }

    /** `wifi`, `mobile` or `other`: the default network's transport (our VPN's carries the underlying one). */
    fun network(context: Context? = app()): String = try {
        val cm = context?.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        when {
            caps == null -> "other"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
            else -> "other"
        }
    } catch (e: Exception) {
        "other"
    }

    /** What [toServer] needs now: the flag, B, and where its sightings go. */
    fun pinningNow(): ServerPinning {
        val context = app()
        return ServerPinning(
            enforce = enforce(context),
            onOutside = { kind, host, anchor ->
                val app = app() ?: return@ServerPinning
                val row = OutsideRow(kind, host.take(253), anchor.name, anchor.pin, network(app), System.currentTimeMillis())
                if (kind == OutsideRow.REFUSED) {
                    lastRefusal = row
                    Log.w(LOG_TAG, "Refused the server's certificate from ${anchor.name}")
                }
                addOutside(app, row)
            },
        )
    }
}
