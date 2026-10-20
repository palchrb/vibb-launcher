package me.vibb.music.logic

import me.vibb.music.model.Entry

// STUB of design 49 §1.1's shared predicate until implementer A's `controlSlots` (logic/Queue.kt, branch music-r-a)
// is merged; this file is deleted then. Same signature and rule.

/** The player's two side slots (design 49 §1.1). */
enum class ControlSlots { SHUFFLE_REPEAT, SKIP }

/** Shuffle/repeat for music and own files that don't resume; the 15 s / 30 s pair for everything that resumes. */
fun controlSlots(entry: Entry): ControlSlots = when (kindOf(entry)) {
    Kind.MUSIC -> ControlSlots.SHUFFLE_REPEAT
    Kind.OWN -> if (entry.resume) ControlSlots.SKIP else ControlSlots.SHUFFLE_REPEAT
    Kind.PODCAST, Kind.AUDIOBOOK -> ControlSlots.SKIP
}
