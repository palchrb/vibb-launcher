# Emulator gate: map links and tappable links in Meldinger (design 110), 2026-11-18

Branch `map-links` at 529e71799, run overnight under the user's OK for tonight (one emulator at a time under
`flock -o ~/emulator.lock`, the builds under the build lock). Design 110 §13's gate (G1-G13) and the code QA's
additions. No product code was changed.

**Builds and versions.** The branch's debug launcher (`assembleDebug -PwarningsAsErrors=true`, versionName
`0.23.6-debug`) and debug server (0.84.0, `cargo build -j 1`). Organic Maps 2026.11.11-39-FDroid (26111139) and CoMaps
2026.11.07-16-FDroid (26110716), both from F-Droid's official repo: `index-v1.jar` verified (`jarsigner`, signer
SHA-256 `43:23:8D:51:…:C9:CC:AB`, F-Droid's repo key), each APK's SHA-256 equal to the index's `hash`
(`c94a9125…6583d`, `17264ca1…b8069`) and its signer equal to the index's `signer` (`e216fc38…459fe9`,
`ca5180da…d2f932b`). Google Play services 25.08.34 on API 36, 23.18.18 on API 34.

**Rig.** The debug server on a copy of the 2026-11-18 app-push gate's data (device 1, Android's lock with the Child
PIN, Chrome as the login browser, migrated to 0159), its phone port behind a small proxy (`proxy.py`, scratchpad) that
passes everything through byte for byte except `POST /api/devices/map-links/resolve` when a row says otherwise: a
canned answer for the Artbox short link and the Artbox and Steak no Don feature ids (the server's own recorded `url`
and the `place` that `server/testdata/map_links/server.json`'s recorded preview rows judge to), a set status (503, 422,
200 `{}`), or a delay. It was needed because Google refused the server's place lookup during the gate (G1b below).
Every row that used a canned answer says so. **`jelly36`** (Android 16, Google Play image) equal to
`~/kid-look-1-avd-pre-start` at the start (22 of 22 sums), started without `-wipe-data`, restored from that copy
afterwards (22 of 22, the same file list, `jelly36.ini` equal). **`compat34`** (Android 14, Google APIs image)
started with `-wipe-data -dns-server 8.8.8.8`. The debug launcher as Device Owner (emulator.md §4), enrolled with a
typed code; the PWA's switch on. Android 17: not run (no API 37 image boots on this VM, §6as).

## Findings

1. **Step 2 can't select the Google hosts (both versions).** The step is done in a pause (its row opens Android's page
   only then), and the pause unhides every app: Google Maps then holds `maps.google.*`, `www.google.*` and
   `google.com` as `system_configured`, Google Photos `goo.gl`. Android's "Add link" dialog greys them out ("Opens in
   Maps"): on 16, 3 of Vibb One's 20 hosts were selectable (`maps.app.goo.gl`, `maps.apple`, `maps.apple.com`); on 14,
   4 (the same and `play.google.com`). After the pause the report reads `selected: 3` (14: 4) of `selectable: 20`, and
   with Chrome as the login browser `reach.google` stays false: a plain VIEW of `www.google.com/maps/…` or
   `maps.google.com/…` goes to Chrome, and the PWA shows "One step left on the phone … Vibb One's links" (orange) for a
   step the parent can't finish (`pwa-group-stepleft-*`). With no browser allowed (the login browser off) `reach.google`
   was true even so: Android falls back to every candidate and the preferred activity picks Vibb One, and the PWA
   says "Ready." (`pwa-group-ready-*`). Meldinger and Dagen are not affected (they start the handler directly). A
   design question for the user, not fixed here.
2. **The server's place lookup never got an answer from Google** (G1b). From the branch's server, 9 of 9 lookups
   ended `unreachable` at the 4 s client timeout. The first seven (21:28-21:31 UTC) ran while `curl` from the same VM
   got a 200 JSON answer in 0.15-0.2 s (21:29 and 21:31, IPv4 and IPv6, HTTP/1.1 and /2) and Python's `urllib` too:
   the server's TCP connection to `www.google.com` opened, its request went out (542 bytes sent, only the TLS
   handshake's 2.8 KB back), and no answer came within 4 s. Then, after about 20 requests to the endpoint in 5
   minutes (the server's and the ones made to compare), Google answered every client from this VM with `403` and its "Sorry…" page after a 5-6 s delay, still at
   22:31 UTC. What makes the server's client wait is not established (its TLS stack is the likely difference; that
   would need more requests to Google, which the gate stopped making). Two consequences to weigh: Google's wall came
   far below §8.1's 100 an hour, and the counter books the wall as `unreachable` (the 403 arrives after the 4 s
   timeout), which §8.4's "lookup isn't working" line doesn't count - the line stays silent through exactly this
   outage. The short-link hops worked throughout (`maps.app.goo.gl` and `maps.apple` 302/301 to their long links).
   With the real server the Artbox short link opened as Organic Maps' search for "Drammensveien 133, 0277 Oslo"
   (the fallback) about 4.5 s after the tap, the waiting note showing.
3. **Android 16's "Open by default" has no "Open supported links" switch.** On 16 the page has two choices, "In the
   app" and "In your browser"; the Settings rows say «Slå av / Slå på «Åpne støttede linker»» (Android 14's wording,
   which matches 14's page). The Jelly Star runs 16. Copy only, not fixed here.
4. **Before step 1, a short link from another app shows Android's "App is not available"** ("Google Play services is
   not available right now", `g3-before-step1-blocked-34`): Play services takes it and the kiosk blocks Play services'
   activity. On `jelly36` that screen came up behind the keyguard and stayed as the only locked task: the next pause
   (started from the PWA) left `mLockTaskModeState=LOCKED` with an empty package list and every start of Vibb One
   refused, until the emulator was rebooted - emulator.md §6af B's picture (the blocked screen as the locked task),
   reached a new way. Expected until step 1 is done; the stuck state is the kiosk's, not this branch's.

## Rows

| Row | `jelly36` (16) | `compat34` (14) |
|---|---|---|
| G1 short links | **Passed** (the branch's server, this VM): all five gave `long` (`maps.app.goo.gl` ×4, `maps.apple` ×1); the Artbox link to `maps.google.com?q=Artbox+AS,+Drammensveien+133,+0277+Oslo&ftid=0x46416db4338a09ff:0x7d7d788043d1f40e&entry=gps&shh=CAE`, the Apple one to `maps.apple.com/place?address=Thomash%C3%B6he%2C+12053+Berlin%2C+Germany&coordinate=52.4737758%2C13.4373898`. On the user's server: skipped (not reachable from here) | - |
| G1b place lookup | **Failed here** (finding 2). `hl=no` against `hl=en`, Steak no Don, RAI, a `cid`: not measured. On the user's server: skipped - owed, with the server binary itself, not curl | - |
| G2 `pm get-app-links` | Play services: `*.app.goo.gl` `system_configured` (and 21 other hosts); the Google app: `www.google.com`, `google.com`; Google Maps: `maps.google.com`, `maps.google.{no,se,dk,fi,de,co.uk}`, `www.google.{com,no,se,dk,fi,de,co.uk}`, `google.com`, `goo.gl` (595 `system_configured` rows in all) | The same for Play services and Maps; the Google app `www.google.com` only |
| G3 short link before the steps | Play services' `AppInviteAcceptInvitationActivity`, blocked by the kiosk (finding 4) | The same: "App is not available" (`g3-before-step1-blocked-34`) |
| G3/G4 after the two steps | **Passed** (canned place): the plain VIEW reached `MapLinkActivity`, the server's answer, Organic Maps started with `geo:59.922362,10.686889?z=17&q=59.922362,10.686889(Artbox%20AS%2C%20Drammensveien%20133%2C%200277%20Oslo)`; the card's title "Artbox AS, Drammensveien 133, 0277 Oslo" (`g4-om-card-artbox-36`). Step 1: Play services "In your browser", `Verification link handling allowed: false`, the row "Ferdig". Step 2: 3 of 20 (finding 1) | **Passed**, the same URI and card (`g4-om-card-artbox-34`); step 1 the "Open supported links" switch; step 2: 4 of 20 |
| G5 forms and decoding | **Passed.** A1 `name=Kaf%C3%A9%20Bl%C3%A5&address=Tr%C3%B8ndergata%201%2C%200180%20Oslo` → "Kafé Blå, Trøndergata 1, 0180 Oslo"; G4 with its address in the segment (`Kiwi+Majorstuen,+Kirkeveien+64,+0364+Oslo`) → that label, `z=16` from the link, no server call; G4 name only (Steak no Don) → the link's own point with the lookup's name and address (canned); G9 (Artbox) → the pin (canned); A5 (`maps.apple/p/…`, the real server) → "Thomashöhe, 12053 Berlin, Germany" | - |
| G6 Chrome as the login browser | Short and Apple links reach Vibb One; Google long links go to Chrome (finding 1). With the login browser off, Chrome hidden: all three classes reach Vibb One | - |
| G7 switch off | **Passed.** `MapLinkActivity` disabled (`disabledComponents`); the PWA: "Google Play services' links are still off on the phone. To give them back: during a pause, Vibb One's Settings › Map links."; Settings: one row "Gi Google Play-tjenester lenkene tilbake" ("Sett alle begrensninger på pause først" outside a pause, "Slå på «Åpne støttede linker» igjen" in one); its page's "In the app" gave `handling allowed: true` and the group disappeared | - |
| G8 target states | **Passed.** Allowed: opens. Hidden (unticked): "Kartet kan ikke åpnes her. Spør en voksen om Organic Maps.", the PWA "Organic Maps isn't allowed on this phone." Suspended (`pm suspend`): "Organic Maps er ikke tilgjengelig akkurat nå." Missing (uninstalled): the hidden note, the PWA "Organic Maps isn't on the phone any more." CoMaps (Organic Maps gone, CoMaps installed and allowed): CoMaps opened the pin, the PWA "Open map links in CoMaps" / "Ready.", notes name CoMaps | - |
| G9 Meldinger | **Passed.** Both bubbles' links underlined in the bubble's own ink (`g9-meldinger-links-*`); the map link (switch on) → `MapLinkActivity` → Organic Maps in its own task, no chooser; `https://example.com/a_(b)` → Chrome (the login browser), no chooser; the kid's own bubble's `maps.google.com/?q=…` → Organic Maps without a server call | **Passed**, the same (map link → Organic Maps' own task; the web link → Chrome) |
| G10 the label and the card | Labels exact everywhere above. The card for Artbox with the Norway Central map downloaded: the label and the coordinates, no OSM data (no mapped feature under the point, `g10-om-card-artbox-36`); a mapped place's data in the card was not tried | - |
| G11/G11b Element X | **Skipped**: needs a Matrix account and homeserver to receive a link; not installable usefully offline | - |
| G12 fallback, lookup forced off (proxy: 200 `{}`) | **Passed.** Centre `59.947246,10.889123` (the id's S2 cell). "Artbox AS, Drammensveien 133, 0277 Oslo" → `q=Drammensveien 133, 0277 Oslo`, first hit "Drammensveien, 133, 0277 Oslo"; "Kiwi Tåsen, Tåsenveien 71, 0873 Oslo" → (b), first hit "Tåsenveien, 71"; "Frognerparken" → (a), first hit the park; "Deichman Bjørvika, Anne-Cath. Vestlys plass 1, 0150 Oslo" → (b), first hit "Anne-Cath. Vestlys plass, 1" | - |
| G13 time rule, keyguard, leaving | **Passed.** Keyguard (explicit start): the activity finished, nothing started. A rule on now: a plain VIEW and an explicit start both finished with the time-rule screen still on top, nothing started. Home pressed during a 3.5 s wait: nothing started after the answer | **Passed**: the rule and the keyguard the same |
| QA: a link to a not-allowed app | **Passed** (login browser off, Chrome hidden): `https://vibbtest.app.goo.gl/AbCdE`, a YouTube link and `example.org` each gave "Denne lenken kan ikke åpnes her." / "This link can't be opened here.", nothing started | - |
| QA: back out of "Åpner kartet …" | **Passed**: Back at 1.2 s of a 3.5 s answer, nothing started | - |
| QA: Dagen | **Passed.** A kid's own event with the link as its place: no "Kart" row while Organic Maps was hidden (`dagen-hidden-nb-36`), the row once allowed (`dagen-kart-nb-36`), its tap opened the pin | - |
| QA: the targets' `geo:` filters | Organic Maps and CoMaps both accept the `BROWSABLE` start (every start above; no `ActivityNotFoundException`) | Organic Maps: the same |
| QA: a label with `(`/`)` | **Passed**: `q=Kaf%C3%A9%20(%C3%98%20%26%20%C3%85)` → `(Kaf%C3%A9%20%28%C3%98%20%26%20%C3%85%29)`, the card "Kafé (Ø & Å)" | - |
| §7.2 a note in a pause | **Passed**: the list note offered "ÅPNE I NETTLESEREN" (the dialog style's capitals), which opened the original link in Chrome by package | - |
| The notes | "Fikk ikke åpnet lenken nå. Prøv igjen når du har nett." (503), "Denne lenken virker ikke lenger." (422), "Fant ikke noe sted i denne lenken." (`/maps/place/9FFW84J8%2BX5`), "Dette er en liste med steder. Den kan ikke åpnes i Organic Maps." | - |

A plus code with a bare `+` in the path (`/maps/place/9FFW84J8+X5`) is read as text and opens as a search; with `%2B`,
as the rule row has it, the "no place" note.

Screenshots (jelly36 unless named `-34`): the waiting dialog nb and en, Meldinger's links nb and en, the PWA group
(ready and step-left, nb and en, light and dark), the calm dialog nb and en, the notes, the Settings group, the two
steps' pages, the Organic Maps and CoMaps cards; kept outside the repo.

Owed: G1/G1b on the user's server (with the server itself), G10's card for a mapped place, G11/G11b with Element X,
Android 17, the Jelly Star.

## Overnight fixes after the gate (no emulator, no request to Google)

**Finding 3.** The PIN-gated Settings rows now name Android 16's choices from `Build.VERSION_CODES.BAKLAVA` on:
"Velg «I nettleseren»" / "Choose “In your browser”" (step 1), "Velg «I appen» og legg til alle linkene" / "Choose “In
the app” and add every link" (step 2), "Velg «I appen» igjen" / "Choose “In the app” again" (the way back). Android 14
keeps the switch's words. Android 15 is unverified and keeps 14's words. The nb labels are taken from Android 16's
English and need checking on a Norwegian phone. The PWA's ⓘ no longer names the switch: "… and turn its links off" /
"… and turn on all of them".

**Finding 2: what differs in the server's call, read from the code.**
- **The lookup request.**
  - Client: reqwest 0.13 on hyper 1, **HTTP/1.1 only**. The `http2` feature isn't on, so ALPN offers `http/1.1`
    alone.
  - TLS: **rustls 0.23 with the ring provider**. Its ClientHello has no GREASE, a different extension and cipher
    order, and no `compress_certificate` or `status_request`, unlike curl's OpenSSL and Python's OpenSSL. That makes
    it a different TLS fingerprint.
  - Headers: `user-agent: vibb/1.0`, `accept: */*` and `accept-encoding: gzip` (reqwest's `gzip` feature). No
    `Accept-Language`, no cookie, no referer.
  - DNS: getaddrinfo through the public-only resolver, both address families.
  - Connect timeout 3 s; 4 s per request; the integration's 5 s deadline from the start of the resolve. A feature-id
    request has all of it; a short link shares it with its hops.
- **The body read.** It reads frames to the end of the message, capped at 256 KB.
- **What the gate measured rules out** DNS, connect, connection reuse, the body read and the deadline. TCP and TLS
  completed, the request went out, and not one byte came back in 4 s. curl over HTTP/1.1 and /2, IPv4 and IPv6, got
  200 in 0.2 s. The same client's hops to `maps.app.goo.gl` were answered.
- **What is left:** the TLS fingerprint and the request headers (the user agent, and `Accept-Encoding: gzip`).
  - The 403 "Sorry" wall also came only after a 5-6 s delay, which looks like Google's front end holding clients it
    classes as automated.
  - Nothing in the client is plainly wrong, so nothing in it was changed.
  - A browser `User-Agent` would be the trick design 110 §11 rules out.
- **To verify on the user's server.** At most four requests, a minute or more apart. The burst limit below keeps the
  server itself within this.
  1. `curl --http1.1 -A 'vibb/1.0' -H 'Accept-Encoding: gzip' -o /dev/null -w '%{http_code} %{time_total}\n' '<lookup URL>'`.
     If this hangs too, the user agent or headers are the cause.
  2. The same with curl's own user agent.
  3. One tap of the Artbox short link through the server itself, with `RUST_LOG=info`: the outcome line.
  4. If only the server hangs, the TLS stack is the cause. The way out is another TLS client for this one call (a
     new dependency, the user's call), or leaving the place lookup off.
- **Changed.**
  - A 403 or 429, or a redirect to Google's `/sorry/…`, is now the outcome `blocked`.
  - After it, no lookup is sent for an hour; each ask in that hour is counted `blocked` without a request. Short
    links' hops still go out: they are another host.
  - The PWA's "lookup isn't working" line now shows on one `blocked`, or on 3 lookups in 24 hours that ended
    `consent`, `http`, `format` or `unreachable` with nothing found. Before, an outage booked as `unreachable` alone
    left the line silent.
  - A **burst limit** on top of the hourly 100: **at most 10 calls out in any 10 minutes, server-wide**. Hops and
    lookups both count. That is half of what tripped the wall (about 20 in 5 minutes) over twice the time.
  - The server's rows `wall_403`, `wall_sorry` and the new `lookup_line` rows, and
    `the_wall_pauses_lookups_and_a_burst_is_capped`, pin this.
- **Proven 2026-11-19: the TLS hello.** The user's server (0.85.1) logged `unreachable` 4 s after the hops, while
  curl from its VM got 200 in 0.3 s. From the dev VM, 7 requests:
  - rustls's default hello (TLS 1.2 and 1.3, ring) got a **403 after 5-6 s**, both with reqwest's own request and
    with curl's exact request bytes.
  - curl got 200 before and after, also when limited to rustls's three key-exchange groups (no ML-KEM).
  - rustls offering **TLS 1.3 alone** got 200 in 0.3-0.5 s, with curl's bytes and with the server's reqwest client.
  - The headers and the URL's encoding are not it.
  - Fix: the place lookup's client offers TLS 1.3 alone (`Kind::tls13_only`). The short links' hops are unchanged.

## Finding 1: what frees the Google hosts in step 2 (2026-11-19, `compat34` and `jelly36`)

The user, 2026-11-19: "På maps lenkene, kunne vi permanent av installert google maps? Du får teste ulike løsninger."
Measured with `pm get-app-link-owners --user 0` (the owners Settings' "Add link" reads) and the dialog itself, first by
adb on a phone without Vibb as Device Owner, then with the branch's build as Device Owner in a real pause. `jelly36`
restored from `~/kid-look-1-avd-pre-start` before and after (22 of 22 sums, `jelly36.ini` equal); `compat34` started with
`-wipe-data`.

**Who holds Vibb One's 20 hosts verified, in a pause.**
- Google Maps: `maps.google.*` (7), `www.google.{no,se,dk,fi,de,co.uk}` (6), `www.google.com`, `google.com`, `goo.gl`.
- The Google app: `www.google.com` (16: also `google.com`). Google Calendar: `www.google.com`. Google Photos: `goo.gl`.
- 16 only: the Play Store, `play.google.com` (the Play link blocker's host, not a map host).
- The Google app's and Calendar's `www.google.com` filters take only their own paths (`/search/about`, `/calendar/…`):
  they grey the host out in Settings but are never a candidate for a `/maps` link. Photos' `goo.gl` filter is
  `/photos/.*` only, the same.

| Approach | 14: hosts selectable | 16: hosts selectable |
|---|---|---|
| None (the gate) | 4 of 20 | 3 of 20 |
| A/B: the holders hidden (`setApplicationHidden`, during the page or the whole pause) | 0: a hidden app is still a verified owner, and the dialog never loads ("Ser etter andre linker som støttes …", `SupportedLinkWrapper: getLabel error`) | 0, the same hang (the Device Owner hiding all four, a local build) |
| C: Google Maps uninstalled for the user (`pm uninstall --user 0`) | 18 (`www.google.com` and `goo.gl` stay held) | 16 (`www.google.com`, `google.com`, `goo.gl`, `play.google.com`) |
| Google Maps suspended | 18 | 16 |
| **The four holders suspended while the page is open (built)** | **20** | **19** (`play.google.com`: Android refuses to suspend the Play Store) |

A suspended app's approval drops to `UNVERIFIED[-1]` and it leaves the owners; a hidden one stays `VERIFIED[4]`.
Uninstalling Google Maps alone leaves `www.google.com` - the commonest long form - greyed out on both versions, so C
doesn't solve the finding; it would also need the Google app removed.

**The selection, once made, stays** (by adb on both versions, then with the build):
- when the holders are unsuspended, when Google Maps is uninstalled and brought back (`cmd package install-existing`),
  and after a reboot;
- when an update of Google Maps is installed while it is uninstalled for the user (14, `pm install -r -i
  com.android.vending` of its own APK). That install makes it installed for the user again: a Play update of an
  uninstalled Maps would bring it back (Play's auto-update only touches installed apps; not tested with a real Play
  account).
- With Google Maps visible (a pause, or allowed by the parent) it wins: `VERIFIED[4]` over the parent's
  `USER_SELECTION[3]`. Outside a pause it is hidden, so Vibb One is the only approved app.

**The build (`map/MapLinkStep`, commit d2c30de44).** Tapping "Vibb Ones lenker" in a pause suspends, as Device
Owner, every other app holding one of Vibb One's hosts verified (never the enforcer's never-restrict apps or the Play
Store); the enforcer leaves them suspended meanwhile. They are given back when the page returns, when the pause ends
(the next pass's plan) or after 15 minutes.

| Row | `jelly36` (16) | `compat34` (14) |
|---|---|---|
| The step in a pause | `Holding 4 app(s) suspended for step 2, 0 refused`; Maps, the Google app, Calendar, Photos suspended; 19 of 20 selectable, 19 ticked (`play.google.com` greyed, "Åpnes i Google Play Store") | The same 4; 20 of 20, 20 ticked |
| A policy push during the step | The pass ran; the four stayed suspended | - |
| Back from the page | `Gave 4 app(s) back after step 2`; the rows "Ferdig" | The same |
| After the pause, Chrome as the login browser | `maps.google.com/?q=…`, `www.google.com/maps/place/…`, `www.google.no/maps/…`, `goo.gl/maps/…`, `maps.app.goo.gl/…`, `maps.apple.com/…` all reach `MapLinkActivity`; `example.com` goes to Chrome. A long Google link with a feature id opened as the pin in Organic Maps (canned place) | The same six reach `MapLinkActivity`; `example.com` Chrome |
| After a reboot | 19 selected, the same seven rows | 20 selected; the five Google rows reach `MapLinkActivity` (the emulator then crashed, exit 139, before the last two) |
| The report and the PWA | `selected 19, selectable 19, declared 20`, `reach` all true: "Klar." | `20, 20, 20`, all true: "Ready." |
| Google Maps back | Nothing is removed: the apps are unsuspended when the page returns; with the switch off nothing is left to undo | The same |

**`goo.gl`.** With the four suspended it is selectable on both versions, and `goo.gl/maps/…` reaches Vibb One after the
step. Photos is suspended for the page's minutes only, never removed.

Not run: Android 15 and 17, the pause ending while the page is open (the plan then decides; held apps are given back by
the 15-minute timer), a real Play update.

Screenshots (nb) outside the repo: the hanging dialog with hidden holders (14, 16), the dialog with the four suspended
(14, 16), with Google Maps uninstalled (14, 16), the step's dialog and result (14, 16), the Settings rows, the PWA's
"Klar.".
