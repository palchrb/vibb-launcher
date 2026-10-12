# QA 26b step 2a code review - the server's Storytel client, login and picker (2026-10-12)

Scope: branch `music-2-storytel-2a`, `685a6ae..a53064a` (`b92b1ef` music_net, `623183b` the client + 0066 + the log
floor, `7baa750` the PWA, `a53064a` the status). Read against design 26b §0, §1.1, §2.1-§2.3, §2.8, §7 (2a's rows), §8
step 2a, §9, §13, the step 2a status and `qa-26b-design.md` (L3, L4, L5, L9, L10), and against vibb `pipewire`
`1fcadac` (`pi/vibb/storytel.py`, `pi/daemon.py` 3824-3881, `pi/web/app.js` 505-535 and 746-990,
`tests/storytel_api.py`, `tests/storytel_routes.py`). Line numbers are `a53064a`'s.

Builds (QA, in the worktree): `cargo test` **559/560** - the one failure is
`tests::parent_pin::a_new_pin_ends_the_notices_but_not_the_list` (below: pre-existing, not Storytel's);
`cargo fmt --check` clean; `cargo clippy --all-targets` the same 12 warnings (`dns_engine.rs` 54/55,
`devices.rs:1237`, the never-read fields), none in the new code. The Storytel tests (22 with phase 1's) green 3/3.
`git diff --stat 685a6ae..a53064a -- server/migrations/` = only the new `0066`.

**Verdict: good to merge after a short fix round** (M1 is one line; the Lows are small). Counts: 0 High, 1 Medium,
6 Low. All 11 listed choices accepted (the status says 10; it lists 11). The four spec problems the implementer
found are real; QA agrees with each.

## Medium

1. **M1: the login URL - e-mail and `pwd=` - rides to a redirect target in a `Referer` header.**
   `server/src/music_net.rs:322-329` (`HttpSource::new` never calls `.referer(false)`; reqwest's default is on).
   Evidence: reqwest 0.13.4 `src/redirect.rs:294-304` (`make_referer`) copies the previous URL with its query (it
   strips only user, password and fragment, and only skips https -> http). QA ran it against a loopback server with
   the same reqwest: a GET of `.../api/login.action?m=1&uid=k%40example.invalid&pwd=AC6C` answered `302 Location:
   http://localhost:<port>/next`, and the second request carried `referer:
   http://127.0.0.1:<port>/api/login.action?m=1&uid=k%40example.invalid&pwd=AC6C`. vibb's `urllib` sends no
   `Referer`. Failure path: Storytel moves `login.action` behind a redirect to another host (a locale domain, an auth
   service, a CDN in front); every login (the save's probe, each picker visit after the hour, 2b's sweep) then hands
   that host the e-mail and the AES'd password (as good as the password, §0) in a header that load balancers and CDNs
   commonly log - exactly the leak `without_url` and the log floor exist to prevent, and no test sees it. Fix:
   `.referer(false)` in `HttpSource::new`'s builder (both clients; a podcast feed gains nothing from a Referer either),
   and a `no_secret_in_errors`-style loopback test: a redirect from the login URL to `localhost:<port>` whose second
   request has no `referer` header (or none holding `pwd=`).

## Low

1. **L1: the picker holds the session lock across unbounded network waits; a save's `keep` and a clear's `forget`
   queue behind it.** `server/src/storytel.rs:934-946` (`bookshelf`) and `:949-986` (`shelf`) take
   `self.session.lock()` and keep it through `credentials` + `fetch_shelf` (login, shelf, the 401 re-login and retry),
   each request first waiting for the one gate (behind a feed fetch of up to 120 s); `handlers/music.rs:1431` calls it
   with no time bound. `keep` (`storytel.rs:989`, called by the save at `handlers/music.rs:1185` after its bounded
   probe) and `forget` (`:1005`, clear at `:1216`) wait for that lock. The probe itself takes no lock (good: a probe
   never blocks anyone), and holding it for the whole read is what keeps two picker visits from logging in twice (they
   share the JWT - checked). Failure path: the sweep is fetching a slow feed when the parent opens the picker in one
   tab and saves the login in another: the picker page spins for up to ~200 s (a reverse proxy at 60 s gives a 504),
   and the save, whose probe answered within 45 s, doesn't answer until the picker's read ends; in 2b the sweep's
   `shelf(300 s)` joins the same queue. Fix: bound the picker's read like the probe - `tokio::time::timeout(PROBE_BUDGET,
   state.storytel.bookshelf(&state))` in `storytel_shelf`, a timeout shown as "Storytel didn't answer in time" (or the
   bound inside `Account::bookshelf`, so 2b's Check now gets it too); and let `keep` use `try_lock` and drop what it
   can't store (the generation check already makes a stale session harmless, so the cost is one extra login).

2. **L2: the `reqwest=info` floor can be bypassed by a more specific `RUST_LOG` directive, its test checks a string,
   and the stated reason no longer holds.** `server/src/main.rs:130-135`, `src/tests/music_storytel.rs:770-784`,
   `server/CLAUDE.md:648`, `main.rs:128`. Evidence (QA ran `log_filter` with tracing-subscriber 0.3.23 and a capturing
   layer): `RUST_LOG=reqwest=trace` is replaced by `reqwest=info` (good), but `RUST_LOG=reqwest::async_impl=debug`
   or `reqwest::connect=trace` keeps its own directive (EnvFilter picks the most specific target), and debug/trace
   events at `reqwest::async_impl::client` / `reqwest::connect::verbose` are printed. The test passes regardless: it
   only asserts that the filter's *string* contains `reqwest=info` and not `reqwest=trace`. Separately, reqwest 0.13.4
   (the lock's) no longer logs redirect URLs at all - redirects go through tower-http 0.6.11's `follow_redirect`, which
   logs nothing; its remaining URL-adjacent logs are host-only (`connect.rs:929`) and the raw-bytes trace of
   `connection_verbose`, which is off. hyper 1.11 / hyper-util 0.1.20 log no URI or header on the client side, and the
   tree has no `h2`. So no leak exists today; the floor is defence in depth, and the docs overstate both what reqwest
   does and what the floor guarantees. Fix: drop every `reqwest`/`reqwest::*` directive from `RUST_LOG` before adding
   the floor (split on `,`, filter on the target prefix), make the test behavioural (`tracing::subscriber::with_default`
   with the filter and a capturing layer; a `debug!(target: "reqwest::async_impl::client", ..)` and a
   `trace!(target: "reqwest::connect::verbose", ..)` under `debug`, `trace`, `reqwest=trace`,
   `reqwest::async_impl=debug` are dropped), and reword the comment and `server/CLAUDE.md`: "reqwest must never log
   below info here: a future version could log a URL (0.12 logged each redirect's), and the login's carries `pwd=`".

3. **L3: the no-`Debug` scan misses common spellings.** `server/src/storytel.rs:1081-1124`
   (`secret_types_have_no_debug`). It looks only at attribute lines directly above the type (a `// comment` line in
   between ends the walk), at a one-line `#[derive(..Debug..)]` (rustfmt splits a long derive over lines: `Debug,` alone
   is missed) and at `impl std::fmt::Debug for` / `impl Debug for` (the file's own `std::fmt::Formatter` style makes
   `use std::fmt; impl fmt::Debug for Jwt` the likely spelling - missed). Only the leaves matter (the compiler refuses
   a derived `Debug` on any holder of `Jwt`/`StoredLogin`/`SourceRequest` while they lack one), so the check can be
   exact. Fix: a compile-time check per leaf, e.g. the autoref probe `struct Probe<T>(PhantomData<T>); trait No { fn
   debug(&self) -> bool { false } } impl<T> No for Probe<T> {} impl<T: Debug> Probe<T> { fn debug(&self) -> bool { true }
   }` asserting `!Probe::<Jwt>(PhantomData).debug()` (and for `Display`), for `StoredLogin`, `Jwt`, `Credentials`,
   `SourceRequest`; keep the scan only if wanted as documentation.

4. **L4: `SourceResponse` derives `Debug` and holds the login's body - the JWT.** `server/src/music_net.rs:111`.
   `login_with` reads `accountInfo.jwt` out of a `SourceResponse` (`storytel.rs`, `login_with`); nothing prints one
   today, but a future `tracing::debug!(?response)` in `music_net` or the sweep would print the JWT (as a byte list,
   trivially decoded). It is the server twin of the phone's `StorytelResponse`, which §0/L9 redacts. Fix: a hand-written
   `Debug` printing `status`, `body.len()`, `truncated` and the validators, no body (the tests' `expect_err` still has
   its `Debug` bound).

5. **L5: a Storytel outage at save is reported as a wrong password.** `server/src/handlers/music.rs:1141-1151`.
   `probe` maps every non-200 login (a 503, a 429) to `Probe::Refused`, and the save answers "Storytel refused that
   e-mail and password. The saved login is unchanged." - per §2.3/vibb for *what is stored* (nothing; correct), but the
   text tells the parent their password is wrong while Storytel is down or rate-limiting, and they will retype it and
   retry. The event already holds the status. Fix: keep "nothing stored", word it by status - 401/403 (and `NoJwt`) keep
   today's text; anything else "Storytel answered 503 - nothing was saved; the saved login is unchanged. Try again
   later." (the same split vibb's picker makes between refused and unreachable).

6. **L6: Add reads an unbounded number of `target`s and dedupes them in quadratic time.**
   `server/src/handlers/music.rs:1453-1459`. The body limit is axum's default 2 MB, so a form can hold ~170 000
   distinct `target=` fields; `targets.contains` makes that ~10^10 string compares on one Tokio worker before the first
   off-shelf target refuses the form (seconds to minutes of CPU on a Pi). Only a signed-in parent can post it (CSRF and
   the `Strict` cookie stop a cross-site form), so it's a robustness fix, not a hole. Fix: refuse more than
   `MAX_ENTRIES` targets with the "isn't on the shelf" path's 400, and dedupe with a `HashSet` beside the `Vec`.

## The implementer's choices (status "Choices made while building")

| # | Choice | Verdict |
|---|---|---|
| 1 | The card's error lines carry no time (0066 has no column for `login_error`'s time) | Accept. The refused and accepted lines keep theirs. If the main session wants §2.3's "(10:02 UTC)" on errors, 0066 is still unreleased: a `login_error_at` column is free now and a new migration later. |
| 2 | `login_error` also holds the shelf's last outcome, cleared by a good shelf | Accept: the probe's "unreachable" includes the shelf, and with the JWT kept an hour a login-only code would go stale. Every write is scoped to its generation (`record`'s `WHERE generation = ?`). |
| 3 | Cooldown active while `0 <= now - at < 1 h` | Accept. Stamps are whole seconds and `Sweep::now()` usually has sub-seconds, so it only matters for an exactly equal (fake) clock; a clock set back still lifts the cooldown as vibb's `0 <` intended. |
| 4 | `tokio::time::Instant` + a per-account offset the tests move | Accept. In production the offset is zero and `tokio::time::Instant::now()` is the monotonic std clock; the `std::sync::Mutex` read per call is negligible (it could be `#[cfg(test)]`-only, not worth it). |
| 5 | No generic `api(state, request)`; `fetch_shelf` holds `_api`'s rule; `credentials` returns the generation and stamps | Accept: the bookshelf is the server's only authenticated call (the phones mint). |
| 6 | `normalize_shelf` edge cases (bool/list id = no id, a non-object row skipped, `77` and `"77"` one group, duplicates dropped shelf-wide) | Accept. Each is stricter or saner than Python's crash/duplicate-target behaviour and none changes a real shelf's result. |
| 7 | Add refuses the whole form when any target isn't on the shelf, and when nothing is ticked; 503 on an unreadable shelf; `full=1`; `all=1` kept; in-library targets skipped | Accept: the ticks come back checked, so the parent loses nothing; vibb 4c's rule holds. (L6 adds a count cap.) |
| 8 | The save's notice is a session flash; a refused probe is a 400 by the field | Accept (the import's pattern; no secret in the flash - checked). |
| 9 | Right after a refusal the picker says "for an hour", then names the time | Accept. |
| 10 | Entry page: a foreign stored value as "The first N (from Vibb)", the target as text with a shelf link; an empty group name shows the target / "Storytel series/book" | Accept. |
| 11 | Left for 2b: `Item`'s doc note, `not_on_shelf`, `kind_label`, `storytel_cache_zero_is_all`'s import and library parts | Accept: all in 2b's file list. 2b should create `music::tests::storytel_cache_zero_is_all` under that name (§7 names it; 2a's save part lives in `the_entry_page_offers_storytels_choices`). |

**The spec problems listed**: (1) §2.3's error time needs a column §2.8 lacks - agree (see choice 1); (3) §1.1's `0 <`
with whole-second stamps - agree; (4) §2.1's "the tests pause it" - agree, a paused clock auto-advances while sqlx
waits on its worker thread; §7 row 4's per-phone downloads and row 5's "the library loses the entries" are 2b's - agree:
both read `LIBRARY_ENTRIES`' Storytel gate and the card's `music_state` lines, which are 2b files. **One more for 2b**:
`Account::shelf` returns the last good shelf of this generation on *every* fetch error, `Refused` and `Cooldown`
included (vibb's `bookshelf_cached` does too), so 2b's "a login failure fails the check (`login_refused`)" (§2.4) and
§7's "`login_refused` inside the cooldown sends nothing" only show once no shelf of this generation is held in memory
(after a restart or a save). Either accept that in §2.4 (the card's status line already says "refused") or have the
sweep check read the row's cooldown before calling `shelf`.

## The vibb test map for 2a (checked against the Rust tests)

Every §7 row mapped to 2a is asserted, not just exercised: `storytel_api.py` 1 (all four vectors and the login URL), 2,
3 (POST, body, form type, `Bearer FAKE-JWT-1`, `Accept`, `Reach::Public`), 5 (once, always-401, renewal after the hour,
per generation), 6 (+ document order, numeric ids, missing id), 10b (stamp, 0/1/59 min with zero requests, a 5xx,
the new generation, `no_jwt`/outage without cooldown), 11 (no login, no key, another key with `storytel_key` stored, no
request); `storytel_routes.py` 1 (the three POSTs and the GET redirect to `/login`, nothing stored or fetched), 2
(accepted, refused with the old login kept, unreachable saved; no password or e-mail on any page or event), 3, 4 (kids
filter, `?all=1`, in-library rows disabled and linked), 4c (an off-shelf target and junk refused, nothing added), 5's
2a half (the revision triggers both ways). The L1/L3/L4/L5 rows after QA are there. Checked against an implementation
removal (by reading the tests): the probe bound, the 401 rule, the cooldown and the document order each fail their test when removed; the one
that doesn't is below (not worth fixing).

## Checked and fine

- **AES**: the code's `encrypt_password` (copied verbatim into a scratch crate) equals `openssl enc -aes-128-cbc -K
  56515a42... -iv 6a6f6977...` for `hemmelig`, `""`, 15, 16, 17 and 32 bytes, `pass æøå` and `🎧 ÆØÅ p@ss w0rd!` -
  a full padding block on a 16-byte multiple, UTF-8 bytes, UPPERCASE hex. The login URL: `form_urlencoded` gives
  `uid=k%40example.invalid`; it differs from Python's `quote_plus` only on `~` and `*`, equivalent once decoded.
- **`normalize_shelf`** against vibb 424-490 line by line: `model || item`, the first `abook` format, `model.id` (never
  the format id), `orderInSeries or 0`, `durationInMilliseconds or 0`, `_cover`, the flags from the abook format, the
  group's name/cover/kids from the first-seen book, `(order, title)` sort by code points. **Document order without
  `preserve_order`**: `RawShelf`/`Items` are `MapAccess` visitors that push `(key, Value)` pairs as they come; the
  fixture's document order (series 26175, book 999, series 4242, book 777) differs from key order (26175, 4242, 777,
  999) and its group cover from the reading-order first book, so the test discriminates. `serde_json` has no
  `indexmap` in the lock.
- **The account**: JWT 1 h on the monotonic clock, one re-login and one retry on a 401 (`fetch_shelf`), the cooldown
  (1 h, generation-keyed, `0 <=`), no request inside it; the shelf cache (300 s, `max_age` 0 always fetches, the last
  good shelf of the same generation on failure, never the old login's); the probe's 45 s around the whole attempt so
  the gate's wait counts; the picker always fetches fresh; `cache` 0 -> -1 on save and display.
- **Concurrency**: every Storytel request goes through `AppState.music_fetch` (`Gated`, tests too); the probe holds no
  lock; two picker visits share one JWT; two concurrent saves each probe their own login and `keep` the generation
  `RETURNING` gave them; a picker racing a save or a clear stores under the old generation, which is then ignored;
  `record` writes only its own generation's row state.
- **Secrets**: every added `tracing`, `log_event`, flash, template and error text carries codes, statuses, counts,
  generations, consumable ids or shelf keys - never the e-mail, password, `pwd=`, JWT or `Authorization`;
  `network_error` drops the URL on `send` and `chunk` errors and timeouts carry no text; our redirect policy's errors are
  our own strings; reqwest strips `Authorization` on a cross-host redirect of the bookshelf POST; fakes are
  `FAKE-JWT-1`, `*.example.invalid`, `hemmelig-pw`.
- **Web**: the routes sit under `require_full_auth`; `csrf_guard` covers every POST; the `Strict` cookie stops a
  cross-site GET of the picker from fetching anything; Askama escapes Storytel's names; all SQL is bound (the two
  `format!`s splice constants); every redirect target is a fixed path; `added` is parsed as a number; the
  `music_entry_added` detail is the cut name and the grammar-checked target.
- **Migration 0066**: byte-identical to §2.8's block; nullable columns on the singleton row; the triggers reference
  existing tables and columns (0049, 0050); no applied migration touched; every column the handlers write exists.
- **Templates at 360 px** (from the CSS): `.st-pick` is a flex row with a fixed 20 px checkbox and a `min-width: 0`,
  `overflow-wrap: anywhere` text; notes and details indent 30 px and wrap; `select { max-width: 100% }`; no fixed
  widths, no images; the card's status line reuses `.music-status` (no `data-busy`, so `music-cards.js` leaves it).
- `storytel.rs`'s header credits vibb (MIT, palchrb) and storytel-player 1.3.0 (debba, MIT); `server/CLAUDE.md`'s
  section is accurate but for L2's sentence.

## Not worth fixing

- `clear_drops_storytel_from_libraries`' "the session went with it" passes without `forget()` (the cleared row already
  makes `credentials` answer `NotConfigured` before any fetch); `forget` is belt and braces beside the generation
  check, and `the_shelf_cache_and_stale_beats_dead` tests it directly.
- Rate-limiting probes: each save is one deliberate parent action, a cross-site page can't trigger one (CSRF, `Strict`),
  a refused probe is one request, and unreachable logins cost Storytel nothing or are paced by the gate and human
  clicks; a throttle would mostly get in the way of a parent fixing a typo.
- The probe's bookshelf doesn't re-login on a 401 (vibb's `_api` would): the outcome class is the same (saved as
  unreachable), and the next read logs in again.
- Python's `TARGET_RE` (`$`) accepts a trailing newline; the Rust grammar refuses it - stricter is right.
- A duplicate key inside `items`: Python keeps the last value, the visitor processes both and the dedupe keeps the
  first. Storytel's shelf is keyed by id; it doesn't happen.
- The picker without a key says "No Storytel login is saved" although one is stored; the card says why (no key).
- The library list's hint lost "Times are UTC." (unlisted, `music.html:129`): every time on the cards already ends in "UTC".
- Until 2b, a Storytel entry's library card offers the podcast offline choices (`save_offline` is 2b's, QA M3); a 0
  stored meanwhile reads as "all" everywhere.

## Pre-existing (not this step)

`tests::parent_pin::a_new_pin_ends_the_notices_but_not_the_list` failed 6/6 here on the branch and 1/1 on `685a6ae`
(exported to the scratchpad with `git archive`, built with its own target dir), always at `parent_pin.rs:668`: the
lockout notice survives the second PIN save. The test posts events stamped 1-2 s *in the future* and expects a save
made shortly after to post-date them; when the save's whole-second stamp lands inside those 2 s (a fast machine), the
notice stays. Time-dependent, older than design 26b; the implementer's note is right that it isn't Storytel's, but it is
now failing consistently rather than occasionally. Left for its owner.
