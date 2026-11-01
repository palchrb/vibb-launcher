package me.vibb.music.logic

/*
 * What a release build's Sonos log may say (security review 2026-10-21 P-L1): logcat goes into bug reports and to
 * anyone with adb, so a release line names no speaker's MAC (a `RINCON_` uid is the MAC plus a suffix), no room and no
 * title, and a URL only by its scheme and host. A debug build logs everything as it is.
 */

private val UID = Regex("RINCON_[0-9A-Fa-f]{12}[0-9A-Za-z]*")
private val URL = Regex("\\b([A-Za-z][A-Za-z0-9+.-]*)://([^/\\s?#)\\]]+)[^\\s)\\]]*")

/**
 * [line] as a release build logs it: every uid as `RINCON_#` and a 16-bit tag of it (the same uid, the same tag, so a
 * log still follows one speaker - far too short to give the MAC back), every URL as `scheme://host/…`.
 */
fun releaseLogLine(line: String): String = line
    .replace(UID) { "RINCON_#%04x".format(it.value.hashCode() and 0xffff) }
    .replace(URL) { m -> if (m.value.length > m.groupValues[1].length + 3 + m.groupValues[2].length) "${m.groupValues[1]}://${m.groupValues[2]}/…" else m.value }

/** A room's name or an item's title in a log line: as it is in a debug build, never in a release one. */
fun logName(name: String?, debug: Boolean): String = if (debug) name.toString() else "‹name›"

/** An exception in a log line: a release build names its class only - its text can carry a UPnP description, an ip:port or a uid. */
fun logFailure(e: Throwable, debug: Boolean): String = if (debug) "${e.javaClass.simpleName}: ${e.message}" else e.javaClass.simpleName
