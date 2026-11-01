//! A phone's status as the Devices row and the summary's header say it (the user's pick C of
//! 2026-11-01, PLAN "Tidier status on Devices and the phone's summary"): the name, then one line on
//! the row and at most two in the header, each the first that holds of
//!
//! 1. not set up, access removed, not seen yet (the phone can't tell more);
//! 2. **turned off** - design 38's `phone_off` later than every `phone_on` and status report
//!    (`parent_events::offs_now`): "Turned off 21:14 · battery was 3 %";
//! 3. **offline** (`streams::connection_state`): "Offline · last seen 13:05";
//! 4. **a rule locks now**: the lock's words · the battery;
//! 5. **low battery** (the phone's own `low`, latched in `devices.battery_low_since`, while it
//!    isn't charging): "Low battery, 9 %" · today's screen time;
//! 6. otherwise today's screen time · the battery ("4 min today · 100 %, charging"), or the
//!    connection's word when the phone said neither.
//!
//! No "seen 7 min ago" and no battery age while the phone is online or away - the avatar's dot says
//! that; the last contact is said only as "last seen" once it is offline. The header's first line
//! is the same lead ("4 min screen time today" for the last case), its second the zone the phone is
//! in now ("Test since 22:19") · the battery (the screen time when the lead is the battery); a
//! phone turned off, offline or not reachable has the first line alone. Every word comes from the
//! catalogs (`devices.status.*`) in the reader's language, every time from [Zone::since].

use chrono::{DateTime, Utc};

use crate::display_time::Zone;
use crate::i18n::{t, t_params};
use crate::parent_events::OffSince;
use crate::streams::ConnectionState;
use crate::time_state::{LockBadge, ScreenTime};

/// The last report's battery.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Battery {
    pub level: i64,
    pub charging: bool,
}

impl Battery {
    /// From a report's two columns; `None` without a level (an older launcher).
    pub fn of(level: Option<i64>, charging: Option<bool>) -> Option<Battery> {
        level.map(|level| Battery {
            level,
            charging: charging == Some(true),
        })
    }
}

/// What the lines are made from; the pages already read every one of them.
pub struct Facts<'a> {
    pub connection: &'a ConnectionState,
    /// Turned off and not on since ([crate::parent_events::off_since]).
    pub off: Option<&'a OffSince>,
    /// While a rule locks the phone.
    pub lock: Option<&'a LockBadge>,
    pub battery: Option<Battery>,
    /// `devices.battery_low_since` is set: the phone said `low` (design 35 §2.5) and hasn't been
    /// on battery above Android's level since.
    pub low_latched: bool,
    pub screen: Option<&'a ScreenTime>,
    /// The zones the phone is in now and since when (`devices::zone_now`): the header's alone.
    pub zone_now: Option<(&'a str, Option<DateTime<Utc>>)>,
}

/// How a part of a line is set: the connection's colours, grey for a phone that can't be reached,
/// orange for what needs a look.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Tone {
    Plain,
    Online,
    Away,
    Off,
    Warn,
}

impl Tone {
    /// The part's class; empty for plain text.
    pub fn css(&self) -> &'static str {
        match self {
            Tone::Plain => "",
            Tone::Online => "state-online",
            Tone::Away => "state-away",
            Tone::Off => "state-offline",
            Tone::Warn => "tone-orange",
        }
    }
}

/// One part of a line; the template puts " · " between them.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Part {
    pub text: String,
    pub tone: Tone,
    /// The exact time, for `title=`.
    pub title: Option<String>,
    /// The part's `data-` attribute (`lock`, `battery`, `screen-time`, `power-off`, `seen`,
    /// `zone-now`): what the tests find it by.
    pub hook: Option<&'static str>,
    /// The battery icon's fill in percent, drawn before the text, with `label` for a screen reader.
    pub icon: Option<i64>,
    pub label: Option<String>,
}

impl Part {
    fn new(text: String, tone: Tone, hook: Option<&'static str>) -> Part {
        Part {
            text,
            tone,
            title: None,
            hook,
            icon: None,
            label: None,
        }
    }

    /// The part's words already say its screen-reader name ("Low battery, 9 %", nb "Lavt batteri,
    /// 9 %"): the template leaves the hidden name out.
    pub fn says_label(&self) -> bool {
        self.label.as_ref().is_some_and(|label| {
            let (text, label) = (self.text.to_lowercase(), label.to_lowercase());
            text.split(|c: char| !c.is_alphanumeric())
                .any(|word| word == label)
        })
    }

    fn titled(mut self, title: String) -> Part {
        self.title = Some(title);
        self
    }

    /// The battery icon, filled to `level`, and its spoken name.
    fn battery_icon(mut self, level: i64, zone: &Zone) -> Part {
        self.icon = Some(level.clamp(8, 100));
        self.label = Some(t(zone.lang(), "devices.status.battery_label").to_string());
        self
    }
}

/// One line: its parts in order.
pub type Line = Vec<Part>;

/// The first fact that holds (the module's list).
enum Lead<'a> {
    Alone(&'static str),
    Off(&'a OffSince),
    Offline(DateTime<Utc>),
    Lock(&'a LockBadge),
    Low(Battery),
    Plain,
}

fn lead<'a>(facts: &Facts<'a>) -> Lead<'a> {
    match facts.connection {
        ConnectionState::NotSetUp => return Lead::Alone("devices.status.not_set_up"),
        ConnectionState::Disconnected => return Lead::Alone("devices.status.disconnected"),
        ConnectionState::NotSeenYet => return Lead::Alone("devices.status.not_seen_yet"),
        _ => {}
    }
    if let Some(off) = facts.off {
        return Lead::Off(off);
    }
    if let ConnectionState::Offline { last_contact } = facts.connection {
        return Lead::Offline(*last_contact);
    }
    if let Some(lock) = facts.lock {
        return Lead::Lock(lock);
    }
    match facts.battery {
        Some(battery) if facts.low_latched && !battery.charging => Lead::Low(battery),
        _ => Lead::Plain,
    }
}

/// The Devices row's one line.
pub fn row(facts: &Facts, zone: &Zone, now: DateTime<Utc>) -> Line {
    match lead(facts) {
        Lead::Plain => {
            let line: Line = [screen(facts, zone, false), battery(facts, zone)]
                .into_iter()
                .flatten()
                .collect();
            if line.is_empty() {
                vec![connection_word(facts.connection, zone)]
            } else {
                line
            }
        }
        lead => {
            let mut line = lead_line(&lead, facts, zone, now);
            match lead {
                Lead::Lock(_) => line.extend(battery(facts, zone)),
                Lead::Low(_) => line.extend(screen(facts, zone, false)),
                _ => {}
            }
            line
        }
    }
}

/// The summary header's lines under the name: one for a phone that can't be reached, else the
/// lead and then the zone · the battery (the screen time when the lead is the battery).
pub fn header(facts: &Facts, zone: &Zone, now: DateTime<Utc>) -> Vec<Line> {
    let lead = lead(facts);
    let second: Line = match lead {
        Lead::Lock(_) | Lead::Plain => [zone_now(facts, zone, now), battery(facts, zone)]
            .into_iter()
            .flatten()
            .collect(),
        Lead::Low(_) => [zone_now(facts, zone, now), screen(facts, zone, true)]
            .into_iter()
            .flatten()
            .collect(),
        _ => Vec::new(),
    };
    let first = match lead {
        Lead::Plain => vec![
            screen(facts, zone, true).unwrap_or_else(|| connection_word(facts.connection, zone)),
        ],
        ref lead => lead_line(lead, facts, zone, now),
    };
    [first, second]
        .into_iter()
        .filter(|line| !line.is_empty())
        .collect()
}

/// The lead's own parts (not [Lead::Plain]'s, which has none).
fn lead_line(lead: &Lead, facts: &Facts, zone: &Zone, now: DateTime<Utc>) -> Line {
    let lang = zone.lang();
    match lead {
        Lead::Alone(key) => vec![Part::new(t(lang, key).to_string(), Tone::Off, None)],
        Lead::Off(off) => {
            let mut line = vec![
                Part::new(off.lead(zone, now), Tone::Off, Some("power-off"))
                    .titled(zone.exact(off.at)),
            ];
            line.extend(
                off.battery(lang)
                    .map(|words| Part::new(words, Tone::Plain, None)),
            );
            line
        }
        Lead::Offline(at) => vec![
            Part::new(
                t(lang, "devices.status.offline").to_string(),
                Tone::Off,
                None,
            ),
            Part::new(
                t_params(
                    lang,
                    "devices.status.last_seen",
                    &[("at", &zone.since(*at, now))],
                ),
                Tone::Plain,
                Some("seen"),
            )
            .titled(zone.exact(*at)),
        ],
        Lead::Lock(lock) => {
            // The lock's "at last report (2 min ago)" loses its time when it is the last
            // contact's: the report is the contact.
            let contact = facts
                .connection
                .last_contact()
                .map(|at| zone.relative(at, now));
            vec![Part::new(
                lock.words(lang, contact.as_deref()),
                Tone::Warn,
                Some("lock"),
            )]
        }
        Lead::Low(battery) => vec![
            Part::new(
                t_params(
                    lang,
                    "devices.status.low_battery",
                    &[("level", &battery.level.to_string())],
                ),
                Tone::Warn,
                Some("battery"),
            )
            .battery_icon(battery.level, zone),
        ],
        Lead::Plain => Vec::new(),
    }
}

/// "100 %", "62 %, charging" with the icon.
fn battery(facts: &Facts, zone: &Zone) -> Option<Part> {
    let battery = facts.battery?;
    let key = if battery.charging {
        "devices.status.battery_charging"
    } else {
        "devices.status.battery"
    };
    let words = t_params(zone.lang(), key, &[("level", &battery.level.to_string())]);
    Some(Part::new(words, Tone::Plain, Some("battery")).battery_icon(battery.level, zone))
}

/// "4 min today" (the row), "4 min screen time today" (the header).
fn screen(facts: &Facts, zone: &Zone, summary: bool) -> Option<Part> {
    facts.screen.map(|screen| {
        Part::new(
            screen.words(zone.lang(), summary),
            Tone::Plain,
            Some("screen-time"),
        )
    })
}

/// "Test since 22:19", or the zones' names alone.
fn zone_now(facts: &Facts, zone: &Zone, now: DateTime<Utc>) -> Option<Part> {
    let (names, since) = facts.zone_now?;
    Some(match since {
        Some(since) => Part::new(
            t_params(
                zone.lang(),
                "devices.status.zone_since",
                &[("zones", names), ("at", &zone.since(since, now))],
            ),
            Tone::Plain,
            Some("zone-now"),
        )
        .titled(zone.exact(since)),
        None => Part::new(names.to_string(), Tone::Plain, Some("zone-now")),
    })
}

/// "Online", "Away": a reachable phone that said neither its screen time nor its battery.
fn connection_word(connection: &ConnectionState, zone: &Zone) -> Part {
    let (key, tone) = match connection {
        ConnectionState::Online { .. } => ("devices.status.online", Tone::Online),
        _ => ("devices.status.away", Tone::Away),
    };
    Part::new(t(zone.lang(), key).to_string(), tone, None)
}

/// A line's words as the page shows them, " · " between the parts (the tests').
#[cfg(test)]
pub fn words(line: &Line) -> String {
    line.iter()
        .map(|part| part.text.as_str())
        .collect::<Vec<_>>()
        .join(" · ")
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::i18n::Lang;

    fn utc(s: &str) -> DateTime<Utc> {
        crate::display_time::parse_db_utc(s).unwrap()
    }

    /// Sunday 1 Nov 2026, 13:00 in Oslo.
    fn now() -> DateTime<Utc> {
        utc("2026-11-01 12:00:00")
    }

    fn online() -> ConnectionState {
        ConnectionState::Online {
            since: now(),
            last_contact: now(),
        }
    }

    fn facts(connection: &ConnectionState) -> Facts<'_> {
        Facts {
            connection,
            off: None,
            lock: None,
            battery: Some(Battery {
                level: 100,
                charging: false,
            }),
            low_latched: false,
            screen: None,
            zone_now: None,
        }
    }

    fn both(facts: &Facts) -> [(String, Vec<String>); 2] {
        [Lang::En, Lang::Nb].map(|lang| {
            let zone = Zone::from_setting("Europe/Oslo").in_lang(lang);
            (
                words(&row(facts, &zone, now())),
                header(facts, &zone, now()).iter().map(words).collect(),
            )
        })
    }

    fn pair(row: &str, header: &[&str]) -> (String, Vec<String>) {
        (
            row.to_string(),
            header.iter().map(|l| l.to_string()).collect(),
        )
    }

    /// Each state in both languages, row and header, first match wins (the module's list).
    #[test]
    fn each_state_on_the_row_and_the_header() {
        let on = online();
        let screen = ScreenTime {
            used: 4,
            budget: None,
            day: None,
        };
        let mut f = facts(&on);
        f.screen = Some(&screen);
        f.zone_now = Some(("Test", Some(utc("2026-11-01 08:19:00"))));
        // 6. Otherwise: the screen time · the battery; the header names it, then zone · battery.
        assert_eq!(
            both(&f),
            [
                pair(
                    "4 min today · 100 %",
                    &["4 min screen time today", "Test since 09:19 · 100 %"]
                ),
                pair(
                    "4 min i dag · 100 %",
                    &["4 min skjermtid i dag", "Test siden 09:19 · 100 %"]
                ),
            ]
        );
        f.battery = Some(Battery {
            level: 62,
            charging: true,
        });
        assert_eq!(
            both(&f)[1],
            pair(
                "4 min i dag · 62 %, lader",
                &["4 min skjermtid i dag", "Test siden 09:19 · 62 %, lader"]
            )
        );
        // 5. Low battery (latched, not charging): it leads, the screen time follows.
        f.battery = Some(Battery {
            level: 9,
            charging: false,
        });
        f.low_latched = true;
        assert_eq!(
            both(&f),
            [
                pair(
                    "Low battery, 9 % · 4 min today",
                    &[
                        "Low battery, 9 %",
                        "Test since 09:19 · 4 min screen time today"
                    ]
                ),
                pair(
                    "Lavt batteri, 9 % · 4 min i dag",
                    &[
                        "Lavt batteri, 9 %",
                        "Test siden 09:19 · 4 min skjermtid i dag"
                    ]
                ),
            ]
        );
        // Charging again: the latch holds until it runs on battery above the level, the line
        // doesn't.
        f.battery = Some(Battery {
            level: 9,
            charging: true,
        });
        assert_eq!(both(&f)[0].0, "4 min today · 9 %, charging");
        // 4. A rule locks: the lock's words · the battery.
        let lock = LockBadge {
            name: "Leggetid".to_string(),
            name_key: None,
            until: Some("07:00".to_string()),
            calls_off: true,
            at_last_report: None,
        };
        f.lock = Some(&lock);
        assert_eq!(
            both(&f),
            [
                pair(
                    "Leggetid until 07:00 · calls off · 9 %, charging",
                    &[
                        "Leggetid until 07:00 · calls off",
                        "Test since 09:19 · 9 %, charging"
                    ]
                ),
                pair(
                    "Leggetid til 07:00 · anrop av · 9 %, lader",
                    &[
                        "Leggetid til 07:00 · anrop av",
                        "Test siden 09:19 · 9 %, lader"
                    ]
                ),
            ]
        );
        // 3. Offline: the last contact, nothing else.
        let gone = ConnectionState::Offline {
            last_contact: utc("2026-11-01 11:05:00"),
        };
        let mut f2 = facts(&gone);
        f2.lock = Some(&lock);
        f2.screen = Some(&screen);
        f2.zone_now = f.zone_now;
        assert_eq!(
            both(&f2),
            [
                pair("Offline · last seen 12:05", &["Offline · last seen 12:05"]),
                pair(
                    "Frakoblet · sist sett 12:05",
                    &["Frakoblet · sist sett 12:05"]
                ),
            ]
        );
        // 2. Turned off: before offline and everything after it.
        let off = OffSince {
            at: utc("2026-10-31 20:14:00"),
            level: Some("3".to_string()),
        };
        f2.off = Some(&off);
        assert_eq!(
            both(&f2),
            [
                pair(
                    "Turned off yesterday 21:14 · battery was 3 %",
                    &["Turned off yesterday 21:14 · battery was 3 %"]
                ),
                pair(
                    "Slått av i går 21:14 · batteriet var 3 %",
                    &["Slått av i går 21:14 · batteriet var 3 %"]
                ),
            ]
        );
        // 1. A phone that can't tell more.
        for (state, en, nb) in [
            (ConnectionState::NotSetUp, "Not set up", "Ikke satt opp"),
            (
                ConnectionState::Disconnected,
                "Disconnected · access removed",
                "Tilgangen er fjernet",
            ),
            (
                ConnectionState::NotSeenYet,
                "Not seen yet",
                "Ikke sett ennå",
            ),
        ] {
            let mut f3 = facts(&state);
            f3.off = Some(&off);
            assert_eq!(both(&f3), [pair(en, &[en]), pair(nb, &[nb])]);
        }
    }

    /// A reachable phone that said nothing: its connection's word; a budget and the fallback
    /// zone's day; the lock's fallback name and its "at last report".
    #[test]
    fn the_words_around_the_edges() {
        let on = online();
        let mut f = facts(&on);
        f.battery = None;
        assert_eq!(
            both(&f),
            [pair("Online", &["Online"]), pair("På nett", &["På nett"])]
        );
        let away = ConnectionState::Away {
            last_contact: now(),
        };
        assert_eq!(both(&facts(&away))[1].0, "100 %");
        let mut quiet = facts(&away);
        quiet.battery = None;
        assert_eq!(both(&quiet)[1], pair("Borte", &["Borte"]));

        let budget = ScreenTime {
            used: 72,
            budget: Some(90),
            day: None,
        };
        f.screen = Some(&budget);
        assert_eq!(
            both(&f),
            [
                pair(
                    "1 h 12 min of 1 h 30 min today",
                    &["1 h 12 min of 1 h 30 min screen time today"]
                ),
                pair(
                    "1 t 12 min av 1 t 30 min i dag",
                    &["1 t 12 min av 1 t 30 min skjermtid i dag"]
                ),
            ]
        );
        let other_day = ScreenTime {
            used: 42,
            budget: None,
            day: Some("13. okt.".to_string()),
        };
        f.screen = Some(&other_day);
        assert_eq!(both(&f)[1].0, "42 min den 13. okt.");

        let lock = LockBadge {
            name: "Bedtime".to_string(),
            name_key: Some("devices.status.lock.bedtime"),
            until: None,
            calls_off: false,
            at_last_report: Some("for 20 min siden".to_string()),
        };
        f.lock = Some(&lock);
        assert_eq!(
            both(&f)[1].0,
            "Leggetid · ved siste melding (for 20 min siden)"
        );
        // The report is the last contact: its time isn't said twice.
        let stale = LockBadge {
            at_last_report: Some("just now".to_string()),
            ..lock.clone()
        };
        f.lock = Some(&stale);
        assert_eq!(both(&f)[0].0, "Bedtime · at last report");
    }

    /// The parts the template sets apart: the tones, the battery icon and its spoken name, the
    /// exact times.
    #[test]
    fn the_parts_carry_their_tone_icon_and_title() {
        let zone = Zone::from_setting("Europe/Oslo").in_lang(Lang::Nb);
        let gone = ConnectionState::Offline {
            last_contact: utc("2026-11-01 11:05:00"),
        };
        let line = row(&facts(&gone), &zone, now());
        assert_eq!(line[0].tone.css(), "state-offline");
        assert_eq!(line[1].hook, Some("seen"));
        assert_eq!(line[1].title.as_deref(), Some("2026-11-01 12:05:00 CET"));
        let on = online();
        let mut f = facts(&on);
        f.battery = Some(Battery {
            level: 3,
            charging: false,
        });
        let line = row(&f, &zone, now());
        assert_eq!(
            (line[0].icon, line[0].label.as_deref(), line[0].hook),
            (Some(8), Some("Batteri"), Some("battery"))
        );
        assert!(!line[0].says_label(), "\"3 %\" needs the spoken name");
        f.low_latched = true;
        let low = row(&f, &zone, now());
        assert_eq!(low[0].tone.css(), "tone-orange");
        assert!(low[0].says_label(), "{:?}", low[0].text);
        let en = Zone::from_setting("Europe/Oslo");
        assert!(row(&f, &en, now())[0].says_label());
    }
}
