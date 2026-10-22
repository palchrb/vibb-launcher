# PWA browser pass: the vibb identity, direction D (2026-10-22)

Headless Chromium (Playwright 1.48.2, `~/.local/opt/pw`), 390x844 at 2x, on branch `pwa-identity-d`
(`style.css?v=40`). It checks the identity change (`docs/notes/server/pwa.md`, "The vibb identity"). Screenshots were
looked at and are not committed.

## Setup

- Debug server built from the branch, run from a scratch dir with `static` linked to the branch, `ADMIN_PASSWORD`,
  `BIND_ADDR=127.0.0.1:3211`, `INSECURE_COOKIES=true`, fresh database.
- Signed in through the real forms (password change, 2FA with a TOTP computed from the page's key). Phones Anna and
  Ola were added and enrolled with a setup code (`/api/devices/enroll`). Anna got a status report (42 of 90 min today,
  battery), Ola none, and Test Phone was left not set up. Anna also got a contact.
- Crawl: from the five tabs, `/devices/1` and `/devices/3`, following same-origin links two levels deep. That gave 70
  pages (every tab page, each phone's summary, About, Advanced, Lock Screen & PINs, Home Screen, Web Filter, Apps,
  Music, Calls, Time, Locate with each phone and each span, Locations, the app page, Add App, Add Phone, Connect, the
  inbox and Notification Settings from each tab, and every Settings sub-page). Each page was loaded in light and dark,
  140 loads in all. Each load got a screenshot at the top and at the bottom of `main`, and these checks: horizontal
  scroll (document and `main`), the `<h1>` count and its computed font, `document.fonts.check("800 30px Nunito")`,
  the tab bar's box against `main` and `main`'s last child when scrolled to the end, and WCAG contrast of every
  rendered text node against its blended background (4.5:1, or 3:1 for large text). The pass also recorded console
  errors and warnings, page errors, failed requests, HTTP 4xx/5xx and every request to another origin.
- The contrast check was tested against itself: with `.row-sub` forced to `#BBBBBB`, it reported the three sub-lines
  on `/devices` at 1.92:1.
- Sign-in page (signed out) in light and dark: the same checks.
- `/devices` and a phone's page at 1280x800 (desktop) and 320x640 (small phone).
- Saves: on 10 pages, the topmost and the lowest auto-saving control (switch or select) was scrolled to mid-screen in
  `main` and changed. Its screen position was then measured on the page that came back (with JS).

## Result

Clean. No page scrolls horizontally, at any width. Every page has exactly one `<h1>`, set in Nunito 800, and the font
loaded from `/static/fonts/` on every page. The only requests to another origin were the Locate map's OpenStreetMap
tiles, which is the existing, intended behaviour. There were no console errors, failed requests or 4xx/5xx. No text
fell below WCAG AA in either scheme. That includes the orange, green and red status words, the peach links, the
buttons (white on `#A64B25`, 5.8:1), the inactive tabs (`#6B6B70` on white, 5.3:1; `#A89C96` on `#1B1B28`, 6.4:1) and
the active tab (`#0C0C14` on `#FBE4DC`; peach on `#2A2A3A` in dark). Every tab page marks its tab.

- **The tab bar** floats 12 px from the sides and 14 px from the bottom on every page (x 12-378, y 761-830 at 390x844).
  `main` always ends at its top edge, so the bar never covers a row. At the end of the scroll, the last element ends
  at least 16 px above the bar. At 1280 px the bar is 560 px wide and centred; at 320 px it fits, with "Settings"
  unclipped.
- **Devices** matches D: the night tile with the rings, the "vibb" wordmark, + and the bell in peach, "Devices" in
  Nunito 800 30 px, peach avatars with the status dot, Test Phone grey. In dark the tile is a night card (`#1B1B28`) so
  it reads on the night ground, and the wordmark is cream.
- **Saves** keep their place. The changed control stayed at the same screen position in 16 of 20 cases (0 px). Three
  cases changed content around the control, not a jump:
  - Advanced, the last switch (±80 px): its toggle adds or removes a note in the same group, and the group stays
    where it was.
  - Web Filter (phone), "Keep a log" (64 px): turning the log off removes the rows below, so the page can no longer
    scroll that far. The switch stays in view at the end of the page.
  - Lock Screen & PINs, the lowest select (a lock-screen notification setting): the changed row is not on the answer
    any more, and the page lands on its group, `#notifications`, which is in view.

  One case went to another page: Web Filter's DNS server select led to `/dns/upstream#server`, its own form. None of
  these cases landed at the top, and none involve the CSS this branch changed.

## Not matched from D, and why

- The tab labels stay 12 px rather than D's 11 px, for legibility. The icons are 24 px rather than D's 22 px, because
  the tab bar's existing icons are drawn on a 26-unit grid with 1.8 strokes, which turn thin at 22 px. The icon
  shapes are today's, not D's sketches.
- The page title is Nunito 800 at 30 px on every page (D shows only Devices). The device summary's name and the
  sign-in titles are Nunito too. Body text stays the system font, as in D.
- The vibb repo's icon (the rings as drawn there, full bleed) is used as is for 192, 512 and the apple-touch icon,
  rather than redrawing D's even rings. The maskable icon is that icon scaled to 80 % on night.

## Seen, not changed (outside this scope)

- The Locate tab's phone and span switchers in dark mode: the chosen segment (`--surface`) is darker than the track
  (`--grey-bg`). It was the same before, with iOS greys.

## Polish 2: the 44 px title bar and the see-through tab bar (2026-10-22, branch `pwa-polish-2`)

The same setup (`style.css?v=44`, port 3212, phones Anna, Ola and Test Phone), headless Chromium at 390x844, 1280x800
and 320x640, light and dark. Pages: Devices, Apps, Locate, Music, Settings, Anna's summary, Lock Screen & PINs and
Advanced. Screenshots at the top, mid-scroll and the end of `main` were looked at and are not committed.

- **The top of Devices matches Apps exactly**, at every width and in both schemes: the title bar at y 8, 44 px high;
  + and the bell at y 8; the `<h1>` at y 56 (Devices before: 54 px bar, the actions and the title 10 px lower). The
  34 px tile sits at y 13-47, centred in the bar.
- **The bar floats over `main`**: `main` runs from y 0 to the screen's bottom (844); the bar is at x 12-378, y
  760.6-830 (69.4 px, as `--tab-bar-h` gives), 560 px wide and centred at 1280. Mid-scroll, the point left of the
  bar, right of it and under it is `main`'s content on every page (seen in the screenshots: the rows pass behind the
  bar and show in the gaps; through the bar only a faint blur). `backdrop-filter: saturate(1.8) blur(20px)` on the
  bar, its ground `rgba(255,255,255,0.9)` / `rgba(27,27,40,0.9)`; the labels read as before.
- **The last row clears the bar** by 35.5-36.3 px at the end of the scroll on every long page (16 px clear plus the
  last group's own margin; the same clearance as before), more on the short ones. No horizontal scroll, the document
  never scrolls, every tab page marks its tab, and Tab reaches the bar's links with the 2 px focus ring.
- **Saves**: the 20 cases of the identity pass again. 19 kept the control at the same screen position (0 px). The
  lowest switch on Advanced (Boot cover) moved 88 px: turning it on adds the "Update Vibb One for the boot cover" note
  above its group; the page lands on `#updates` with the switch in view - content added, not a jump.
- **Not checked here**: the iPhone itself (the real `backdrop-filter`, the home indicator's inset, resume after a long
  background). The bar is absolute in the body, which is exactly the screen and never scrolls, so it sits where the
  flex child sat and nothing is placed against the viewport - design 43's stale-viewport drift has nothing to act on.

## Night band on Devices and the tab bar as its own layer (2026-10-22, branch `pwa-night-band`)

The same setup (`style.css?v=45`, port 3213, a fresh database with Anna, Ola and Test Phone), headless Chromium at
390x844, 1280x800 and 320x640, light and dark, on Devices, Apps, Locate, Music and Settings. An iPhone's status-bar
inset was simulated at 390x844 by serving the CSS with `env(safe-area-inset-top, 0px)` replaced by 47 px (Chromium
has no inset to emulate). The band's extent was read from the screenshots' pixels; the screenshots were looked at and
are not committed.

- **The top of Devices still matches Apps exactly**, at every width and in both schemes: the title bar at y 8-52, +
  and the bell at y 8 (the bell's right edge at 385 / 995 / 315), the `<h1>` at y 56, the tile at y 13-47. With the
  47 px inset: the bar at 55-99 and the `<h1>` at 103 on both pages.
- **The band**: exactly `#0C0C14` from y 0 to y 52 (99 with the inset) across the whole width (0-390, 0-1280, 0-320),
  the grey ground `#F2F2F7` from y 52 down; on Apps, Locate, Music and Settings no night pixel at the top. In dark the
  band is the ground's night and runs on into it. Computed: the tile `#1B1B28`, the wordmark `#FFFFFF`, + and the bell
  `#F0A884`, hover `#F6C4AA`, Tab reaches + with a 2 px peach ring. White on night 19.5:1, peach 9.9:1, the hover
  12.4:1 (the old `#A64B25` ring would have been 3.4:1 on night). The tile against the band is 1.14:1: it is the
  Pust mark's ground, read by its peach rings, as D's dark tile was.
- **It scrolls with the bar**: with Devices' rows repeated so `main` scrolls (747 px), at a scroll of 30 the bar's top
  is at y -22 and the band ends at y 22 with it; nothing stays behind at the top. No horizontal scroll (document or
  `main`) on any page at any width, and the document never scrolls.
- **theme-color**: Devices has one, `#0C0C14`, applied in both schemes; the other tabs keep `#F2F2F7` (light) and
  `#0C0C14` (dark), checked with `matchMedia` on each meta.
- **The tab bar** (the user's iPhone, Settings: the white bar ran into a white group under it; then "like
  Nextcloud", the content behind dimmed and blurred): `--bar-glass` `rgba(238, 236, 234, 0.72)` with
  `saturate(180%) blur(20px)` (dark `rgba(27, 27, 40, 0.72)`), the solid fallback `#F4F2F0` / `#1B1B28`, an inset 1 px
  hairline `rgba(12, 12, 20, 0.08)` / `rgba(246, 231, 224, 0.08)`, a shadow `0 4px 18px rgba(12, 12, 20, 0.14)` /
  `rgba(0, 0, 0, 0.5)`. Looked at over a white group (Settings at the top with Sign Out behind the bar; Advanced
  mid-scroll, rows and switches behind it; Devices with its rows repeated, scrolled 200) and over the grey ground
  (Devices, Apps), light and dark: the bar's edge shows against both, the content behind it is dimmed, while in the
  gaps it stays sharp; the pill (`#FBE4DC` / `#2A2A3A`) stands out. No fade on main's bottom edge (it would dim the gaps).
  Label contrast against the worst rendered backdrop (the labels made transparent, the darkest pixel in each label's
  box, light; the lightest, dark): at 72 % the old `--tab-off` fell to 3.98:1 light / 4.14:1 dark with Advanced's text
  behind, so it is now `#5A5A5F` / `#B4A8A2`: inactive 5.16-5.97:1 light, 4.77-7.73:1 dark; the chosen tab 15.98:1 /
  7.14:1. Headless Chromium draws the backdrop blur faintly (the text behind stays half legible), so these numbers are
  a worst case; a real 20 px blur averages the text away. The bar's box is unchanged (x 12-378, y 760.6-830).
- **Saves**: the 20 cases again, the same as Polish 2: 19 at 0 px, the Boot cover switch 88 px (its note added above
  its group, landing on `#updates`).
- **Not checked here**: the iPhone itself - whether iOS colours the standalone status bar from `theme-color` with
  `status-bar-style` `default` (Android's Chrome does), and the pull-down bounce showing the band's night above it.

