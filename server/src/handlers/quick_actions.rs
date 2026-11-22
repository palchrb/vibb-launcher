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
    /// Slå på nå's default (design 123 §3.2: the first rule without days, else the first school rule, else the
    /// first): `data-on-default`, which `quick-sheets.js` picks when the selection must move.
    pub on_default: bool,
    /// The rule can be paused (design 123 §3.3, `lock_now::pausable`): `data-pause`. Every option can be turned on.
    pub pause: bool,
    pub name: String,
    /// "Kalender og nødanrop virker." (§6.2 B5; design 113: the rule is the select's, not repeated).
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
}

pub(crate) struct QuickActions {
    /// The sheet the address opens: "time" or "rules".
    pub open: Option<&'static str>,
    /// The Regler sheet opens in on mode (else pause).
    pub mode_on: bool,
    /// The address asked for Slå på regel's tile (`mode=on`): that tile is the chosen one even when
    /// Slå på nå can't be chosen and the sheet opens in pause mode.
    pub tile_on: bool,
    /// Why Slå på nå can't be chosen now (`m-on` disabled), if it can't.
    pub on_disabled: Option<String>,
    /// Pause regler is offered (design 123 §3.3): a rule can be paused, or a pause still runs. Without it the tile
    /// isn't rendered and the Regler sheet is in on mode only (no Hva segment, no `m-pause`).
    pub can_pause: bool,
    /// The phone's scope: every rule a parent may turn on (design 123 §3.1); an empty one is "Ingen regler".
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
    /// "Neste: Leggetid 20:30" while no rule is turned on now (design 101 §4.2), grey.
    pub next_line: Option<String>,
    pub running: Option<RunningView>,
    /// The running pauses, each "Leggetid på pause til 21:00" with its lift id (Avslutt nå).
    pub pauses: Vec<(i64, String)>,
    /// The Skjermtid sheet's note: "Brukt i dag: 1 t av 2 t" while the phone has a budget, else none.
    pub extra_note: Option<String>,
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

/// The rule's mode in words (§6.2 B5): what still works while it locks ("Kalender og nødanrop virker.").
pub(crate) fn mode_line(lang: Lang, rule: &TimeRuleRow, apps: &HashMap<String, String>) -> String {
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
        t(lang, "device.summary.qa.mode_line_none").to_string()
    } else {
        t_params(
            lang,
            "device.summary.qa.mode_line",
            &[("list", &listed(lang, &works))],
        )
    }
}

/// What the builder reads, besides the database.
pub(crate) struct Inputs<'a> {
    pub device_id: i64,
    pub latest: Option<&'a DeviceStatus>,
    /// The effective rules: the schedule's lines ("Neste", "Ingen regel nå").
    pub rules: &'a [TimeRuleRow],
    /// The phone's scope (`time_rules::phone_rule_rows`): the on-list (design 123 §3.2).
    pub on_rules: &'a [TimeRuleRow],
    /// The pausable rules (`lock_now::pausable`), a subset of `on_rules` (§3.3).
    pub pause_rules: &'a [TimeRuleRow],
    pub query: &'a HashMap<String, String>,
    /// The lock badge, while it isn't stale and a rule (not the screen time) locks: its name.
    pub rule_in_force: Option<String>,
    /// A time report was read, it is today's and the phone in touch (`time_state::report_is_fresh`), and no rule locks
    /// - the screen-time lock counts as none (design 101 §4.1): the Pause tile may say "Ingen regel nå".
    pub no_rule_now: bool,
    pub screen: Option<&'a crate::time_state::ScreenTime>,
    /// A refusal's words from `?done=` (shown in the sheet).
    pub refusal: Option<String>,
    pub zone: &'a Zone,
    pub now: DateTime<Utc>,
}

/// The opening's end: the setup hold's (design 114 §5.2, from the latest report), else the newest `start_pause` the
/// phone answered while its pause still runs - two hours after the answer (the phone's own pause length).
async fn setup_until(
    db: &sqlx::SqlitePool,
    device_id: i64,
    latest: Option<&DeviceStatus>,
    now: DateTime<Utc>,
) -> Result<Option<DateTime<Utc>>, sqlx::Error> {
    if let Some(end) = latest
        .and_then(|latest| crate::setup_status::hold_end(latest, now.timestamp_millis()))
        .and_then(DateTime::from_timestamp_millis)
    {
        return Ok(Some(end));
    }
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
        // Design 101 D2: without a daily limit the tile says what is used (the header no longer does).
        None => t_params(
            lang,
            "device.summary.qa.used",
            &[("used", &crate::display_time::duration(lang, screen.used))],
        ),
    });
    // The running pauses in the phone's own clock, for the lines that read the schedule (§4.1, §4.2).
    let local_pauses: Vec<(Option<i64>, chrono::NaiveDateTime)> = tz
        .map(|tz| {
            pause_rows
                .iter()
                .filter_map(|(_, rule_id, _, expires)| {
                    parse_db_utc(expires).map(|e| (*rule_id, e.with_timezone(&tz).naive_local()))
                })
                .collect()
        })
        .unwrap_or_default();
    let pause_line = pauses.first().map(|(_, words)| words.clone()).or_else(|| {
        input
            .rule_in_force
            .as_ref()
            .map(|name| t_params(lang, "device.summary.qa.rule_now", &[("rule", name)]))
    });
    // "Ingen regel nå": the fresh report says no rule locks, and the schedule agrees - no rule's window covers the
    // phone's now (a pause out of it): a report from before a rule's start can't say it (QA M4).
    let pause_line = pause_line.or_else(|| {
        (input.no_rule_now
            && lock.is_none()
            && now_local
                .is_some_and(|n| !lock_now::in_force_by_schedule(input.rules, &local_pauses, n)))
        .then(|| t(lang, "device.summary.qa.no_rule_now").to_string())
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

    // "Neste: Leggetid 20:30" / "Neste: Skole i morgen 08:00": the schedule's next start within 24 hours, from the
    // policy and the phone's clock, while no rule is turned on now (design 101 §4.2).
    let next_line = if on_line.is_some() {
        None
    } else {
        tz.zip(now_local).and_then(|(tz, now_local)| {
            let (rule, start) = lock_now::next_start(input.rules, &local_pauses, now_local)?;
            let clock = start.format("%H:%M").to_string();
            let at = if start.date() == now_local.date() {
                clock
            } else {
                t_params(lang, "time.tomorrow", &[("clock", &clock)])
            };
            let at = if zone.name() == Some(tz.name()) {
                at
            } else {
                t_params(lang, "device.summary.qa.phone_time", &[("at", &at)])
            };
            Some(t_params(
                lang,
                "device.summary.qa.next",
                &[
                    ("rule", &crate::time_rules::shown_name(lang, &rule.name)),
                    ("at", &at),
                ],
            ))
        })
    };

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
        setup_until(db, input.device_id, input.latest, now).await?
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
    // Design 123 §3.2: the first rule without days (made to be turned on; never the bedtime), else the first school
    // rule, else the first.
    let on_default = input
        .on_rules
        .iter()
        .find(|r| !r.bedtime && !crate::time_rules::has_days(r))
        .or_else(|| input.on_rules.iter().find(|r| r.kind == "school"))
        .or(input.on_rules.first())
        .map(|r| r.id);
    let rules: Vec<RuleOption> = input
        .on_rules
        .iter()
        .map(|rule| {
            let name = crate::time_rules::shown_name(lang, &rule.name);
            let mut warnings = Vec::new();
            if open_for_setup {
                warnings.push(if let Some(at) = until {
                    t_params(
                        lang,
                        "device.summary.qa.warn_setup",
                        &[("at", &zone.clock(at))],
                    )
                } else {
                    t(lang, "device.summary.qa.warn_setup_plain").to_string()
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
            RuleOption {
                id: rule.id,
                on_default: on_default == Some(rule.id),
                pause: input.pause_rules.iter().any(|p| p.id == rule.id),
                mode_line: mode_line(lang, rule, &apps),
                name,
                warnings,
            }
        })
        .collect();

    // What the address asks for (§6.1, §6.5); an unknown value is ignored.
    let open = match input.query.get("open").map(String::as_str) {
        Some("time") => Some("time"),
        // Design 123 §2: the Regler sheet opens with no rule too ("Ingen regler").
        Some("rules") => Some("rules"),
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
    // QA L4: Pause regler stays while a pause still runs (its End now is in the sheet).
    let can_pause = !input.pause_rules.is_empty() || !pauses.is_empty();
    // Without a pause the sheet is Slå på regel's alone.
    let tile_on = input.query.get("mode").map(String::as_str) == Some("on") || !can_pause;
    let mode_on = on_disabled.is_none() && tile_on;
    let default_rule = on_default
        .map(|id| id.to_string())
        .unwrap_or_else(|| "all".into());
    // A refusal's `rule=` wins while the mode offers it (any rule is turned on; a pausable one or "all" paused).
    let posted = input
        .query
        .get("rule")
        .filter(|r| {
            if mode_on || !can_pause {
                rules.iter().any(|o| o.id.to_string() == **r)
            } else {
                *r == "all" || rules.iter().any(|o| o.pause && o.id.to_string() == **r)
            }
        })
        .cloned();
    let selected = match posted {
        Some(rule) => rule,
        None if mode_on || !can_pause => default_rule,
        None => "all".to_string(),
    };
    let lengths = lock_now::lengths_offered(now_minute);
    let length = match lengths {
        [_, true, _] => "120",
        [true, false, _] => "60",
        _ => "day",
    };
    let extra_note = input
        .screen
        .and_then(|s| s.budget.map(|b| (s.used, b)))
        .map(|(used, budget)| {
            t_params(
                lang,
                "device.summary.qa.extra_used",
                &[
                    ("used", &crate::display_time::duration(lang, used)),
                    ("budget", &crate::display_time::duration(lang, budget)),
                ],
            )
        });
    Ok(QuickActions {
        open,
        mode_on,
        tile_on,
        on_disabled,
        can_pause,
        rules,
        selected,
        lengths,
        length,
        now_minute,
        time_line,
        pause_line,
        on_line,
        next_line,
        running,
        pauses,
        extra_note,
        // Without a sheet to show it in, a refusal stays in Today's group (the caller's flash).
        sheet_flash: input.refusal.filter(|_| open.is_some()),
        setup,
    })
}

/// The Avansert page's first row, "Åpne for oppsett" (design 93 §6.6), for a phone that has
/// reported; `None` before its first report.
pub(crate) struct SetupCard {
    /// Open for setup at the newest report: the sheet holds Lås igjen instead of the open button.
    pub open: bool,
    /// The row's value while open: "til 17:10".
    pub value: Option<String>,
    /// The sheet's line while open, shown only when the row has no value (design 103-11): the Play install mode's
    /// words, or "Åpnet for oppsett" for an open with no known end.
    pub open_text: String,
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
    if latest.is_none() {
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
        setup_until(db, device.id, latest, now).await?
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
