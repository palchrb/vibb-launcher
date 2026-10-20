# 47 - "Add from Spotify" opens on the add form; the setup behind a row (proposal)

Asked 2026-10-20 ("trykker på add from spotify, så kommer jeg inn i en tab som øverst viser ... settings; client id og
pålogging av kontoer ... add spotify link, category og 'on these phones' ... er det som burde komme opp ... Blir mye
støy"). Server only (`handlers::music_spotify`, `music_spotify.html` split in two); no API change. Rides in server
round 5 (the B implementer) with designs 44/45 and the small items. Builds on design 26c §2 (the PWA's Spotify page).

## 0. Today

`GET /music/spotify` (`music_spotify.html`, reached from the Music tab's "From Spotify" row) is one page: Your
Spotify App (`#app`: client id, redirect URI, Clear), Children's Accounts (`#accounts`: Log in with Spotify, the paste
flow `#paste`, Remove), Add a Spotify link (`#add`: link, category), On these phones (`#add-phones`), the added
result (`#added`). Every POST comes back to its own group's fragment (the no-jump rule).

## 1. Decisions

The Music tab already has one settings place, `/music/settings` (`music_settings.html`: New episodes, **Storytel** -
the login, On phones, Pick Books -, The music app, Files no entry uses), and the Storytel shelf page already shows the
pattern for "not set up yet" (`music_storytel.html`: "No Storytel login is saved - Save the family's login first" and a
row to `/music/settings#storytel`). Spotify follows that pattern instead of getting a page of its own (the user,
2026-10-20: "burde spotify heller legges inn der?").

1. **`GET /music/spotify` is the add page**: back link "Music" (`/music#spotify`), title "Add from Spotify", then
   "Add a Spotify link" (`#add`), "On these phones" (`#add-phones`), the added result (`#added`), and one row at the
   end, **"Spotify setup"** with its value - "2 accounts" (the number of children's accounts logged in), "App saved, no
   account yet", or "Not set up yet" - to `/music/settings#spotify`.
2. **The setup moves into Music › Settings as its own group, "Spotify"** (`#spotify`, after Storytel): Your Spotify
   App (`#spotify-app`: client id, redirect URI, Clear) and Children's Accounts (`#spotify-accounts`: Log in with
   Spotify, the paste flow `#spotify-paste`, Remove) as they are today. Every id of the moved markup takes the
   `spotify-` prefix (`music_settings.html` already has `#app`): `spotify-key`, `spotify-redirect-uri`,
   `spotify-app-form`, `spotify-setup`, `spotify-app-clear`, `spotify-account-{id}`, `spotify-login`, `spotify-paste`,
   plus the three field ids (`spotify-client-id`, `spotify-redirect-uri-field`, `spotify-paste-url`). Each form's
   action fragment and each handler's redirect carry the prefixed id (`every_form_on_every_page_lands_on_its_own_group`
   checks them). The POSTs `save_app`, `clear_app`, `login`, `paste`, `remove_account` come back to
   `/music/settings?…#spotify-…` with their notices; `done`'s target is `/music/settings?login={code}#spotify-accounts`;
   `add` comes back to `/music/spotify?…#add|#add-phones|#added` as today. Nothing else on the settings page reads
   `login|saved|cleared|removed` (`show_settings` reads only the Storytel session flash).
   Refusals re-render in place: `save_app`, `clear_app`, `login` and `paste` answer a refusal by rendering Music ›
   Settings with the Spotify `Entered` state (values, the error field, `autofocus`, `login_elsewhere` =
   `https://{target}/music/settings#spotify-accounts`), through `render_settings(state, headers, trusted, status,
   storytel, spotify: Entered)` (the headers and the peer's trust feed the redirect-URI and host checks). The view
   gains the Spotify group's data, loaded by the same functions the Spotify page used. `render_settings` sets
   `page_csp` (ADMIN_CSP with `form-action` widened to Spotify's accounts origin, as the Spotify page's `render` did:
   the browser checks it on the 303 that `login` answers with).
3. **Not set up yet** (no app, or no child account): the add page shows, as the Storytel shelf does, a row on top
   ("No Spotify app is saved - set it up first" / "No child's account is logged in yet", `attention attention-action`,
   the chevron) linking to `/music/settings#spotify`, and the add form below it stays visible but disabled
   (`fieldset disabled`, the sub-line says why). The add POST's other refusals get their own attention-row words:
   `SpotifyError::Reauthorize` ("An account needs to log in again") and no music key on the server ("The server has
   no music key"), both linking to `#spotify-accounts` / `#spotify-key`. `add`'s handler refuses a POST in that state
   as it does today.
4. **The Music tab's "From Spotify" row** keeps its link and its `spotify_state` value; the Settings row shows no
   value today and stays so.
5. One view struct per page; the shared pieces move with their groups; the add page needs only the setup summary
   (counts) and the add form's state. `music_spotify.html` keeps the add groups; the setup groups become a partial
   (`partials/spotify_setup.html`) included by `music_settings.html`, so the markup moves rather than being retyped.

6. **Who plays as which account, from Music › Settings** (the user, 2026-10-20: "Ja jeg liker utvidelse 1 og 2").
   Each account row in the Spotify group shows which phones play as it ("Plays on Annas telefon"; "Plays on Annas
   telefon, Olas telefon" when several; "Plays on no phone yet") and carries a "Plays on" picker (`select
   data-autosave`, id `spotify-account-{id}-plays-on`; options "No phone" and every enrolled phone; when several
   phones are bound the selected option is a disabled "Several phones"). The binding is per phone
   (`device_policy.music_spotify_account`, Device › Music's "Plays as" = `handlers::music::save_device_spotify`,
   `POST /devices/{id}/music/spotify`, which takes the phone from the path), so the picker needs **a new narrow
   handler the other way round**: `POST /music/spotify/accounts/{id}/plays-on` with `device` = a phone id or `none`.
   Picking a phone binds that phone to this account (a phone bound to another account moves) and unbinds this
   account from every other phone - one phone per account by choice, because an account plays on one device at a
   time (the footer's advice stays); "No phone" unbinds them all. The UPDATE is the one `save_device_spotify` runs,
   extracted into a helper both call (it bumps the library revision through 0074's trigger, so the phones sync);
   the handler nudges the phones it changed, writes the same `log_event`, refuses an unknown phone or account (400,
   nothing written) and redirects to `/music/settings#spotify-account-{id}` (no jump).
7. **Storytel on phones, from Music › Settings.** The Storytel group's "On phones · 2" row (`data-row="phones"`,
   `storytel_phones()`) becomes a tappable `details` row (`#storytel-phones`) that opens, on the same page, one switch
   per enrolled phone (`data-autosave`). Device › Music's "Storytel on this phone" is one checkbox of the whole-form
   handler `handlers::music::save_device_settings` (`POST /devices/{id}/music`, which also sets the mobile-data
   switches, `volume_cap` and `storage_limit` from the same form - a partial post would switch those off), so the
   switches post to **a new narrow handler**, `POST /music/storytel/phones/{id}` with `on` (the checkbox), which sets
   `device_policy.music_storytel` alone, nudges that phone, writes the same `log_event` the device page's save does,
   refuses an unknown phone (400) and redirects to `/music/settings#storytel-phones`. The count stays on the summary
   row. Never a jump: the redirect carries the fragment.

## 2. Tests (required)

`src/tests/music_spotify.rs` (or where the Spotify page tests are): the add page has the add form and the setup row
and no `client_id` field; Music › Settings has the Spotify group with the app and accounts forms and every form still
lands (`check_forms` on both pages); each POST's redirect lands on the right page's group; the not-set-up state renders
the attention row and the disabled form and the add POST is refused as before; the OAuth `done` return lands on
`/music/settings?login={code}#spotify-accounts`; `/music/settings`'s CSP names Spotify's accounts origin in `form-action`; a refusal (`save_app` with an empty id) re-renders Music › Settings with the `Entered` values and `autofocus`; the Reauthorize and no-key refusals show their attention-row words; the prefixed ids exist and no unprefixed Spotify id is left; the "Plays on" handler binds the chosen phone, moves it from another account, unbinds the account's other phones, "No phone" unbinds all, an unknown phone is a 400 with nothing written, and the row says "Plays on …"/"Several phones"/"Plays on no phone yet"; the Storytel phones handler sets `music_storytel` alone (the phone's mobile-data switches, `volume_cap` and `storage_limit` untouched - asserted), lands on `/music/settings#storytel-phones`, the summary count unchanged; the error ids (`spotify-app-error`, `spotify-clear-error`, `spotify-remove-error-{id}`, `spotify-paste-error`) carry the prefix too.

## 3. Device check

The iPhone: Music › From Spotify opens on the form; the setup row's count right; in Music › Settings, Save/Log in/Remove
come back to the Spotify group without a jump; a fresh server (no app) shows the attention row on the add page.

## Revision (2026-10-20, after qa-r5-design.md)

- 47-1: `render_settings` sets `page_csp`; a test asserts `/music/settings`'s CSP names Spotify's accounts origin.
- 47-2: new `render_settings` signature; refusals re-render in place with the Spotify `Entered` state; `login_elsewhere` -> `https://{target}/music/settings#spotify-accounts`.
- 47-3: every moved id listed with the `spotify-` prefix; each form action's fragment and each handler's redirect carry it.
- 47-4: Reauthorize and no-key refusals get their own attention-row words.
- 47-5: old §1.4 dropped (the Settings row shows no value); renumbered.
- 47-6: `done` -> `/music/settings?login={code}#spotify-accounts`; no other reader of those query keys on the settings page.
- New (the user): §1.6 "Plays on" picker per account and §1.7 Storytel phones `details` row (`#storytel-phones`), with tests in §2.
- Revision check (2026-10-20): §1.6 and §1.7 each get a new narrow handler (`plays-on`, `storytel/phones/{id}`) - the device page's handlers take the phone from the path or save the whole form; the error ids prefixed too.
