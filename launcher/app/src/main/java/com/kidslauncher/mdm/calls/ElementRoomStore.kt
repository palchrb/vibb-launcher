package com.kidslauncher.mdm.calls

import android.content.Context
import android.content.SharedPreferences

/**
 * The learned Element X DM rooms (design 15; rule and codec in ElementRooms.kt): contact MXID ->
 * (session, room) and nothing else, in its own credential-encrypted prefs file - never the DE
 * boot copy, never the status report, never logged (a failure is swallowed: the button falls
 * back to the profile). Written by the notification listener ([learn], main thread) and pruned
 * on every CE refresh of the call rules ([prune], from [CallPolicyStore.refresh]).
 */
object ElementRoomStore {
    private const val PREFS = "element_rooms"
    private const val KEY = "rooms"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    private fun read(context: Context): Map<String, ElementRoom> = decodeElementRooms(prefs(context).getString(KEY, null))

    @Synchronized
    private fun write(context: Context, before: Map<String, ElementRoom>, after: Map<String, ElementRoom>) {
        if (after == before) return
        val editor = prefs(context).edit()
        if (after.isEmpty()) editor.remove(KEY) else editor.putString(KEY, encodeElementRooms(after))
        editor.apply()
    }

    /** A newer notification replaces the contact's pair; the rest is pruned against [state]. */
    @Synchronized
    fun learn(context: Context, learned: LearnedElementRoom, state: CallPolicyState) {
        try {
            val stored = read(context)
            write(context, stored, withLearnedRoom(stored, learned, state))
        } catch (e: Exception) {
            // CE not readable, or the write failed: nothing learned this time.
        }
    }

    /** Drops the pairs of contacts that left the phone book, stopped using Element or changed MXID. */
    @Synchronized
    fun prune(context: Context, state: CallPolicyState) {
        try {
            val stored = read(context)
            write(context, stored, pruneElementRooms(stored, state))
        } catch (e: Exception) {
            // Tried again at the next refresh; a stale pair is never used for another MXID.
        }
    }

    /** For the contact sheet: the learned room of an Element MXID (read once per call). */
    fun lookup(context: Context): (String) -> ElementRoom? {
        val rooms = try {
            read(context)
        } catch (e: Exception) {
            emptyMap()
        }
        return { mxid -> rooms[mxid] }
    }
}
