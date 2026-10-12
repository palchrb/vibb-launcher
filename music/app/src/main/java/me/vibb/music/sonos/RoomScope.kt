package me.vibb.music.sonos

/*
 * The parent's room scope (design 26a §5.5; the user, 2026-10-11: every room allowed by default, the
 * parent may limit the phone to some speakers). The library carries it (`Library.sonosRooms`: null =
 * every room). A group plays only when the scope allows EVERY member (the main session's decision
 * after qa-26a-step3c-code H1: a group row with a forbidden member would sound there). An allowed room
 * absorbed into a group with a forbidden one is therefore gone from the sheet until the household
 * ungroups it - playing it alone would break the household's group. Pure; RoomScopeTest.
 */

/** May the phone play on [uid]? [allowed] null = every room. */
fun roomAllowed(allowed: List<String>?, uid: String): Boolean = allowed == null || uid in allowed

/** The uids that sound when [uid] plays: its row's members (a group by its coordinator), else [uid] alone. */
fun roomMembers(rooms: List<Room>, uid: String): List<String> = rooms.firstOrNull { it.uid == uid }?.members ?: listOf(uid)

/** May the phone play on [uid] as the household is grouped ([rooms])? Every member must be allowed. */
fun groupAllowed(allowed: List<String>?, rooms: List<Room>, uid: String): Boolean =
    allowed == null || roomMembers(rooms, uid).all { roomAllowed(allowed, it) }

/** The sheet's rows within the scope: a room, or a group row only if every member is allowed. */
fun allowedRooms(view: RoomsView, allowed: List<String>?): RoomsView =
    if (allowed == null) view else view.copy(rooms = view.rooms.filter { r -> r.members.all { roomAllowed(allowed, it) } })

/**
 * A session on [room] (null: the phone renders) must leave it - bookmark, ours-gated Stop, the phone
 * paused - when the scope doesn't allow it as the household is grouped now: at the next sync, after
 * an adopt, when the groups change and after a migration follow.
 */
fun mustLeaveRoom(room: String?, rooms: List<Room>, allowed: List<String>?): Boolean =
    room != null && !groupAllowed(allowed, rooms, room)
