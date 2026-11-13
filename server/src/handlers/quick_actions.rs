//! The summary's Quick Actions as three tiles and their sheets (design 93 §6, §7): Skjermtid,
//! Pause regler and Slå på regel, each with a state line only when it says something, the sheet
//! the address opens (`?open=time|rules`, `mode=on|pause`, `rule=`), the running on-lock and
//! pauses, and the row while the phone is open for setup. The words are built here, in the
//! reader's language; the template lays them out.

use std::collections::HashMap;

use chrono::{DateTime, Utc};

use crate::display_time::{Zone, parse_db_utc};
use crate::i18n::{Lang, t, t_params};
use crate::lock_now::{self, LockNowRow};
use crate::models::DeviceStatus;
use crate::time_rules::TimeRuleRow;

/// One rule in the Regler sheet's select, with what the sheet says of it when it is chosen.
pub(crate) struct RuleOption {
    pub id: i64,
    /// A school rule: Slå på nå's default.
    pub school: bool,
    pub name: String,
    /// "Telefonen låses som i Skole: …" (§6.2 B5).
    pub mode_line: String,
    /// The `.qa-warn` lines for turning this rule on (§6.2 B5), joined by a newline for JS.
    pub warnings: Vec<String>,
}

impl RuleOption {
    pub fn warnings_joined(&self) -> String {
        self.warnings.join("\n")
    }
}

/// The running on-lock in the sheet's section A.
pub(crate) struct RunningView {
    pub id: i64,
    /// "Skole på til 18:30".
    pub what: String,
    /// "Startet 16:30 · på telefonen".
    pub sub: String,
    /// `ends_at` as stored: the change form's `seen`.
    pub seen: String,
    /// +30 min and +1 t are offered (not past midnight).
    pub add_offered: [bool; 2],
    /// The current end on the phone's clock, the Til field's placeholder.
    pub end_clock: String,
}

/// The row right under the phone's header while it is open for setup (§6.1 item 1).
pub(crate) struct SetupRow {
    pub text: String,
    pub sub: String,
    pub lock_again: bool,
}

pub(crate) struct QuickActions {
    /// The sheet the address opens: "time" or "rules".
    pub open: Option<&'static str>,
    /// The Regler sheet opens in on mode (else pause).
    pub mode_on: bool,
    /// Why Slå på nå can't be chosen now (`m-on` disabled), if it can't.
    pub on_disabled: Option<String>,
    pub rules: Vec<RuleOption>,
    /// The selected option: "all" or a rule id.
    pub selected: String,
    pub lengths: [bool; 3],
    /// The checked length: "60", "120", "180" or "day".
    pub length: &'static str,
    /// The phone's local minute now, for the button sentences (JS).
    pub now_minute: i64,
    pub time_line: Option<String>,
    pub pause_line: Option<String>,
    pub on_line: Option<String>,
    pub running: Option<RunningView>,
    /// The running pauses, each "Leggetid på pause til 21:00" with its lift id (Avslutt nå).
    pub pauses: Vec<(i64, String)>,
    /// The Skjermtid sheet's note.
    pub extra_note: String,
    /// A refusal's words, shown in the sheet it reopened.
    pub sheet_flash: Option<String>,
    pub setup: Option<SetupRow>,
}

impl QuickActions {
    pub fn selected_is(&self, id: i64) -> bool {
        self.selected == id.to_string()
    }

    /// Slå på nå's lengths: the value posted, the hours shown, whether it ends by midnight.
    pub fn length_options(&self) -> Vec<(&'static str, &'static str, bool)> {
        [("60", "1"), ("120", "2"), ("180", "3")]
            .into_iter()
            .zip(self.lengths)
            .map(|((value, hours), offered)| (value, hours, offered))
            .collect()
    }

    /// The warnings of the selected rule (none for "Alle regler").
    pub fn selected_warnings(&self) -> Vec<String> {
        self.rules
            .iter()
            .find(|r| self.selected_is(r.id))
            .map(|r| r.warnings.clone())
            .unwrap_or_default()
    }

    pub fn selected_mode_line(&self) -> String {
        self.rules
            .iter()
            .find(|r| self.selected_is(r.id))
            .or(self.rules.first())
            .map(|r| r.mode_line.clone())
            .unwrap_or_default()
    }
}

/// "a, b og c".
fn listed(lang: Lang, items: &[String]) -> String {
    match items {
        [] => String::new(),
        [one] => one.clone(),
        [rest @ .., last] => format!(
            "{} {} {last}",
            rest.join(", "),
            t(lang, "device.summary.qa.and")
        ),
    }
}

/// The rule's mode in words (§6.2 B5): what still works while it locks.
pub(crate) fn mode_line(lang: Lang, rule: &TimeRuleRow, apps: &HashMap<String, String>) -> String {
    let name = crate::time_rules::shown_name(lang, &rule.name);
    let mut works = Vec::new();
    if rule.calls_allowed {
        works.push(t(lang, "device.summary.qa.calls").to_string());
    }
    for package in crate::time_rules::parse_exempt_apps(&rule.exempt_apps_json).unwrap_or_default()
    {
        works.push(apps.get(&package).cloned().unwrap_or(package));
    }
    if rule.calendar_allowed {
        works.push(t(lang, "device.summary.qa.calendar").to_string());
    }
    if works.is_empty() {
        t_params(lang, "device.summary.qa.mode_line_none", &[("rule", &name)])
    } else {
        t_params(
            lang,
            "device.summary.qa.mode_line",
            &[("rule", &name), ("list", &listed(lang, &works))],
        )
    }
}

fn kind_rank(kind: &str) -> u8 {
    match kind {
        "school" => 0,
        "bedtime" => 1,
        _ => 2,
    }
}

/// For a launcher without `lock_now_v1` (§4.2): the rule of the same or a stronger kind already in
/// force at the phone's `now_local`, and its end - the card shows it, not the turned-on rule.
fn already(
    rule: &TimeRuleRow,
    rules: &[TimeRuleRow],
    now_local: chrono::NaiveDateTime,
) -> Option<(String, chrono::NaiveDateTime)> {
    rules
        .iter()
        .filter(|r| r.id != rule.id && kind_rank(&r.kind) <= kind_rank(&rule.kind))
        .find_map(|r| {
            let days = crate::time_rules::parse_days(&r.days_json)
                .map(|d| crate::time_rules::served_days(&d))
                .ok()?;
            let end = crate::time_state::covering_ends(&days, now_local)
                .into_iter()
                .max()?;
            Some((r.name.clone(), end))
        })
}

/// What the builder reads, besides the database.
pub(crate) struct Inputs<'a> {
    pub device_id: i64,
    pub latest: Option<&'a DeviceStatus>,
    pub rules: &'a [TimeRuleRow],
    pub query: &'a HashMap<String, String>,
    /// The lock badge, while it isn't stale and a rule (not the screen time) locks: its name.
    pub rule_in_force: Option<String>,
    pub screen: Option<&'a crate::time_state::ScreenTime>,
    /// `RelockCard::lock_again` and whether the launcher takes the commands.
    pub lock_again: bool,
    pub relock_capable: bool,
    /// A refusal's words from `?done=` (shown in the sheet).
    pub refusal: Option<String>,
    pub zone: &'a Zone,
    pub now: DateTime<Utc>,
}

/// The newest `start_pause` the phone answered while its pause still runs: its end, two hours
/// after the answer (the phone's own pause length).
async fn setup_until(
    db: &sqlx::SqlitePool,
    device_id: i64,
    now: DateTime<Utc>,
) -> Result<Option<DateTime<Utc>>, sqlx::Error> {
    let answered: Option<String> = sqlx::query_scalar(
        "SELECT acknowledged_at FROM device_commands WHERE device_id = ? AND command = ? \
         AND acknowledged_at IS NOT NULL AND succeeded IS NOT 0 ORDER BY id DESC LIMIT 1",
    )
    .bind(device_id)
    .bind(crate::window_relock::START_PAUSE)
    .fetch_optional(db)
    .await?;
    Ok(answered
        .as_deref()
        .and_then(parse_db_utc)
        .map(|at| at + chrono::Duration::hours(2))
        .filter(|end| *end > now))
}

/// Quick Actions for the summary. A DB error is the caller's 500.
pub(crate) async fn build(
    db: &sqlx::SqlitePool,
    input: Inputs<'_>,
) -> Result<QuickActions, sqlx::Error> {
    let zone = input.zone;
    let lang = zone.lang();
    let now = input.now;
    let now_ms = now.timestamp_millis();
    let tz = lock_now::phone_zone(input.latest);
    let now_local = tz.map(|tz| now.with_timezone(&tz).naive_local());
    let now_minute = now_local.map(lock_now::minute_of).unwrap_or(0);
    let lock = lock_now::running(db, input.device_id, now).await?;
    let capable_now = input.latest.is_some_and(|s| {
        s.capabilities_json
            .as_deref()
            .is_some_and(|c| c.contains(&format!("\"{}\"", lock_now::CAPABILITY)))
    });

    // The phone's clock: a lock's times are its local wall time; another zone than Phone Setup's says so.
    let phone_time = |lock: &LockNowRow, clock: String| {
        if zone.name() == Some(lock.zone.as_str()) {
            clock
        } else {
            t_params(lang, "device.summary.qa.phone_time", &[("at", &clock)])
        }
    };
    let reported_after = |stamp: &str| input.latest.is_some_and(|s| s.reported_at.as_str() > stamp);

    // The running pauses (rule lifts the server still sends), latest-ending first.
    let pause_rows: Vec<(i64, Option<i64>, Option<String>, String)> = sqlx::query_as(
        "SELECT id, rule_id, rule_name, expires_at FROM time_lifts WHERE device_id = ? \
         AND target = 'rule' AND ended_early_at IS NULL AND expires_at > datetime('now') \
         ORDER BY expires_at DESC, id DESC",
    )
    .bind(input.device_id)
    .fetch_all(db)
    .await?;
    let pause_words = |rule: &Option<String>, expires: &str| {
        let at = parse_db_utc(expires)
            .map(|e| zone.clock(e))
            .unwrap_or_default();
        match rule {
            Some(name) => t_params(
                lang,
                "device.summary.qa.rule_paused",
                &[
                    ("rule", &crate::time_rules::shown_name(lang, name)),
                    ("at", &at),
                ],
            ),
            None => t_params(lang, "device.summary.qa.all_paused", &[("at", &at)]),
        }
    };
    let pauses: Vec<(i64, String)> = pause_rows
        .iter()
        .map(|(id, _, rule, expires)| (*id, pause_words(rule, expires)))
        .collect();

    // §7's lines.
    let time_line = input.screen.map(|screen| match screen.budget {
        Some(budget) if budget > screen.used => t_params(
            lang,
            "device.summary.qa.left",
            &[(
                "left",
                &crate::display_time::duration(lang, budget - screen.used),
            )],
        ),
        Some(_) => t(lang, "device.summary.qa.used_up").to_string(),
        None => t(lang, "device.summary.qa.no_limit").to_string(),
    });
    let pause_line = pauses.first().map(|(_, words)| words.clone()).or_else(|| {
        input
            .rule_in_force
            .as_ref()
            .map(|name| t_params(lang, "device.summary.qa.rule_now", &[("rule", name)]))
    });
    let on_line = lock.as_ref().map(|l| {
        let line = t_params(
            lang,
            "device.summary.qa.on_until",
            &[
                ("rule", &crate::time_rules::shown_name(lang, &l.rule_name)),
                (
                    "at",
                    &phone_time(l, lock_now::end_words(lang, l.end_minute)),
                ),
            ],
        );
        if reported_after(l.changed_at.as_deref().unwrap_or(&l.created_at)) {
            line
        } else {
            t_params(lang, "device.summary.qa.waiting", &[("line", &line)])
        }
    });

    let running = lock.as_ref().map(|l| {
        let start = lock_now::end_words(lang, l.start_minute);
        let sub = if reported_after(l.changed_at.as_deref().unwrap_or(&l.created_at)) {
            "device.summary.qa.started"
        } else if l.changed_at.is_some() {
            "device.summary.qa.new_time_waiting"
        } else {
            "device.summary.qa.started_waiting"
        };
        RunningView {
            id: l.id,
            what: t_params(
                lang,
                "device.summary.qa.on_until",
                &[
                    ("rule", &crate::time_rules::shown_name(lang, &l.rule_name)),
                    (
                        "at",
                        &phone_time(l, lock_now::end_words(lang, l.end_minute)),
                    ),
                ],
            ),
            sub: t_params(lang, sub, &[("at", &start)]),
            seen: l.ends_at.clone(),
            add_offered: lock_now::ADD_MINUTES
                .map(|m| lock_now::extended(l.end_minute, m).is_some()),
            end_clock: if l.end_minute >= crate::time_rules::LAST_MINUTE {
                String::new()
            } else {
                lock_now::end_words(lang, l.end_minute)
            },
        }
    });

    // The opening for setup.
    let open_for_setup = input
        .latest
        .is_some_and(|s| crate::handlers::rules_now::open_for_setup(s, now_ms));
    let until = if open_for_setup {
        setup_until(db, input.device_id, now).await?
    } else {
        None
    };
    let setup = open_for_setup.then(|| {
        let paused = input.latest.is_some_and(|s| s.paused_at(now_ms));
        SetupRow {
            text: match (paused, until) {
                (true, Some(at)) => t_params(
                    lang,
                    "device.summary.qa.setup_until",
                    &[("at", &zone.clock(at))],
                ),
                (true, None) => t(lang, "device.summary.qa.setup_open").to_string(),
                (false, _) => t(lang, "device.summary.qa.setup_play").to_string(),
            },
            sub: if input.lock_again {
                t(lang, "device.summary.qa.setup_sub").to_string()
            } else {
                t(lang, "device.summary.pause_ends_old").to_string()
            },
            lock_again: input.lock_again,
        }
    });

    // The rule options and their warnings.
    let apps = crate::window_relock::app_names(
        input.latest.and_then(|s| s.installed_apps_json.as_deref()),
    );
    let all_paused = pause_rows
        .iter()
        .find(|(_, rule_id, _, _)| rule_id.is_none())
        .map(|(_, _, _, expires)| {
            let at = parse_db_utc(expires)
                .map(|e| zone.clock(e))
                .unwrap_or_default();
            t_params(lang, "device.summary.qa.warn_all_paused", &[("at", &at)])
        });
    let rules: Vec<RuleOption> = input
        .rules
        .iter()
        .map(|rule| {
            let name = crate::time_rules::shown_name(lang, &rule.name);
            let mut warnings = Vec::new();
            if open_for_setup {
                warnings.push(if !input.relock_capable {
                    t(lang, "device.summary.qa.warn_no_relock").to_string()
                } else if let Some(at) = until {
                    t_params(
                        lang,
                        "device.summary.qa.warn_setup",
                        &[("at", &zone.clock(at)), ("rule", &name)],
                    )
                } else {
                    t_params(
                        lang,
                        "device.summary.qa.warn_setup_plain",
                        &[("rule", &name)],
                    )
                });
            }
            if let Some((_, _, _, expires)) = pause_rows
                .iter()
                .find(|(_, rule_id, _, _)| *rule_id == Some(rule.id))
            {
                let at = parse_db_utc(expires)
                    .map(|e| zone.clock(e))
                    .unwrap_or_default();
                warnings.push(t_params(
                    lang,
                    "device.summary.qa.warn_paused",
                    &[("rule", &name), ("at", &at)],
                ));
            }
            warnings.extend(all_paused.clone());
            if !capable_now
                && let Some(local) = now_local
                && let Some((other, end)) = already(rule, input.rules, local)
            {
                let at = if end.date() > local.date() {
                    t(lang, "device.summary.rules_now.midnight").to_string()
                } else {
                    end.format("%H:%M").to_string()
                };
                warnings.push(t_params(
                    lang,
                    "device.summary.qa.warn_already",
                    &[
                        ("rule", &crate::time_rules::shown_name(lang, &other)),
                        ("at", &at),
                    ],
                ));
            }
            RuleOption {
                id: rule.id,
                school: rule.kind == "school",
                mode_line: mode_line(lang, rule, &apps),
                name,
                warnings,
            }
        })
        .collect();

    // What the address asks for (§6.1, §6.5); an unknown value is ignored.
    let open = match input.query.get("open").map(String::as_str) {
        Some("time") => Some("time"),
        Some("rules") if !rules.is_empty() => Some("rules"),
        _ => None,
    };
    let on_disabled = if let Some(l) = &lock {
        Some(t_params(
            lang,
            "device.summary.qa.on_busy_line",
            &[("rule", &crate::time_rules::shown_name(lang, &l.rule_name))],
        ))
    } else if tz.is_none() {
        Some(t(lang, "device.summary.flash.on_no_zone").to_string())
    } else if now_minute >= crate::time_rules::LAST_MINUTE {
        Some(t(lang, "device.summary.flash.day_over").to_string())
    } else {
        None
    };
    let mode_on =
        on_disabled.is_none() && input.query.get("mode").map(String::as_str) == Some("on");
    let default_rule = rules
        .iter()
        .zip(input.rules)
        .find(|(_, row)| row.kind == "school")
        .or(rules.iter().zip(input.rules).next())
        .map(|(option, _)| option.id.to_string())
        .unwrap_or_else(|| "all".into());
    let posted = input
        .query
        .get("rule")
        .filter(|r| *r == "all" || rules.iter().any(|o| o.id.to_string() == **r))
        .cloned();
    let selected = match posted {
        Some(rule) if !(mode_on && rule == "all") => rule,
        _ if mode_on => default_rule,
        _ => "all".to_string(),
    };
    let lengths = lock_now::lengths_offered(now_minute);
    let length = match lengths {
        [_, true, _] => "120",
        [true, false, _] => "60",
        _ => "day",
    };
    let extra_note = match input.screen.and_then(|s| s.budget.map(|b| (s.used, b))) {
        Some((used, budget)) => t_params(
            lang,
            "device.summary.qa.extra_used",
            &[
                ("used", &crate::display_time::duration(lang, used)),
                ("budget", &crate::display_time::duration(lang, budget)),
            ],
        ),
        None => t(lang, "device.summary.qa.extra_note").to_string(),
    };
    Ok(QuickActions {
        open,
        mode_on,
        on_disabled,
        rules,
        selected,
        lengths,
        length,
        now_minute,
        time_line,
        pause_line,
        on_line,
        running,
        pauses,
        extra_note,
        // Without a sheet to show it in, a refusal stays in Today's group (the caller's flash).
        sheet_flash: input.refusal.filter(|_| open.is_some()),
        setup,
    })
}

/// The Avansert page's first row, "Åpne for oppsett" (design 93 §6.6), for a launcher that takes
/// the commands (`window_relock_v1`); `None` for an older one.
pub(crate) struct SetupCard {
    /// Open for setup at the newest report: the sheet holds Lås igjen instead of the open button.
    pub open: bool,
    /// The row's value while open: "til 17:10".
    pub value: Option<String>,
    /// "Åpnet for oppsett til 17:10" (or the Play install mode's words).
    pub open_text: String,
    /// What opening does, with the phone's name.
    pub note: String,
    /// A rule turned on now is paused meanwhile: "Skole på til 18:30 står på pause så lenge."
    pub on_lock: Option<String>,
    /// "Sendt…" after a press, in the group (`?notice=`).
    pub flash: Option<&'static str>,
    /// The page reloads itself while the phone hasn't shown what the press did.
    pub wait_for_phone: bool,
}

pub(crate) async fn setup_card(
    db: &sqlx::SqlitePool,
    device: &crate::models::Device,
    latest: Option<&DeviceStatus>,
    notice: Option<&str>,
    zone: &Zone,
    now: DateTime<Utc>,
) -> Result<Option<SetupCard>, sqlx::Error> {
    if !crate::handlers::window_relock::takes_commands(latest) {
        return Ok(None);
    }
    let lang = zone.lang();
    let now_ms = now.timestamp_millis();
    let relock =
        crate::handlers::window_relock::card(db, device.id, latest, notice, zone, now, None)
            .await?;
    let open = latest.is_some_and(|s| crate::handlers::rules_now::open_for_setup(s, now_ms));
    let paused = latest.is_some_and(|s| s.paused_at(now_ms));
    let until = if paused {
        setup_until(db, device.id, now).await?
    } else {
        None
    };
    let lock = lock_now::running(db, device.id, now).await?;
    Ok(Some(SetupCard {
        open,
        value: until.filter(|_| open).map(|at| {
            t_params(
                lang,
                "device.advanced.setup.until",
                &[("at", &zone.clock(at))],
            )
        }),
        open_text: match (paused, until) {
            (true, Some(at)) => t_params(
                lang,
                "device.summary.qa.setup_until",
                &[("at", &zone.clock(at))],
            ),
            (true, None) => t(lang, "device.summary.qa.setup_open").to_string(),
            (false, _) => t(lang, "device.summary.qa.setup_play").to_string(),
        },
        note: t_params(
            lang,
            "device.advanced.setup.note",
            &[("name", &device.name)],
        ),
        on_lock: lock.map(|l| {
            t_params(
                lang,
                "device.advanced.setup.on_lock",
                &[
                    ("rule", &crate::time_rules::shown_name(lang, &l.rule_name)),
                    ("at", &lock_now::end_words(lang, l.end_minute)),
                ],
            )
        }),
        flash: relock
            .notice
            .filter(|(_, key)| {
                *key == "device.summary.flash.start_pause_sent"
                    || *key == "device.summary.flash.end_windows_sent"
            })
            .map(|(_, key)| key),
        wait_for_phone: relock.wait_for_phone,
    }))
}
