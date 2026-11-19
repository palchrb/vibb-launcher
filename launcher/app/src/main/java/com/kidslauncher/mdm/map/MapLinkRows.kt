package com.kidslauncher.mdm.map

/*
 * Design 110's steps on the phone. The two one-time steps (§6.2) are design 114's setup steps `map_play_links` and
 * `map_vibb_links` (114 §2.6): flow screens, read in `SetupReadings` as the report reads them. The PIN-gated Settings'
 * "Kartlenker" keeps only §6.3's row.
 */

/** Step 1 done: the short-link holder's links read off. [holderAllowed] null = unreadable (no reading). */
fun playLinksDone(holderAllowed: Boolean?): Boolean? = holderAllowed?.not()

/** Step 2 done: every host the parent can select is selected (or verified). */
fun vibbLinksDone(hosts: VibbHosts): Boolean = hosts.declared > 0 && hosts.selected >= hosts.selectable

/** §6.3: the row that gives the holder its links back - only while the switch is off and they read off (Q4). */
fun mapLinkBackShown(on: Boolean, holderAllowed: Boolean?): Boolean = !on && holderAllowed == false
