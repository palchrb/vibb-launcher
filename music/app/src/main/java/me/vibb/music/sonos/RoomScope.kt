package me.vibb.music.sonos

/*
 * The parent's room scope (design 26a §5.5; the user, 2026-10-11: every room allowed by default, the
 * parent may limit the phone to some speakers). The library carries it (`Library.sonosRooms`: null =
 * every room). A group plays only when the scope allows EVERY member (the main session's decision
 * after qa-26a-step3c-code H1: a group row with a forbidden member would sound there). An allowed room
 * absorbed into a group with a forbidden one is therefore gone from the sheet until the household
 * ungroups it - playing it alone would break the household's group. Pure; RoomScopeTest.
 *
 * Design 53 §2.8 (the user's answer to Q4, 2026-10-21: "det barna gjør manuelt bør respekteres"): a room
 * the kids grouped into our running session by hand (`accepted`, the controller's) is respected whatever
 * the scope - the session's own checks use [sessionAllowed]; everything the app starts or adds itself
 * (a pick, a "+") still needs [groupAllowed].
 */

/** May the phone play on [uid]? [allowed] null = every room. */
fun roomAllowed(allowed: List<String>?, uid: String): Boolean = allowed == null || uid in allowed

/** The uids that sound when [uid] plays: its row's members (a group by its coordinator), else [uid] alone. */
fun roomMembers(rooms: List<Room>, uid: String): List<String> = rooms.firstOrNull { it.uid == uid }?.members ?: listOf(uid)

/** May the phone play on [uid] as the household is grouped ([rooms])? Every member must be allowed. */
fun groupAllowed(allowed: List<String>?, rooms: List<Room>, uid: String): Boolean =
    allowed == null || roomMembers(rooms, uid).all { roomAllowed(allowed, it) }

/**
 * May the running session on [uid] go on as the household is grouped now (design 53 §2.8, Q4)? Every member
 * allowed, or in [accepted]: grouped in from outside the app during this renderer's life. The session's own
 * checks only - the leave, the controller's belt, our own row in the sheet.
 */
fun sessionAllowed(allowed: List<String>?, rooms: List<Room>, uid: String, accepted: Set<String>): Boolean =
    allowed == null || roomMembers(rooms, uid).all { roomAllowed(allowed, it) || it in accepted }

/**
 * The sheet's rows within the scope: a room, or a group row only if every member is allowed - and the
 * session's own row ([ours], with the rooms it [accepted]) by [sessionAllowed] (design 53 §2.7).
 */
fun allowedRooms(view: RoomsView, allowed: List<String>?, ours: String? = null, accepted: Set<String> = emptySet()): RoomsView =
    if (allowed == null) {
        view
    } else {
        view.copy(rooms = view.rooms.filter { r -> r.members.all { roomAllowed(allowed, it) || (r.uid == ours && it in accepted) } })
    }

/**
 * Design 53 §2.7 (QA 53-3): "+" and "−" are offered only while a room renders ([ours]), our uid is a row's uid and
 * in its members (not grouped away), the session is [live] (fresh, ours, PLAYING or PAUSED) and the rows aren't
 * stale. The sheet and the service's belt on `ROOM_JOIN`/`ROOM_LEAVE` both ask this.
 */
fun groupingOffered(view: RoomsView, ours: String?, live: Boolean): Boolean =
    ours != null && live && !view.stale && view.rooms.any { it.uid == ours && ours in it.members }

/**
 * The service's belt on `ROOM_JOIN` (§2.7): the row [row] of [view] exists, grouping is offered, the row doesn't hold
 * our uid, and the scope allows every room of it - the app's own join never adds a room outside the scope.
 */
fun joinOffered(view: RoomsView, ours: String?, live: Boolean, allowed: List<String>?, row: String): Boolean {
    val target = view.rooms.firstOrNull { it.uid == row } ?: return false
    return groupingOffered(view, ours, live) && ours !in target.members && groupAllowed(allowed, view.rooms, row)
}

/**
 * The service's belt on `ROOM_LEAVE` (§2.6, N2): [member] is in our group as the topology has it now, of two or more
 * rooms (a "−" never leaves nothing playing: Q2), and grouping is offered.
 */
fun leaveOffered(view: RoomsView, ours: String?, live: Boolean, member: String): Boolean {
    if (ours == null) return false
    val members = roomMembers(view.rooms, ours)
    return member in members && members.size >= 2 && groupingOffered(view, ours, live)
}

/**
 * A session on [room] (null: the phone renders) must leave it - bookmark, ours-gated Stop, the phone
 * paused - when the scope doesn't allow it as the household is grouped now: at the next sync, after
 * an adopt, when the groups change and after a migration follow. A room in [accepted] (grouped in by
 * hand, design 53 §2.8) never makes it leave.
 */
fun mustLeaveRoom(room: String?, rooms: List<Room>, allowed: List<String>?, accepted: Set<String> = emptySet()): Boolean =
    room != null && !sessionAllowed(allowed, rooms, room, accepted)
