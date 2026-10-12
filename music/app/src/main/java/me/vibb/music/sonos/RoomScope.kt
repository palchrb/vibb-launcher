package me.vibb.music.sonos

/*
 * The parent's room scope (design 26a §5.5; the user, 2026-10-11: every room allowed by default, the
 * parent may limit the phone to some speakers). The library carries it (`Library.sonosRooms`: null =
 * every room). A group is allowed by its coordinator - the uid a group row targets. Pure; RoomScopeTest.
 */

/** May the phone play on [uid]? [allowed] null = every room. */
fun roomAllowed(allowed: List<String>?, uid: String): Boolean = allowed == null || uid in allowed

/** The sheet's rows within the scope: a room, or a group row only if its coordinator is allowed. */
fun allowedRooms(view: RoomsView, allowed: List<String>?): RoomsView =
    if (allowed == null) view else view.copy(rooms = view.rooms.filter { roomAllowed(allowed, it.uid) })

/**
 * A session on [room] (null: the phone renders) must leave it - bookmark, ours-gated Stop, the phone
 * paused - when the scope no longer allows it (at the next sync, and after an adopt).
 */
fun mustLeaveRoom(room: String?, allowed: List<String>?): Boolean = room != null && !roomAllowed(allowed, room)
