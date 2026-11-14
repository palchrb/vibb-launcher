# The screen-size matrix on emulators, 2026-11-14

Branch `screen-sizes` at `ffb33c95` (= main). The question (the user, 2026-10-25): how do our screens scale across
phones with very different resolutions and sizes? One pass of every launcher screen and the music app's library and
player on four screen classes, at font scale 1.0 and 1.3, with gesture and 3-button navigation. Nothing is fixed
here; the findings go to the user first. Screenshots are in `screens/sizes/` (all 360 px wide, so a 270 dp screen
and a 411 dp one show at the same width; the `f*` files are crops at the device's own pixels).

## Setup

| Class | AVD | Display | dp | Image | Status bar |
|---|---|---|---|---|---|
| Narrow 4" (Bluefox NX1's class) | `ssnarrow`, throwaway, deleted after | 540x1168 @ 320 | **270x584** | Android 16, Google Play (jelly36's image) | 24 dp, no cutout |
| Jelly Star | `jelly36` (put back from its clean copy before and after) | 480x854 @ 240 | **320x569** | Android 16, Google Play | 24 dp (36 px), with the clock profile |
| 6.1" | `six35` (backed up, wiped for the run, put back byte-identical) | 1080x2400 @ 420 | **411x914** | Android 15, Google APIs (no Play) | pixel_7 cutout |
| 6.7" tall | `sstall`, throwaway, deleted after | 1440x3120 @ 560 | **411x891** | Android 16, Google Play (jelly36's image) | 24 dp, no cutout |

- The two new classes are **real AVDs** at those panel sizes (`hw.lcd.*` in `config.ini`, made like `jelly36`: no
  device profile, `hw.mainKeys = no`), not `wm size`/`wm density` on another AVD.
- Debug launcher (0.23.6-debug) and debug music app built from this commit (`assembleDebug -PwarningsAsErrors=true`,
  no emulator running); a local server 0.79.0 of the same commit, fresh data dir, the debug admin component. The PWA
  was driven with form posts as the signed-in admin.
- Content: the kid is Emma; contacts Mamma, Pappa, Jack, Lily (calls and SMS "Styrt av Vibb"); three SMS from the
  console (two from Mamma, one from Jack); a calendar link (`Familien`: an all-day "Helg hos Pappa",
  "Fotballtrening med Jack" with a place, "Bursdagsselskap hos Lily", "Middag med Mamma"); two podcast entries from
  `scripts/fake-audio.py --feed 6` renamed "Eventyr for Emma" and "Lydbok-kveld med Lily og Jack"; six apps ticked;
  the quick controls (Wi-Fi, Bluetooth, sound, touch, brightness) on; Meldinger on the lock with its text, Vibb
  music's name on the lock; rules Skole and Leggetid. Launcher language nb, 24 h, 3 columns. Time zone Europe/Oslo.
- Each phone provisioned per `emulator.md` §4 (device owner over adb) and enrolled to the same device row with a fresh
  code. The music app let out with "Allow these apps".
- The time-rule screen came from "Slå på nå" (Skole, an hour) and was ended from the PWA; the pause screen is Home
  during "Pause all restrictions" (the "Åpen til ..." banner) from the PWA's Advanced page. Android mode is the Lock
  Screen page's switch (`kid-lock/keyguard`), own mode the same switch back.
- Navigation: gesture (the images' default), 3-button with `cmd overlay enable-exclusive --category
  com.android.internal.systemui.navbar.threebutton`. Font with `settings put system font_scale`.
- Screenshots through the emulator console (`screenrecord screenshot`); screen positions from `uiautomator dump`.

## The matrix run

| Class | 1.0 gesture, own mode, Mørk | 1.0 gesture, Lys | 1.0 gesture, android mode | 1.3 3-button, own mode, Mørk |
|---|---|---|---|---|
| Narrow 270 dp | every screen (`narrow-10g-*`) | the five Lys pages (`narrow-10g-light-*`) | glance + Android's pad | every screen (`narrow-13b-*`) |
| Jelly Star 320 dp | every screen (`jelly-10g-*`) | the five Lys pages | glance + pad | every screen (`jelly-13b-*`); the time-rule screen also at 1.0 3-button (UI dump) |
| 6.1" 411 dp | every screen but the thread (`six-10g-*`) | not run | glance + pad | every screen but the thread (`six-13b-*`) |
| 6.7" 411 dp | every screen (`tall-10g-*`) | the five Lys pages | glance + pad | every screen (`tall-13b-*`) |

"Every screen": Home, phone book, Meldinger's list and Mamma's thread, Kalender (Dagen), kid Settings (three scroll
positions), the music library and the player, the glance and our keypad, the time-rule screen, the pause screen. On
the narrow one also the Parent PIN dialog (`narrow-10g-parentpin`). The Lys pages: kid Settings (the switch), the
phone book, Meldinger and the thread, Kalender.

**Not run, and why:**
- 6.1" Meldinger thread: `six35`'s Android 15 Google APIs image stayed "No service" (`OUT_OF_SERVICE`, no SIM state)
  after the wipe, so the console's SMS never arrived and the list is "Ingen meldinger ennå". The thread at 411 dp is
  the 6.7" one's.
- Lys on the 6.1": the Lys pages were the same layout as Mørk on the three other classes; one more size added nothing.
- 1.3 with gesture navigation and 1.0 with 3-button navigation as full passes (lean: gesture at 1.0 on every size as
  asked; the one 3-button finding was checked at 1.0 too).
- Android's own pad and keyguard are SystemUI's and weren't judged (`*-keypad-android`), nor kid Settings in Lys,
  which stays dark by design ("Gjelder Meldinger, Kalender, Telefonbok og listene her i Innstillinger").

## Findings

| # | Sev. | Screen | Size / font / nav | What is wrong | Screenshot | Likely file |
|---|---|---|---|---|---|---|
| S1 | **H** | Time-rule screen | Jelly Star, 1.0 and 1.3, **3-button** | "Nødsamtale" lies under the navigation bar: its bounds `[132,780][348,852]`, the bar's frame `[0,782][480,854]`. It shows dimmed between ◀ ● ■ and a tap there lands on the bar. The screen doesn't scroll it free. Not seen on the narrow, the 6.1" or the 6.7" (the button sits above the bar there), nor with gesture navigation | `jelly-13b-timerule`, `f1-timerule-emergency-under-navbar` | `ui/LockActivity.kt:101` (`KidInsets.apply` on the `ScrollView` root) and `res/layout/activity_lock.xml` (`clipToPadding="false"`). Of the four, only `jelly36` gets the clock profile (`lock/LockClockFace.kt`, `place`/`follow`: the clock at Android's geometry). That is the likely difference, but it was read in the code, not tested |
| S2 | **H** (narrow class) | Music player | Narrow, 1.0 and 1.3, both navs | The control row doesn't fit: "15 sekunder fram" `[464,852][540,940]` overlaps "Neste" `[404,844][508,948]` by 22 dp and runs to the screen edge, with no margin. The compact row is 44+52+72+52+44 dp plus 4x6 dp of gaps = 288 dp, and the content is 238 dp wide at 270 dp. Fits at 320 dp | `narrow-10g-player`, `narrow-13b-player`, `f2-player-row-narrow` | `music/.../ui/Fit.kt:26` (`CONTROLS_COMPACT`, no smaller step), `ui/PlayerScreen.kt:192` |
| S3 | M (narrow class) | Kalender (Dagen) | Narrow, 1.3 | Event titles and places break **mid-word**: "Fotballtr / ening med ...", "Bursdagss / elskap hos ...", "Kunstgress / banen ved sk...". With `maxLines=2` they end in "...", so even a short title loses its second word. At 1.0 they wrap at spaces; at 320 dp 1.3 too | `narrow-13b-kalender`, `f3-kalender-midword-narrow-13` | `res/layout/item_day_event.xml:71-77` (title `maxLines="2"`, the narrow text column beside the time column) |
| S4 | M | Meldinger, list | Narrow 1.0 ("Meldi…"), narrow 1.3 ("Me…"), **Jelly Star 1.3** ("Meldin…") | The page title is cut with "…" because "Ny melding" takes the header's right side. At 320 dp it happens from font 1.3 on, so it is on our floor size | `narrow-10g-messages`, `narrow-13b-messages`, `jelly-13b-messages`, `f4-header-*` | `res/layout/include_kid_header.xml:41-45` (title `ellipsize="end"`) with the action `kid_header_action` |
| S5 | M | The glance (own and android mode) | All four sizes, **3-button** | Our drawn gesture pill (design 59d §8.13) still shows at the bottom edge, now inside the 3-button bar under ◀, so it looks like a gesture handle in a bar with no gestures. A swipe started on it goes to the bar: on the narrow at 1.3 a swipe from the pill's centre did not open the pad, and a swipe from above the bar did | `f5-glance-handle-in-navbar` (narrow, Jelly Star, 6.1", 6.7"), `*-13b-glance-own` | `lock/GlancePanel.kt:149-170` (`glanceHandle(profile)` is drawn whatever the navigation mode) |
| S6 | L (narrow class) | Home | Narrow, 1.3 | Tile labels are hyphenated across two lines: "Telefon- / bok", "Meldin- / ger", "Lomme- / lykt", "Innstillin- / ger". Readable, but a child sees broken words. Fine at 320 dp 1.3 | `narrow-13b-home`, `f6-home-labels-narrow-13` | Home's tile label (`tile_label`; the platform's hyphenation at 3 columns of 270 dp) |
| S7 | L | Music player, "Spill av på" chip | Narrow 1.0 and 1.3, Jelly Star 1.3 | "Telefonen" shows as "Telefo…" / "Tele…". This is the chip's `min-width: 0` by design (`flexWidths`), but with one word it hides the whole name | `narrow-10g-player`, `jelly-13b-player` | `music/.../ui/Fit.kt` (`flexWidths`), `ui/PlayerScreen.kt` |
| S8 | L | Our keypad | All sizes | "Foreldrekode" is a 44 dp touch target (`minHeight="44dp"`), under 48 dp | `*-keypad-own` | `res/layout/activity_pin_lock.xml:113-121` |
| S9 | L | Kalender | Narrow 1.3, Jelly Star 1.3 | At rest the "+" sits over the last visible card: on the narrow it covers "med" in "Middag med Mamma", on the Jelly Star the card's right end. The page's 96 dp bottom padding lets it scroll clear, so nothing is lost | `narrow-13b-kalender`, `jelly-13b-kalender` | `res/layout/activity_day.xml:50` (`day_add`), `item_day_page.xml:12` |
| S10 | L (design question) | Home, glance, Kalender, player, library | 6.1" and 6.7", both fonts | Nothing scales up. With 11 tiles, Home's grid ends at about 70 % of the height. The glance and Kalender leave about half the screen empty. The 6.7" player has a gap of about 150 dp between the cover and the title, and the library's cover stays at its 180 dp cap (the six-inch doc's F5). Not broken, just the small-screen layout with more room | `six-10g-home`, `tall-10g-home`, `tall-10g-player`, `tall-10g-kalender`, `six-13b-glance-own` | Home's grid, `music/.../ui/Fit.kt` (`CAROUSEL_COVER_DP`), the player's column |

## What looks right

- **The Jelly Star (320x569 dp), the floor**: every screen fits at 1.0 with both looks. At 1.3 everything fits apart
  from S1 (3-button only), S4's title and S7's chip. The phone book's row, kid Settings, the thread, Kalender, the
  glance with a music tile and a message row, our keypad, the time-rule card with its three rows and the pause
  banner all hold.
- **The narrow 270 dp class, below the floor**: much more holds than its size suggests. The phone book moves its call
  and message buttons under the name, and the glance, the time-rule card, the thread, kid Settings (the sound choice
  wraps to "Lydløs / (vibrerer)" inside its segment), the keypad, the Parent PIN dialog and the pause banner all fit
  at both fonts. Home shows two contacts and the edge of a third, a hint that the row scrolls, and its grid scrolls
  under the music card. What breaks is S2, S3, S4 and S6.
- **6.1" and 6.7"**: no screen is clipped, overlapped or cut. At 1.0 the phone book switches to its one-row layout (at 1.3 it keeps the stacked one), the
  thread's bubbles keep a sensible width, and Kalender's in-progress event ("Pågår nå") is outlined. On the 6.7" the
  glance now shows a message's text on two lines (the six-inch doc's F4 one-line rows no longer apply there).
- **Font 1.3** nowhere pushes a primary control off the screen except S1 and S2. The keypad's digits keep their size
  and its labels grow. Kid Settings' rows wrap ("Ringetone / Flutey Phone") rather than cut.
- **3-button navigation**: apart from S1 and S5, every page keeps its content above the bar (the kid header pages, the
  thread's compose bar, the music app, the glance's "Nødsamtale" and flashlight, the keypad).
- **Lys**: the phone book, Meldinger, the thread and Kalender have the same layout as in Mørk on the narrow, the Jelly
  Star and the 6.7". No element is lost or overlapped in the light look.

## Notes from the run

- A swipe from the middle of the glance opens the pad in both navigation modes. Only a swipe that starts on the
  pill in 3-button mode does nothing (S5).
- The Meldinger tile reopens the thread that was last open, not the list (the task keeps its place). This is
  expected, but a pass that wants the list must go back first.
- After the time-rule screen ends in android mode, the phone stays at Android's keyguard (the PIN is needed again),
  as designed.
- `six35` after `-wipe-data`: SystemUI showed "isn't responding" once at the first boot, and Android's swipe keyguard
  came up once before the device owner's policy arrived. It was dismissed with `wm dismiss-keyguard`, and the
  enrolment then went as usual.
