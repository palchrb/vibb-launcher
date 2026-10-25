package com.kidslauncher.mdm.ui.home

/*
 * The black behind Home after the Home button (PLAN "Fix backlog", the user's videos 2026-10-16): from an app, the Home
 * button showed Home's icons on solid black for ~150 ms while the app slid away, then the wallpaper; Back was clean.
 * Home's window is transparent over Android's wallpaper (`windowShowWallpaper`), and that wallpaper isn't shown under it
 * while the app-close transition runs (measured on `jelly36` 2026-10-24: 2-3 black frames at 30 fps before, 0 with the
 * copy - `docs/testing/2026-10-24-home-round-run.md`). So Home draws its own copy of the wallpaper under the grid -
 * `WallpaperGround`'s FULL ground, the store's colour, gradient or the one decoded photo with the ink's scrim, placed as
 * Android's (centre crop over the whole window, as the bitmap `WallpaperApplier` sets is drawn).
 *
 * Since 2026-10-25 (PLAN "Home's wallpaper copy: a one-frame zoom step at the hand-over", the user's 24 fps video: the
 * whole photo shifted by a hair 11 frames after Home came to rest, when the copy went and Android's own wallpaper -
 * rendered at a slightly different scale, its wallpaper zoom - showed through) the copy is **permanent**: no hand-over
 * to Android's wallpaper at all, so no step. The one decoded bitmap is already in memory; an unchanged ground keeps its
 * drawable (`WallpaperGround`'s key tag), so nothing is allocated per render. This also covers design 59d step B's
 * face leave (its 3 black frames) and design 08's owed 3-button check (qa-home-round-code Q-7: the copy never goes).
 *
 * Never at a lock-up: the copy is the content root's background, and the content isn't on the window while the lock is
 * LOCKED or at a dark lock-up (design 16c/32 - the night ground is), and Home's snapshot stays the black task
 * description (no screenshot). Back is unchanged: it reveals Home with no intent, and Home's ground is simply there.
 */

/** Whether Home draws its own wallpaper copy under its content: always while the content is on the window
 * ([contentShown]) - never the night ground's lock-up. */
fun homeGroundDue(contentShown: Boolean): Boolean = contentShown
