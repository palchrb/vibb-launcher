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
