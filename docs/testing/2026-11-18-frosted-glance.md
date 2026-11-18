# Emulator check: frosted glass B on the lock glance's cards, 2026-11-18

Branch `frosted-glance` (PLAN.md "Frosted glass on the lock glance's cards"; the design:
`docs/notes/launcher/lock-and-glance.md`, "Frosted glass on the glance's cards"). Run overnight under the user's
standing OK, one emulator at a time under `flock -o ~/emulator.lock` and the build lock.

**Builds.** "Before": main at 89612203b (debug launcher). "After": the branch's debug launcher - first 32cea64c9 (the
first round of shots), then a03a9b89a (after the code QA's fixes: the 1 dp edge, the tint over every pixel, the
fling's sync, the weather column in the contrast check). Each shot below names its build.

**Rig.** The server-round debug server (`BIND_ADDR=0.0.0.0:3108`, `DEVICE_BIND_ADDR=0.0.0.0:3109`) on a copy of the
2026-11-17 small-fixes check's data (device 1, Android's lock with the Child PIN, `kid_keyguard = android`). The debug
launcher as Device Owner (§4), enrolled with a typed code. **`jelly36`** (Android 16, 480x854 at 240 dpi, the 36 px
bar): equal to `~/kid-look-1-avd-pre-start` at the start (22 of 22 sums), started without `-wipe-data`, restored
from that copy after each run (22 of 22 sums, the same file list). **`compat34`** (Android 14): `-wipe-data
-dns-server 8.8.8.8`. Android 17: not run (no API 37 image renders on this VM, emulator.md §6as).

- **Wallpapers**: three generated PNGs, 1080x2400, no photos (a throwaway Python/PIL script in the
  scratchpad): *light* (a pale sky, soft white clouds, a pale sun), *dark* (a night sky to near-black, faint stars), *busy*
  (saturated stripes, discs and lines). Uploaded on the PWA's Wallpapers page; for each shot the device's only ticked
  wallpaper, so Home's and the lock's.
- **Content**: a throwaway probe app (`me.vibb.probe.media`, Java, debug-signed, `appCategory="audio"`, let out with
  Allow these apps and ticked under Apps; on the lock with Full text): a `MediaSession` that says it plays
  ("Morgenstemning", 4:00, art) in a `mediaPlayback` foreground service, one MessagingStyle notification ("Mamma · 2
  new") and on request six more conversations (for a page that scrolls). The time-rule card: the PWA's Regler "Slå på
  nå" for the rule "Lekser", 3 h (`rules-now mode=on`), ended with `lock-now/{id}/end`. The torch: the emulator's back
  camera has a flash unit, so the button shows.
- Each glance shot: the wallpaper saved, 8 s for the sync, a sleep and a wake (`input keyevent SLEEP`/`WAKEUP`), the
  host frame (`adb emu screenrecord screenshot`).

## Results

| Row | Android 16 (`jelly36`) | Android 14 (`compat34`) |
|---|---|---|
| 1 glass over a photo: the music tile (full and compact), the rows, the torch | **Passed.** Light, dark and busy: every card shows the blurred, tinted picture with the light edge (`after-*`); main's solid cards on the same pictures (`before-*`) | **Passed.** Busy and light (`a14-after-*`; build 32cea64c9) |
| 2 the time-rule card | **Passed.** Light, dark, busy (`after-rule-*`, `before-rule-*`); under the rule the tile and the rows are gone, as on main | - |
| 3 alignment while the page scrolls | **Passed.** Seven rows and the compact tile: at the top, mid-fling and at rest each row's glass is the picture at its own place (`scroll-busy-top`, `-fling`, `-scrolled`); the bottom fade fades the glass with the row | **Passed** (`a14-scroll-busy-*`; build 32cea64c9) |
| 4 the torch | **Passed.** Lit: the peach disc as before; off again: the glass (`torch-lit-busy`, `torch-off-again-busy`; build 32cea64c9) | - |
| 5 no picture: the cards as today | **Passed.** A colour (Plum) and a gradient (Fjord): solid `#1B1B28` cards, as main; back to the busy photo: glass again in the same lock activity (`fallback-solid-plum`, `fallback-gradient-fjord`, `after-busy-back`; build 32cea64c9) | **Passed.** Plum (`a14-fallback-solid-plum`) |
| 6 text contrast on the glass | **Passed** (a03a9b89a). Measured on the screenshots in an empty part of the tile and a row: the card at #282930-#2A2A32 on light, #131320-#121526 on dark, #1B1E28-#241F2D on busy; white 14.2-18.4:1, `kid_core_dim` 7.7-9.4:1 (main's solid tile: 17.0:1 and 8.9:1). The lock's own scrim already darkens the light picture to a mid grey | - |

The light and dark wallpapers are kept calm by the lock's scrim: on the light picture the glass reads as a slightly
lighter, softer card than main's; on the busy one the picture's colours show faintly through. Nothing animates: the
glass is redrawn only when a card moves.

Side notes. The before shots ran with the phone in English and the first after round in Norwegian (the sync applied
the policy's language after the reinstall); the a03a9b89a round is English again, so `before-*` and `after-*` match.
A fling's last frames can't be caught exactly by a host screenshot; the code QA's M1 (the glass a frame behind during
a fling) is fixed by reasoning and code (the scroller's scroll listener), not proven on a frame here.

## Screenshots (scratchpad, not in the repo)

`/tmp/claude-1000/-workspace-handy/430a895f-506c-4fe9-bfff-b4b9a36a016b/scratchpad/frosted-shots/`:
`before-{light,dark,busy}.png`, `before-rule-{light,dark,busy}.png` (main); `after-{light,dark,busy}.png`,
`after-rule-{light,dark,busy}.png`, `scroll-busy-{top,fling,scrolled}.png` (a03a9b89a); `after-busy-back.png`,
`fallback-solid-plum.png`, `fallback-gradient-fjord.png`, `torch-lit-busy.png`, `torch-off-again-busy.png`,
`a14-after-{busy,light}.png`, `a14-fallback-solid-plum.png`, `a14-scroll-busy-{top,scrolled}.png` (32cea64c9).

**Owed**: the Jelly Star itself before a release (the PLAN's "then on the phone"), and Android 17.
