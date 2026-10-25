use std::convert::Infallible;
use std::time::Duration;

use axum::extract::{ConnectInfo, Path, State};
use axum::http::{StatusCode, header};
use axum::response::IntoResponse;
use axum::response::sse::{Event, KeepAlive, Sse};
use axum::{Extension, Json};
use tokio_stream::StreamExt;
use tokio_stream::wrappers::{BroadcastStream, WatchStream};

use crate::AppState;
use crate::models::{
    CallPolicy, CommandReceiptRequest, CommandResultRequest, DeviceContactRow, DevicePolicy,
    DnsBlocklistCategory, DnsEventReport, DnsFilterSettings, EnrollRequest, EnrollResponse,
    GlobalSchedule, InstallProgressReport, InstalledApp, LauncherUi, PendingCommand, PolicyContact,
    PolicyResponse, StatusReportRequest, TrackedApp, TrackedAppUpdate,
};
use crate::security::{self, AuthedDevice};
use crate::time_rules::{LocationPolicy, TimePolicy};

/// `POST /api/devices/enroll` (design 22 §3.1): a code from the device page or a setup QR for a
/// device token. The code is normalised and hashed ([crate::enrollment]); using it is one atomic
/// `UPDATE ... RETURNING`, so two phones racing for one code get one token between them.
///
/// - Not code-shaped: 401 at once (nothing to look up, nothing counted).
/// - Typed-shaped: 429 while typed codes are paused or this client's failure bucket is full;
///   401 at once, uncounted, when no typed code is live; a miss counts toward both.
/// - QR-shaped: always checked (130 bits - guessing is hopeless), never paused.
///
/// At most `enrollment::IN_FLIGHT` enrollments run at once, reading the body included (within
/// `enrollment::BODY_TIMEOUT`); a failure is a throttled `enroll_failed` event, a success a
/// `device_enrolled` event with the client's IP.
pub async fn enroll(
    State(state): State<AppState>,
    ConnectInfo(addr): ConnectInfo<std::net::SocketAddr>,
    headers: axum::http::HeaderMap,
    body: axum::body::Body,
) -> axum::response::Response {
    // One of 8 slots first, then the body within 5 s (QA 22 M2): a slow body holds an enroll
    // slot for 5 s at most, never a phone's way in.
    let gate = state.enrollment.gate();
    let Ok(Ok(_slot)) = tokio::time::timeout(Duration::from_secs(5), gate.acquire()).await else {
        return (StatusCode::TOO_MANY_REQUESTS, [(header::RETRY_AFTER, "5")]).into_response();
    };
    let bytes = match tokio::time::timeout(
        crate::enrollment::BODY_TIMEOUT,
        axum::body::to_bytes(body, crate::device_routes::ENROLL_BODY_LIMIT),
    )
    .await
    {
        Ok(Ok(bytes)) => bytes,
        Ok(Err(_)) => return StatusCode::PAYLOAD_TOO_LARGE.into_response(),
        Err(_) => return StatusCode::REQUEST_TIMEOUT.into_response(),
    };
    let Ok(req) = serde_json::from_slice::<EnrollRequest>(&bytes) else {
        return StatusCode::BAD_REQUEST.into_response();
    };
    use crate::enrollment::{self, Kind};

    let client = state.net.client(&headers, addr.ip());
    let ip = client.describe();
    let key = client.key();
    let code = enrollment::normalize(&req.enrollment_code);
    let unauthorized = || {
        (
            StatusCode::UNAUTHORIZED,
            "invalid or expired enrollment code",
        )
            .into_response()
    };
    let too_many = |seconds: i64| {
        (
            StatusCode::TOO_MANY_REQUESTS,
            [(header::RETRY_AFTER, seconds.max(1).to_string())],
        )
            .into_response()
    };

    let Some(kind) = enrollment::shape(&code) else {
        return unauthorized();
    };
    let now = chrono::Utc::now();
    if kind == Kind::Typed {
        if let Some(until) = state.enrollment.typed_paused_until(now) {
            return too_many((until - now).num_seconds());
        }
        if let Some(refused) = state.limits.ip_blocked(&key) {
            return too_many(refused.retry_after_secs as i64);
        }
        if !state.enrollment.typed_live(now) {
            return unauthorized();
        }
    }

    let token = security::generate_device_token();
    let token_hash = security::hash_token(&token);
    let enrolled: Result<Option<(i64, String)>, sqlx::Error> = sqlx::query_as(
        "UPDATE devices SET token_hash = ?, enrollment_code_hash = NULL, \
         enrollment_code_kind = NULL, enrollment_code_expires_at = NULL, \
         enrolled_at = datetime('now'), mock_logged = NULL, mock_logged_at = NULL \
         WHERE enrollment_code_hash = ? AND enrollment_code_expires_at > datetime('now') \
         RETURNING id, name",
    )
    .bind(&token_hash)
    .bind(enrollment::code_hash(&code))
    .fetch_optional(&state.db)
    .await;

    let (device_id, name) = match enrolled {
        Ok(Some(device)) => device,
        Ok(None) => {
            let mut detail = format!("{} code", kind.as_str());
            if kind == Kind::Typed {
                if state.limits.ip_failed(&key) {
                    detail.push_str(" - this client is now refused for 10 minutes");
                }
                if state.enrollment.typed_failed(now) {
                    security::record_security_event(
                        &state.db,
                        "enroll_typed_paused",
                        None,
                        Some(&ip),
                        Some(&format!(
                            "{} wrong typed codes this hour - typed codes are paused until {} UTC; \
                             setup QR codes still work",
                            enrollment::TYPED_PAUSE_FAILURES,
                            enrollment::end_of_hour(now).format("%H:%M")
                        )),
                    )
                    .await;
                }
            }
            state
                .audit
                .record(&state.db, "enroll_failed", &key, Some(&ip), &detail)
                .await;
            return unauthorized();
        }
        Err(err) => {
            tracing::error!(%err, "enrollment failed");
            return StatusCode::INTERNAL_SERVER_ERROR.into_response();
        }
    };

    // The old token (a re-enrolled phone) stops working, the new one works from now on.
    state
        .tokens
        .refresh(&state.db, device_id, Some(&token_hash))
        .await;
    // Another phone in the row (design 64 §3.1): its zones' sides are the old phone's.
    if let Err(err) = crate::zones::forget_sides(&state.db, device_id).await {
        tracing::error!(device_id, %err, "can't forget the zones' sides at enrollment");
    }
    state.command_streams.close_device(device_id);
    state.enrollment.refresh(&state.db).await;
    // A command meant for the phone this row had before - a wipe for a lost one - must never
    // reach its replacement (QA of the launcher wave, Low 2).
    let dropped = end_pending_commands(&state.db, device_id).await;
    let mut detail = format!("{name} (device {device_id}), {} code", kind.as_str());
    if dropped > 0 {
        detail.push_str(&format!(
            "; {dropped} command(s) for the earlier phone won't be sent"
        ));
    }
    security::record_security_event(&state.db, "device_enrolled", None, Some(&ip), Some(&detail))
        .await;

    Json(EnrollResponse {
        device_id,
        device_token: token,
    })
    .into_response()
}

/// Why `build_policy` couldn't produce a policy. Every variant becomes an empty 500 - see
/// `policy`.
#[derive(Debug)]
pub(crate) enum PolicyError {
    Db(sqlx::Error),
    /// No `device_policy` row. `devices::create_device` inserts it in the same transaction as
    /// the device, so this only happens after manual DB edits or a restore gone wrong.
    MissingRow,
    CorruptAllowlist(serde_json::Error),
    /// A stored rule, exempt-app list or budget that isn't valid - sending the phone fewer
    /// rules (or no budget) than the parent set would open it up.
    CorruptTimePolicy(String),
}

impl std::fmt::Display for PolicyError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            PolicyError::Db(err) => write!(f, "database error: {err}"),
            PolicyError::MissingRow => write!(f, "no device_policy row"),
            PolicyError::CorruptAllowlist(err) => write!(f, "allowlist_json is not valid: {err}"),
            PolicyError::CorruptTimePolicy(err) => write!(f, "time policy is not valid: {err}"),
        }
    }
}

impl From<sqlx::Error> for PolicyError {
    fn from(err: sqlx::Error) -> Self {
        PolicyError::Db(err)
    }
}

/// Fails closed: anything this server can't read correctly becomes a 500 with an empty body,
/// never a default policy. Upstream answered a missing row or a DB error with
/// `DevicePolicy::default()` (kiosk off, no allowlist), which the launcher applied as "no
/// restrictions". The launcher treats any non-2xx as "no fresh policy" and keeps enforcing its
/// cached one.
pub async fn policy(
    State(state): State<AppState>,
    Extension(AuthedDevice(device)): Extension<AuthedDevice>,
) -> impl IntoResponse {
    match build_policy(&state, device.id).await {
        // `no-store`, as every device answer without its own (`limits`): also the one with the
        // Child PIN in it (design 59 §2.1).
        Ok(policy) => Json(policy).into_response(),
        Err(err) => {
            tracing::error!(device_id = device.id, %err, "failed to build device policy");
            StatusCode::INTERNAL_SERVER_ERROR.into_response()
        }
    }
}

/// `DevicePolicyManager.LOCK_TASK_FEATURE_BLOCK_ACTIVITY_START_IN_TASK`.
const LOCK_TASK_BLOCK_ACTIVITY_START: i64 = 64;

pub(crate) async fn build_policy(
    state: &AppState,
    device_id: i64,
) -> Result<PolicyResponse, PolicyError> {
    let policy =
        sqlx::query_as::<_, DevicePolicy>("SELECT * FROM device_policy WHERE device_id = ?")
            .bind(device_id)
            .fetch_optional(&state.db)
            .await?
            .ok_or(PolicyError::MissingRow)?;

    // NULL stays None: a genuinely unmanaged new device, before its first heartbeat bootstraps
    // the allowlist (see `status`). Anything stored but unparseable is an error, not "open".
    let allowlist = policy
        .allowlist_json
        .as_deref()
        .map(serde_json::from_str::<Vec<String>>)
        .transpose()
        .map_err(PolicyError::CorruptAllowlist)?;

    // The legacy schedule fields: the global default unless this device has its own override
    // turned on - see migrations/0017_schedules_page.sql. Since step 6 they're frozen (converted
    // into time rules once, `time_rules::migrate_legacy`) and only read by launchers without
    // `time_rules_v1`. A missing singleton row means "no schedule" (its migration seeds it); a
    // query error is a 500.
    let global = sqlx::query_as::<_, GlobalSchedule>("SELECT * FROM global_schedule WHERE id = 1")
        .fetch_optional(&state.db)
        .await?;
    let legacy = if policy.custom_schedule_enabled {
        (
            policy.weekday_start_minutes,
            policy.weekday_end_minutes,
            policy.weekend_start_minutes,
            policy.weekend_end_minutes,
            policy.bedtime_start_minutes,
            policy.bedtime_end_minutes,
        )
    } else {
        let g = global.clone().unwrap_or_default();
        (
            g.weekday_start_minutes,
            g.weekday_end_minutes,
            g.weekend_start_minutes,
            g.weekend_end_minutes,
            g.bedtime_start_minutes,
            g.bedtime_end_minutes,
        )
    };
    let (
        weekday_start_minutes,
        weekday_end_minutes,
        weekend_start_minutes,
        weekend_end_minutes,
        bedtime_start_minutes,
        bedtime_end_minutes,
    ) = legacy;

    let time_policy = build_time_policy(state, &policy, global.as_ref()).await?;
    // Design 64 step 2: the phone's zones (centre and radius, never the name) while Regularly,
    // and the served interval: at least 30 min while there are zones and the phone's watch holds
    // fences (the user's decision 3) - computed here, never written.
    let zones = crate::zones::list(&state.db, device_id).await?;
    let watch = crate::zones::last_zone_watch(&state.db, device_id).await?;
    let location_policy = LocationPolicy {
        mode: policy.location_mode.clone(),
        interval_minutes: crate::zones::served_interval(
            &policy.location_mode,
            policy.location_interval_minutes,
            zones.len(),
            crate::zones::watching(watch.as_ref()),
        ),
        // Q-10: the centres and radii only to a launcher that watches zones itself.
        zones: if last_capabilities(&state.db, device_id)
            .await?
            .iter()
            .any(|c| c == crate::models::ZONES_CAPABILITY)
        {
            crate::zones::policy_zones(&policy.location_mode, &zones)
        } else {
            Vec::new()
        },
    };

    // Design 36 §4.1: Custom with a valid pair is the `dns_upstream` object and "cloudflare" for a
    // launcher that doesn't read it; Custom without one reads as Cloudflare.
    let dns_settings =
        sqlx::query_as::<_, DnsFilterSettings>("SELECT * FROM dns_filter_settings WHERE id = 1")
            .fetch_optional(&state.db)
            .await?;
    let (dns_upstream_provider, dns_upstream) = match dns_settings {
        Some(s) if s.upstream == "custom" => (
            "cloudflare".to_string(),
            crate::dns_server::upstream_of(&s.custom_server, &s.custom_backup, s.custom_rev),
        ),
        Some(s) => (s.upstream, None),
        None => ("cloudflare".to_string(), None),
    };

    let dns_filter_version = compute_dns_filter_version(state, device_id).await?;

    let packages_to_uninstall: Vec<String> = sqlx::query_scalar(
        "SELECT package_name FROM device_pending_uninstalls WHERE device_id = ?",
    )
    .bind(device_id)
    .fetch_all(&state.db)
    .await?;

    let call_policy = build_call_policy(state, &policy).await?;

    // Vibb music (design 21): `null` when it can't be read (QA #3) - the phone keeps what it has; a
    // made-up empty library would make it delete its downloads. Never fails the policy.
    let music = match crate::music::policy_music(&state.db, &state.music_libraries, &policy).await {
        Ok(music) => Some(music),
        Err(err) => {
            tracing::error!(device_id, %err, "couldn't read the music library - policy sent with music: null");
            None
        }
    };

    // The lock's glance page (design 25): cosmetic like the wallpapers - a failing query sends no
    // apps (missed calls only on the phone's lock), never a 500 that would cost the phone its rules.
    let lock_glance = crate::lock_glance::policy_lock_glance(&state.db, device_id)
        .await
        .unwrap_or_else(|err| {
            tracing::error!(
                device_id,
                %err,
                "couldn't load the lock screen's apps - policy sent with lock_glance.apps: []"
            );
            crate::lock_glance::LockGlancePolicy { apps: Vec::new() }
        });

    // Popped last, after every read above has succeeded, and in one statement: a 500 never
    // consumes a command, and two concurrent polls can't both get the same one. A launcher that
    // confirms commands (security review L12) confirms each one before running it; one it didn't
    // confirm - the response was lost, or it refused the policy - is offered again after
    // REDELIVER_AFTER_MINUTES, at most MAX_COMMAND_DELIVERIES times in all and only within
    // REDELIVER_WINDOW_MINUTES of its first delivery, so nothing stale ever replays (QA of the
    // launcher wave, High 1; the phone runs a command once). For an older launcher delivery stays
    // at-most-once, which is why every command must be safe to simply queue again. A command
    // the parent cancelled is never handed out, nor - first or again - a pause, ring, stop or
    // location update past its hour (EXPIRING_COMMANDS, the user's decision 2026-10-12). See
    // migrations/0010_find_my_device.sql, 0065_command_receipts.sql, 0068 and handlers::locate.
    let capabilities = last_capabilities(&state.db, device_id).await?;
    let receipts = capabilities
        .iter()
        .any(|c| c == crate::models::COMMAND_RECEIPT_CAPABILITY);
    let popped = pop_command(&state.db, device_id, receipts, policy.kid_pin_rev).await?;
    if let Some((id, _, requested_at, _)) = &popped {
        // Commands run in the order they were asked for (QA Low 1): once this one is out, an older
        // one the phone didn't confirm is never offered again - a lost "pause" must not run after
        // a later "lock again". Its row stays, as history.
        sqlx::query(
            "UPDATE device_commands SET deliveries = MAX(deliveries, ?) WHERE device_id = ? \
             AND id != ? AND delivered_at IS NOT NULL AND received_at IS NULL \
             AND acknowledged_at IS NULL AND (requested_at < ? OR (requested_at = ? AND id < ?))",
        )
        .bind(crate::models::MAX_COMMAND_DELIVERIES)
        .bind(device_id)
        .bind(id)
        .bind(requested_at)
        .bind(requested_at)
        .bind(id)
        .execute(&state.db)
        .await?;
        if receipts {
            spawn_redelivery_nudge(state, device_id, *id);
        }
    }
    // `allow_blocked` carries the apps the card listed (QA of cmd-expiry, Low 1); a row queued
    // before 0068 has none, and the phone then lets out every block, as it did. Install Now's
    // payload is the release tag the row showed (design 37 §3) - a plain tag, only for it.
    // `set_kid_pin` (design 59 §2.1) carries the PIN opened from the row read above, so it is the
    // PIN of this answer's `kid_lock` hash - only in android mode and for a launcher whose last
    // report has the handler (an older one re-enrolled into the row gets the command without it).
    let pending_command = popped.map(|(id, command, requested_at, payload)| PendingCommand {
        allow: payload
            .as_deref()
            .filter(|_| command == crate::window_relock::ALLOW_BLOCKED)
            .and_then(|p| serde_json::from_str(p).ok()),
        release_tag: payload.filter(|_| command == crate::install_now::COMMAND),
        pin: (command == crate::kid_lock::SET_KID_PIN)
            .then(|| {
                crate::kid_lock::deliverable_pin(
                    state.kid_pin_key.as_deref(),
                    &policy,
                    device_id,
                    &capabilities,
                )
            })
            .flatten(),
        id,
        command,
        requested_at,
    });

    Ok(PolicyResponse {
        allowlist,
        weekday_start_minutes,
        weekday_end_minutes,
        weekend_start_minutes,
        weekend_end_minutes,
        bedtime_start_minutes,
        bedtime_end_minutes,
        kiosk_desired: policy.kiosk_desired,
        // The block bit travels as `block_activity_start` only (see PolicyResponse).
        lock_task_features: policy.lock_task_features.unwrap_or(0)
            & !LOCK_TASK_BLOCK_ACTIVITY_START,
        block_activity_start: policy.block_activity_start,
        kid_lock: crate::kid_lock::policy_kid_lock(&policy),
        screen_timeout_seconds: crate::models::screen_timeout_seconds(
            policy.screen_timeout_seconds,
        ),
        update_fence: policy.update_fence,
        notification_auto_cancel: policy.notification_auto_cancel,
        boot_cover: policy.boot_cover,
        dns_log_enabled: policy.dns_log_enabled,
        app_updates_wifi_only: policy.app_updates_wifi_only,
        override_pin_hash: policy.override_pin_hash,
        override_pin_salt: policy.override_pin_salt,
        quick_controls_mask: policy.quick_controls_mask,
        pending_command,
        vpn_filter_enabled: policy.vpn_filter_enabled,
        dns_filter_version,
        dns_upstream_provider,
        dns_upstream,
        packages_to_uninstall,
        call_policy,
        hardening: policy.hardening,
        launcher_ui: LauncherUi {
            language: policy.launcher_language,
            home_columns: policy.home_columns,
            clock_format: crate::models::clock_format(&policy.clock_format).to_string(),
            // Cosmetic: a failing wallpaper query must not cost the phone its time rules or
            // calls - it gets no list (navy) this time, and the error is logged.
            wallpapers: crate::wallpapers::policy_wallpapers(&state.db, device_id)
                .await
                .unwrap_or_else(|err| {
                    tracing::error!(
                        device_id,
                        %err,
                        "couldn't load the wallpapers - policy sent without them"
                    );
                    Vec::new()
                }),
            // Cosmetic too: a failing query leaves the lock on Home's wallpaper.
            lock_wallpaper: crate::wallpapers::lock_wallpaper(&state.db, device_id)
                .await
                .unwrap_or_else(|err| {
                    tracing::error!(
                        device_id,
                        %err,
                        "couldn't load the lock's wallpaper - policy sent with Home's"
                    );
                    None
                }),
            // Cosmetic like the wallpapers: a failing query sends no names and icons.
            app_display: crate::app_display::policy_app_display(&state.db, device_id)
                .await
                .unwrap_or_else(|err| {
                    tracing::error!(
                        device_id,
                        %err,
                        "couldn't load the app names and icons - policy sent without them"
                    );
                    Vec::new()
                }),
            lock_wake_on_notification: policy.lock_wake_on_notification,
        },
        time_policy,
        location_policy,
        music,
        lock_glance,
    })
}

/// The next command for this device (the pop of [build_policy], one statement): marks it delivered
/// and returns `(id, command, requested_at, payload)`. `kid_pin_rev` is the rev of the
/// `device_policy` row the answer was built from: a `set_kid_pin` goes out only while it is still
/// the stored one (design 59 S59-1) - a save that committed since stays waiting, its nudge brings
/// the next poll, and the PIN never goes out older than the stored hash.
pub(crate) async fn pop_command(
    db: &sqlx::SqlitePool,
    device_id: i64,
    receipts: bool,
    kid_pin_rev: i64,
) -> Result<Option<(i64, String, String, Option<String>)>, sqlx::Error> {
    let pop = format!(
        "UPDATE device_commands SET delivered_at = datetime('now'), deliveries = deliveries + 1, \
           first_delivered_at = COALESCE(first_delivered_at, datetime('now')) \
         WHERE id = (SELECT id FROM device_commands WHERE device_id = ? \
           AND {} \
           AND (command NOT IN (?, ?, ?, ?) OR requested_at > datetime('now', ?)) \
           AND (command != ? OR (SELECT kid_pin_rev FROM device_policy \
                WHERE device_policy.device_id = device_commands.device_id) = ?) \
           AND (delivered_at IS NULL OR (? AND deliveries < ? \
                AND delivered_at <= datetime('now', ?) \
                AND first_delivered_at > datetime('now', ?))) \
          ORDER BY requested_at ASC, id ASC LIMIT 1) \
         RETURNING id, command, requested_at, payload",
        crate::models::COMMAND_OPEN
    );
    sqlx::query_as(&pop)
        .bind(device_id)
        .bind(crate::models::EXPIRING_COMMANDS[0])
        .bind(crate::models::EXPIRING_COMMANDS[1])
        .bind(crate::models::EXPIRING_COMMANDS[2])
        .bind(crate::models::EXPIRING_COMMANDS[3])
        .bind(format!(
            "-{} minutes",
            crate::models::COMMAND_EXPIRY_MINUTES
        ))
        .bind(crate::kid_lock::SET_KID_PIN)
        .bind(kid_pin_rev)
        .bind(receipts)
        .bind(crate::models::MAX_COMMAND_DELIVERIES)
        .bind(format!(
            "-{} minutes",
            crate::models::REDELIVER_AFTER_MINUTES
        ))
        .bind(format!(
            "-{} minutes",
            crate::models::REDELIVER_WINDOW_MINUTES
        ))
        .fetch_optional(db)
        .await
}

/// The device's effective rules and budget (its own while `custom_schedule_enabled`, else the
/// global ones) and the lifts still to deliver. Anything stored that doesn't validate is an error
/// (500): the phone keeps its cached rules instead of getting fewer.
async fn build_time_policy(
    state: &AppState,
    policy: &DevicePolicy,
    global: Option<&GlobalSchedule>,
) -> Result<TimePolicy, PolicyError> {
    let rules = crate::time_rules::effective_rule_rows(
        &state.db,
        policy.device_id,
        policy.custom_schedule_enabled,
    )
    .await?
    .iter()
    .map(|row| row.to_policy())
    .collect::<Result<Vec<_>, _>>()
    .map_err(PolicyError::CorruptTimePolicy)?;
    let budget_json = if policy.custom_schedule_enabled {
        policy.daily_budget_json.as_str()
    } else {
        global
            .map(|g| g.daily_budget_json.as_str())
            .unwrap_or(crate::time_rules::UNLIMITED_BUDGET_JSON)
    };
    let daily_budget_minutes =
        crate::time_rules::parse_budget(budget_json).map_err(PolicyError::CorruptTimePolicy)?;
    let lifts = crate::time_rules::deliverable_lifts(&state.db, policy.device_id).await?;
    Ok(TimePolicy {
        rules,
        daily_budget_minutes,
        lifts,
    })
}

/// Always explicit (QA blocker 3): an unmanaged device gets `managed: false` with defaults and no
/// contacts, never a missing key - the launcher treats a missing `call_policy` after managed
/// calls as a suspect response and keeps its last rules. Read errors are 500s like the rest of
/// `build_policy`.
async fn build_call_policy(
    state: &AppState,
    policy: &DevicePolicy,
) -> Result<CallPolicy, PolicyError> {
    let default_country_code: String =
        sqlx::query_scalar("SELECT default_country_code FROM call_settings WHERE id = 1")
            .fetch_one(&state.db)
            .await?;
    if !policy.calls_managed {
        return Ok(CallPolicy {
            managed: false,
            calls_enabled: true,
            sms_enabled: true,
            default_country_code,
            contacts: Vec::new(),
        });
    }

    let contacts = device_contacts(state, policy.device_id)
        .await?
        .into_iter()
        .map(|c| PolicyContact {
            id: c.contact_id,
            name: c.name,
            number: c.phone_number,
            inbound: c.allow_inbound,
            outbound: c.allow_outbound,
            show_on_home: c.show_on_home,
            message_app: c
                .message_app
                .unwrap_or_else(|| policy.default_message_app.clone()),
            message_address: c.message_address,
            photo: c.photo_hash,
        })
        .collect();

    Ok(CallPolicy {
        managed: true,
        calls_enabled: policy.calls_enabled,
        sms_enabled: policy.sms_enabled,
        default_country_code,
        contacts,
    })
}

/// The contacts attached to a device, in the order the parent sees them.
pub(crate) async fn device_contacts(
    state: &AppState,
    device_id: i64,
) -> Result<Vec<DeviceContactRow>, sqlx::Error> {
    sqlx::query_as::<_, DeviceContactRow>(
        "SELECT c.id AS contact_id, c.name, c.phone_number, dc.allow_inbound, dc.allow_outbound, \
         dc.show_on_home, dc.message_app, dc.message_address, c.photo_hash \
         FROM device_contacts dc JOIN contacts c ON c.id = dc.contact_id \
         WHERE dc.device_id = ? ORDER BY dc.sort_order, c.name",
    )
    .bind(device_id)
    .fetch_all(&state.db)
    .await
}

/// Whether `device_id` plays Spotify (design 26c §2.5, §6.2): bound to an account. The policy's
/// `music.spotify` (`music::policy_music`, from the same column), the blocklist's drop of
/// Spotify's hosts and the version below follow it.
async fn plays_spotify(state: &AppState, device_id: i64) -> Result<bool, sqlx::Error> {
    Ok(sqlx::query_scalar::<_, bool>(
        "SELECT music_spotify_account IS NOT NULL FROM device_policy WHERE device_id = ?",
    )
    .bind(device_id)
    .fetch_optional(&state.db)
    .await?
    .unwrap_or(false))
}

/// Opaque per-device token combining the global compiled blocklist's content
/// hash with a summary of this device's own overrides/custom domains, so the
/// client can tell "has my effective blocklist changed since I last fetched
/// it" (see `PolicyResponse.dns_filter_version`'s doc comment) without
/// needing to compare the full ~100k+ domain list on every poll.
///
/// A phone that plays Spotify gets its list without Spotify's hosts (design 26c §6.2), so the
/// binding is hashed too (QA M7): binding and unbinding each move the version, and the launcher
/// refetches. Only a bound phone adds to the hash, so every other phone keeps its version (and its
/// list) across the upgrade that brought this in.
async fn compute_dns_filter_version(
    state: &AppState,
    device_id: i64,
) -> Result<String, sqlx::Error> {
    use sha2::{Digest, Sha256};

    let global_hash = state.dns_compiled.read().await.content_hash.clone();

    let mut overrides: Vec<(i64, bool)> = sqlx::query_as(
        "SELECT blocklist_id, enabled FROM device_blocklist_overrides WHERE device_id = ? \
         ORDER BY blocklist_id",
    )
    .bind(device_id)
    .fetch_all(&state.db)
    .await?;
    overrides.sort();

    let mut custom: Vec<(String, String)> = sqlx::query_as(
        "SELECT domain, list_type FROM dns_custom_domains WHERE device_id = ? ORDER BY domain",
    )
    .bind(device_id)
    .fetch_all(&state.db)
    .await?;
    custom.sort();

    let mut hasher = Sha256::new();
    hasher.update(global_hash.as_bytes());
    for (id, enabled) in overrides {
        hasher.update(format!("\nov:{id}:{enabled}").as_bytes());
    }
    for (domain, list_type) in custom {
        hasher.update(format!("\ncd:{list_type}:{domain}").as_bytes());
    }
    if plays_spotify(state, device_id).await? {
        hasher.update(b"\nspotify:true");
    }
    Ok(hex::encode(hasher.finalize()))
}

/// The most installed apps a status report keeps (a phone has a few hundred packages at most).
pub(crate) const MAX_INSTALLED_APPS: usize = 1500;
/// The longest app label kept.
const MAX_APP_LABEL_CHARS: usize = 100;
/// `relock_blocks_total` is kept within 0..this.
const MAX_RELOCK_BLOCKS_TOTAL: i64 = 10_000;

/// An Android package name as the platform allows it: letters, digits, `_` and `.`, at most 255.
pub(crate) fn is_package_name(name: &str) -> bool {
    !name.is_empty()
        && name.len() <= 255
        && !name.starts_with('.')
        && !name.ends_with('.')
        && name
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'.')
}

/// The installed-apps list within limits (security review M7): entries without a real package
/// name are dropped, and so are duplicates; labels are clipped, an installer that isn't a package
/// name is dropped, and at most [MAX_INSTALLED_APPS] are kept.
fn cap_installed_apps(apps: Vec<InstalledApp>) -> Vec<InstalledApp> {
    let reported = apps.len();
    let mut seen = std::collections::HashSet::new();
    let capped: Vec<InstalledApp> = apps
        .into_iter()
        .filter(|app| is_package_name(&app.package_name))
        .filter(|app| seen.insert(app.package_name.clone()))
        .take(MAX_INSTALLED_APPS)
        .map(|app| InstalledApp {
            label: crate::audit::clip(&app.label, MAX_APP_LABEL_CHARS).to_string(),
            installer: app.installer.filter(|installer| is_package_name(installer)),
            ..app
        })
        .collect();
    if capped.len() < reported {
        tracing::warn!(
            reported,
            kept = capped.len(),
            "a status report's app list was over the limits"
        );
    }
    capped
}

/// Allows on this phone every catalog app the parent checked for it whose pinned package the
/// launcher installed here (`installed` is the phone's report), and nudges it when that changed
/// the allowlist. An app installed from anywhere else, or a package that isn't the pin, never is.
pub(crate) async fn allow_installed_catalog_apps(
    state: &AppState,
    device_id: i64,
    installed: &[InstalledApp],
) {
    let by_launcher: std::collections::HashSet<&str> = installed
        .iter()
        .filter(|app| installed_by_launcher(state, app.installer.as_deref()))
        .map(|app| app.package_name.as_str())
        .collect();
    if by_launcher.is_empty() {
        return;
    }
    let checked: Vec<String> = match sqlx::query_scalar(
        "SELECT ta.package_name FROM tracked_apps ta \
         JOIN device_tracked_apps dta ON dta.tracked_app_id = ta.id \
         WHERE dta.device_id = ? AND ta.package_name != ''",
    )
    .bind(device_id)
    .fetch_all(&state.db)
    .await
    {
        Ok(checked) => checked,
        Err(err) => {
            tracing::error!(device_id, %err, "can't read the phone's catalog apps");
            return;
        }
    };
    let allowlist: Vec<String> = match sqlx::query_scalar::<_, Option<String>>(
        "SELECT allowlist_json FROM device_policy WHERE device_id = ?",
    )
    .bind(device_id)
    .fetch_optional(&state.db)
    .await
    {
        Ok(json) => json
            .flatten()
            .and_then(|json| serde_json::from_str(&json).ok())
            .unwrap_or_default(),
        Err(err) => {
            tracing::error!(device_id, %err, "can't read the phone's allowlist");
            return;
        }
    };
    let mut changed = false;
    for package in checked
        .iter()
        .filter(|package| by_launcher.contains(package.as_str()) && !allowlist.contains(package))
    {
        match crate::handlers::devices::add_to_allowlist(state, device_id, package).await {
            Ok(()) => changed = true,
            Err(err) => tracing::error!(device_id, %err, "can't allow an installed catalog app"),
        }
    }
    // The phone keeps a new app hidden and suspended until it re-fetches the policy - nudged now,
    // not at the next backstop sync up to 30 min later.
    if changed {
        let _ = state.command_notify.send(device_id);
    }
}

/// What a new phone's allowlist starts with, where it has them (security review M10): a clock, a
/// calculator, a camera and the Vibb music app. Never Settings, a browser, a file manager or a
/// store; the parent allows the rest on the phone's page.
pub(crate) const SAFE_DEFAULT_APPS: [&str; 10] = [
    "com.google.android.deskclock",
    "com.android.deskclock",
    "com.google.android.calculator",
    "com.android.calculator2",
    "com.google.android.GoogleCamera",
    "com.android.camera2",
    "app.grapheneos.camera",
    "org.lineageos.aperture",
    "me.vibb.music",
    "me.vibb.music.debug",
];

/// The phone and SMS apps a new phone keeps (QA of the security wave, Medium 3), besides the ones
/// it names by role ([call_and_text_roles]). While calls aren't managed by the launcher, the
/// launcher restricts outgoing calls unless the system dialer is allowed, and it hides every app
/// that isn't, the SMS app included: without these a new phone could only call emergency numbers
/// and couldn't text. Google's and AOSP's phone and Messages apps.
pub(crate) const CALL_AND_TEXT_APPS: [&str; 4] = [
    "com.google.android.dialer",
    "com.android.dialer",
    "com.google.android.apps.messaging",
    "com.android.messaging",
];

/// The phone's system dialer and default SMS app as its launcher reports them in `call_state`
/// (`TelecomManager.getSystemDialerPackage()`, the SMS role holder): what it needs for calls and
/// texts, whatever its maker named them. Real package names only, never Settings or Play's core.
pub(crate) fn call_and_text_roles(call_state: Option<&serde_json::Value>) -> Vec<String> {
    let Some(call_state) = call_state else {
        return Vec::new();
    };
    ["system_dialer", "default_sms_package"]
        .iter()
        .filter_map(|key| call_state.get(key)?.as_str())
        .filter(|package| {
            is_package_name(package)
                && *package != "com.android.settings"
                && !crate::play::is_play_core(package)
        })
        .map(str::to_string)
        .collect()
}

/// The launcher's application ids (the configured admin component's, and the release and debug
/// builds'): what an app it installed - a catalog app - names as its installer.
pub(crate) fn installed_by_launcher(state: &AppState, installer: Option<&str>) -> bool {
    let Some(installer) = installer else {
        return false;
    };
    let configured = state
        .config
        .launcher_admin_component
        .split('/')
        .next()
        .unwrap_or_default();
    installer == configured || ["me.vibb.launcher", "me.vibb.launcher.debug"].contains(&installer)
}

/// This device's fully-resolved effective blocklist: every feed the global
/// default has on (minus this device's overrides that turn one off), plus
/// any feed this device's override turns on even if the global default has
/// it off, plus global + device-scoped custom block domains, minus global +
/// device-scoped custom allow domains. Only fetched by the client when
/// `PolicyResponse.dns_filter_version` changes - see that field's doc
/// comment - since this can be a large (~100k+ domain) payload.
///
/// Never a smaller list than the parent set up (security review M6): a feed that can't be
/// downloaded keeps its last good domains, across restarts too (`dns_engine`); one that never
/// loaded on this server is left out - no phone got it from here (QA of the security wave, Low 8:
/// a 503 for it froze every phone's list, custom blocks included). A database error is a 500. The
/// launcher keeps its own list on any error and asks again at the next sync.
pub async fn dns_blocklist(
    State(state): State<AppState>,
    Extension(AuthedDevice(device)): Extension<AuthedDevice>,
) -> impl IntoResponse {
    match effective_blocklist(&state, device.id).await {
        Ok(categories) => Json(categories).into_response(),
        Err((status, message)) => (status, message).into_response(),
    }
}

async fn effective_blocklist(
    state: &AppState,
    device_id: i64,
) -> Result<Vec<DnsBlocklistCategory>, (StatusCode, &'static str)> {
    let db_error = |err: sqlx::Error| {
        tracing::error!("dns blocklist for device {device_id}: {err}");
        (StatusCode::INTERNAL_SERVER_ERROR, "database error")
    };
    let global_enabled: std::collections::HashMap<i64, bool> =
        sqlx::query_as::<_, (i64, bool)>("SELECT id, enabled FROM dns_blocklists")
            .fetch_all(&state.db)
            .await
            .map_err(db_error)?
            .into_iter()
            .collect();

    let overrides: std::collections::HashMap<i64, bool> = sqlx::query_as::<_, (i64, bool)>(
        "SELECT blocklist_id, enabled FROM device_blocklist_overrides WHERE device_id = ?",
    )
    .bind(device_id)
    .fetch_all(&state.db)
    .await
    .map_err(db_error)?
    .into_iter()
    .collect();

    let device_custom = sqlx::query_as::<_, crate::models::DnsCustomDomain>(
        "SELECT * FROM dns_custom_domains WHERE device_id = ?",
    )
    .bind(device_id)
    .fetch_all(&state.db)
    .await
    .map_err(db_error)?;
    let device_allow: std::collections::HashSet<String> = device_custom
        .iter()
        .filter(|d| d.list_type == "allow")
        .map(|d| d.domain.to_lowercase())
        .collect();
    let device_block: std::collections::HashSet<String> = device_custom
        .iter()
        .filter(|d| d.list_type == "block")
        .map(|d| d.domain.to_lowercase())
        .collect();

    let effective = |blocklist_id: i64| {
        overrides
            .get(&blocklist_id)
            .or_else(|| global_enabled.get(&blocklist_id))
            .copied()
            .unwrap_or(false)
    };

    // A phone that plays Spotify never gets Spotify's hosts as blocked (design 26c §6.2), whatever
    // a category or a custom block says: the parent's choice is overridden on that phone, as its
    // device page says. The launcher exempts them too, before its suffix walk.
    let spotify = plays_spotify(state, device_id).await.map_err(db_error)?;
    let exempt = |d: &str| {
        crate::play::is_fcm_protected(d) || (spotify && crate::spotify::is_spotify_host(d))
    };

    let compiled = state.dns_compiled.read().await;

    let mut categories: Vec<DnsBlocklistCategory> = compiled
        .lists
        .iter()
        .filter(|list| effective(list.blocklist_id))
        .map(|list| DnsBlocklistCategory {
            category: list.category.clone(),
            domains: list
                .domains
                .iter()
                .filter(|d| !device_allow.contains(*d) && !exempt(d))
                .cloned()
                .collect(),
        })
        .collect();

    let custom_block: Vec<String> = compiled
        .global_custom_block
        .iter()
        .chain(device_block.iter())
        .filter(|d| {
            !device_allow.contains(*d) && !compiled.global_custom_allow.contains(*d) && !exempt(d)
        })
        .cloned()
        .collect();
    if !custom_block.is_empty() {
        categories.push(DnsBlocklistCategory {
            category: "Custom".to_string(),
            domains: custom_block,
        });
    }

    Ok(categories)
}

/// Ingests a batch of blocked-domain events the device's on-device filter
/// has queued since its last successful report - see
/// `PolicyResponse.dns_filter_version`'s doc comment and
/// migrations/0011_client_side_dns_filtering.sql. Fire-and-forget per row,
/// same pattern as `status()`'s single `location` insert - a failed insert
/// here should never fail the whole batch/block the client's sync cycle.
pub async fn dns_events(
    State(state): State<AppState>,
    Extension(AuthedDevice(device)): Extension<AuthedDevice>,
    Json(events): Json<Vec<DnsEventReport>>,
) -> impl IntoResponse {
    // The log is a per-phone opt-in (off by default): an older launcher that still reports is
    // answered 204 and nothing is stored.
    let enabled: Option<bool> =
        sqlx::query_scalar("SELECT dns_log_enabled FROM device_policy WHERE device_id = ?")
            .bind(device.id)
            .fetch_optional(&state.db)
            .await
            .ok()
            .flatten();
    if enabled != Some(true) {
        return StatusCode::NO_CONTENT;
    }
    for event in events.into_iter().take(200) {
        sqlx::query(
            "INSERT INTO device_dns_events (device_id, domain, category, blocked_at) \
             VALUES (?, ?, ?, ?)",
        )
        .bind(device.id)
        .bind(&event.domain)
        .bind(&event.category)
        .bind(&event.blocked_at)
        .execute(&state.db)
        .await
        .ok();
    }

    StatusCode::NO_CONTENT
}

pub async fn status(
    State(state): State<AppState>,
    Extension(AuthedDevice(device)): Extension<AuthedDevice>,
    Json(mut report): Json<StatusReportRequest>,
) -> impl IntoResponse {
    // Security review M7: what a phone (or anything holding its token) sends is stored only within
    // limits - at most MAX_INSTALLED_APPS apps with real package names, short labels, and short
    // free-text fields. Everything below works on the capped report.
    report.installed_apps = report.installed_apps.take().map(cap_installed_apps);
    report.lock_reason = crate::audit::clip(&report.lock_reason, 32).to_string();
    report.app_version = report
        .app_version
        .as_deref()
        .map(|version| crate::audit::clip(version, 64).to_string());
    let installed_apps_json = report
        .installed_apps
        .as_ref()
        .and_then(|apps| serde_json::to_string(apps).ok());

    // Stored as-is apart from a length cap; the device page only compares it against "ok".
    let policy_state = report
        .policy_state
        .as_deref()
        .map(|state| state.chars().take(64).collect::<String>());

    // Opaque JSON, capped so a misbehaving launcher can't grow the status log without bound.
    let capabilities_json = Some(&report.capabilities)
        .filter(|caps| !caps.is_empty())
        .and_then(|caps| serde_json::to_string(caps).ok())
        .filter(|json| json.len() <= 4096);
    let time_state_json = report
        .time_state
        .as_ref()
        .filter(|state| state.is_object())
        .map(|state| state.to_string())
        .filter(|json| json.len() <= 4096);
    let call_state_json = report
        .call_state
        .as_ref()
        .filter(|state| state.is_object())
        .map(|state| state.to_string())
        .filter(|json| json.len() <= 4096);

    let install_mode_until_ms = report.install_mode.map(|m| m.until_ms);
    // Handy's lock (step 10): what the phone says about it - only the known fields are kept, so
    // no unlock times or PIN material can be stored whatever a launcher sends.
    let lock_state_json = report.lock_state.as_ref().and_then(|state| {
        crate::kid_lock::sanitize_lock_state(state, chrono::Utc::now().timestamp_millis())
    });
    // Kiosk escapes (step 11): re-serialized through the known fields and capped - package and
    // channel ids with counts only, never notification text.
    let update_fence_json = report
        .update_fence
        .as_ref()
        .and_then(crate::kiosk_escapes::sanitize_update_fence);
    let notification_cancels_json = report
        .notification_cancels
        .as_ref()
        .and_then(crate::kiosk_escapes::sanitize_notification_cancels);
    // The boot cover (design 16b): known fields only.
    let boot_cover_json = report
        .boot_cover
        .as_ref()
        .and_then(crate::kiosk_escapes::sanitize_boot_cover);
    // Sound mode (design 18): known values only.
    let ringer_mode = crate::sound_mode::sanitize_ringer_mode(report.ringer_mode.as_deref());
    let interruption_filter =
        crate::sound_mode::sanitize_interruption_filter(report.interruption_filter.as_deref());
    // Catalog downloads (design 13): known fields only, capped.
    let app_downloads_json = report
        .app_downloads
        .as_ref()
        .and_then(crate::app_downloads::sanitize);
    // Vibb music (design 21): known fields only - never positions or what is playing.
    let music_state_json = report
        .music_state
        .as_ref()
        .and_then(crate::music::sanitize_music_state);

    // Design 24b: the last relock, known fields only; the previous one for the security log.
    let relock_json = report
        .relock
        .as_ref()
        .and_then(crate::window_relock::sanitize_relock);
    let previous_relock_at =
        crate::handlers::window_relock::previous_relock_at(&state.db, device.id).await;
    // The fix round (QA High 1 (b)): an app that could read the parent PIN, and the previous one.
    let foreign_access_json = report
        .foreign_access
        .as_ref()
        .and_then(crate::window_relock::sanitize_foreign_access);
    // Every app blocked now, an install-time block too (fix of 2026-10-12), and the previous list.
    let relock_blocks_json = report
        .relock_blocks
        .as_ref()
        .and_then(crate::window_relock::sanitize_relock_blocks);
    let previous_blocks =
        crate::handlers::window_relock::previous_relock_blocks(&state.db, device.id).await;
    // Design 24b §3: boots the launcher didn't see, and the previous count for the security log.
    let unseen_boots = report
        .unseen_boots
        .as_ref()
        .and_then(crate::boot_witness::sanitize);
    // Design 36 §4.2: which of the parent's DNS servers the phone uses, known words only.
    let dns_upstream_json = report
        .dns_upstream
        .as_ref()
        .and_then(crate::dns_server::sanitize_dns_upstream);
    // Design 64 step 2: the phone's zone watch, known keys only.
    let zone_watch_json = report
        .zone_watch
        .as_ref()
        .and_then(crate::zones::sanitize_zone_watch);

    // The previous report, in one read (qa-35-s1-code L1: the foreign apps' dedup key and their
    // comparison come from the same row, so two parallel reports agree): for the security log
    // below (install mode started, new apps, the foreign apps, unseen boots) and the parent's
    // events (design 35 §2.1: its emergency call, its id for foreign access).
    let previous: Option<PreviousReport> = sqlx::query_as(
        "SELECT id, installed_apps_json, install_mode_until_ms, call_state_json, \
                foreign_access_json, unseen_boots_count, app_version, app_version_code, \
                music_state_json FROM device_status \
         WHERE device_id = ? ORDER BY reported_at DESC, id DESC LIMIT 1",
    )
    .bind(device.id)
    .fetch_optional(&state.db)
    .await
    .unwrap_or_else(|err| {
        tracing::error!(device_id = device.id, %err, "can't read the previous status");
        None
    });

    sqlx::query(
        "INSERT INTO device_status \
         (device_id, lock_reason, kiosk_engaged, installed_apps_json, app_version, app_version_code, \
          offline_override_used, policy_state, restrictions_paused, capabilities_json, \
          call_state_json, notification_listener_enabled, time_state_json, \
          install_mode_until_ms, play_window_active, play_store_suspendable, lock_state_json, \
          screen_timeout_seconds, ringer_mode, interruption_filter, update_fence_json, \
          notification_cancels_json, \
          backup_service_enabled, app_downloads_json, boot_cover_json, music_state_json, \
          battery_optimization_exempt, relock_json, unseen_boots_count, unseen_boots_last_at_ms, \
          foreign_access_json, remote_wipe_ready, admin_policies_pending_json, relock_blocks_json, \
          relock_blocks_total, clock_format_applied, battery_level, battery_charging, dns_upstream_json, \
          write_settings_granted, zone_watch_json) \
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
    )
    .bind(device.id)
    .bind(&report.lock_reason)
    .bind(report.kiosk_engaged)
    .bind(&installed_apps_json)
    .bind(&report.app_version)
    .bind(report.app_version_code)
    .bind(report.offline_override_used)
    .bind(&policy_state)
    .bind(report.restrictions_paused)
    .bind(&capabilities_json)
    .bind(&call_state_json)
    .bind(report.notification_listener_enabled)
    .bind(&time_state_json)
    .bind(install_mode_until_ms)
    .bind(report.play_window_active)
    .bind(report.play_store_suspendable)
    .bind(&lock_state_json)
    // Only a plausible value (1 s .. 1 day) is kept; anything else is dropped, not stored.
    .bind(
        report
            .screen_timeout_seconds
            .filter(|s| (1..=86_400).contains(s)),
    )
    .bind(ringer_mode)
    .bind(interruption_filter)
    .bind(&update_fence_json)
    .bind(&notification_cancels_json)
    .bind(report.backup_service_enabled)
    .bind(&app_downloads_json)
    .bind(&boot_cover_json)
    .bind(&music_state_json)
    .bind(report.battery_optimization_exempt)
    .bind(&relock_json)
    .bind(unseen_boots.map(|(count, _)| count))
    .bind(unseen_boots.and_then(|(_, at)| at))
    .bind(&foreign_access_json)
    .bind(report.remote_wipe_ready)
    .bind(crate::models::admin_policies_json(
        report.admin_policies_pending.as_ref(),
    ))
    .bind(&relock_blocks_json)
    // How many there are in all - the list stops at BLOCKS_MAX (QA of cmd-expiry, Low 2); only
    // with the list.
    .bind(relock_blocks_json.as_ref().and_then(|_| {
        report
            .relock_blocks_total
            .map(|n| n.clamp(0, MAX_RELOCK_BLOCKS_TOTAL))
    }))
    .bind(report.clock_format_applied)
    .bind(report.battery.map(|b| b.level))
    .bind(report.battery.map(|b| b.charging))
    .bind(&dns_upstream_json)
    .bind(report.write_settings_granted)
    .bind(&zone_watch_json)
    .execute(&state.db)
    .await
    .ok();
    // Design 59 §2.1: the PIN the phone last confirmed is forgotten at the current rev, and a phone
    // in android mode that needs the Child PIN gets it as a command (nudged at once).
    if let Some(lock_state) = crate::kid_lock::parse_lock_state(lock_state_json.as_deref()) {
        let capable = report
            .capabilities
            .iter()
            .any(|c| c == crate::kid_lock::ANDROID_KEYGUARD_CAPABILITY);
        match crate::kid_lock::after_lock_report(&state.db, device.id, &lock_state, capable).await {
            Ok(true) => {
                let _ = state.command_notify.send(device.id);
            }
            Ok(false) => {}
            Err(err) => {
                tracing::error!(device_id = device.id, %err, "can't check the Child PIN's command")
            }
        }
    }
    // Design 35 §2: the parent's events from this report, each raised once (its dedup key).
    let phone = crate::parent_events::Phone::load(&state.db, device.id).await;
    let previous_status_id = previous.as_ref().map_or(0, |p| p.id);
    let previous_unseen = previous.as_ref().and_then(|p| p.unseen_boots_count);
    let previous_foreign = previous
        .as_ref()
        .and_then(|p| p.foreign_access_json.clone());
    if let Some(phone) = &phone {
        // App update notices (2026-10-25): the launcher's or the music app's new release
        // installed, against the previous report (none: a new phone, nothing).
        if let Some(p) = &previous {
            crate::handlers::tracked_apps::note_app_updates(
                &state,
                phone,
                &installed_codes(
                    p.app_version.as_deref(),
                    p.app_version_code,
                    p.music_state_json.as_deref(),
                ),
                &installed_codes(
                    report.app_version.as_deref(),
                    report.app_version_code,
                    music_state_json.as_deref(),
                ),
            )
            .await;
        }
        let previous_call = previous
            .as_ref()
            .and_then(|p| emergency_call_at(p.call_state_json.as_deref()));
        if let Some(at) = crate::parent_events::new_emergency_call(
            emergency_call_at(call_state_json.as_deref()),
            previous_call,
            phone.now,
        ) {
            crate::parent_events::raise_logged(&state, &phone.emergency_call(at)).await;
        }
        if let Some(battery) = &report.battery {
            crate::parent_events::battery_report(&state, phone, battery).await;
        }
    }
    if let Some(line) = crate::boot_witness::log_line(
        device.id,
        previous_unseen,
        unseen_boots.map(|(count, _)| count),
    ) {
        crate::security::record_security_event(&state.db, "boot_unseen", None, None, Some(&line))
            .await;
        if let (Some(phone), Some((count, last_at_ms))) = (&phone, unseen_boots) {
            crate::parent_events::raise_logged(&state, &phone.unseen_boot(count, last_at_ms)).await;
        }
    }
    // Design 64 step 2: a release build ignores a mock fix and counts it (decision 14); the line
    // comes once per rise, against the count last logged (Q-9), written with the counter in one
    // transaction (`zones::mock_rise`).
    if let Err(err) = crate::zones::mock_rise(
        &state.db,
        device.id,
        crate::zones::parse_zone_watch(zone_watch_json.as_deref()).as_ref(),
    )
    .await
    {
        tracing::error!(device_id = device.id, %err, "can't compare the mock count");
    }
    crate::handlers::window_relock::log_relock(
        &state.db,
        device.id,
        previous_relock_at,
        relock_json.as_deref(),
    )
    .await;
    crate::handlers::window_relock::log_foreign_access(
        &state,
        device.id,
        phone.as_ref().map(|phone| (phone, previous_status_id)),
        previous_foreign.as_deref(),
        foreign_access_json.as_deref(),
        installed_apps_json.as_deref(),
    )
    .await;
    crate::handlers::window_relock::log_relock_blocks(
        &state.db,
        device.id,
        previous_blocks.as_deref(),
        relock_blocks_json.as_deref(),
    )
    .await;

    // A phone's failing downloads and streams flag their items for a re-resolve (design 21b §2.5).
    if let Some(report) = crate::music::parse_music_state(music_state_json.as_deref())
        && !report.item_errors.is_empty()
    {
        match crate::music_sweep::flag_reports(
            &state.db,
            device.id,
            &report,
            state.music_sweep.now(),
        )
        .await
        {
            Ok(0) => {}
            Ok(_) => state.music_sweep.wake(),
            Err(err) => tracing::warn!(device_id = device.id, %err, "can't flag music items"),
        }
    }

    log_play_events(
        &state,
        device.id,
        &report,
        previous.map(|p| (p.installed_apps_json, p.install_mode_until_ms)),
    )
    .await;

    sqlx::query("UPDATE devices SET last_seen_at = datetime('now') WHERE id = ?")
        .bind(device.id)
        .execute(&state.db)
        .await
        .ok();

    // Self-cleans device_pending_uninstalls - once a real installed-apps report no longer lists a
    // package that was queued for uninstall, it's confirmed gone, so there's nothing left to keep
    // asking the device to do. See migrations/0014_device_pending_uninstalls.sql's doc comment for
    // why this table exists instead of reusing the device_commands queue.
    if let Some(installed) = &report.installed_apps {
        let installed_names: std::collections::HashSet<&str> =
            installed.iter().map(|a| a.package_name.as_str()).collect();
        let pending: Vec<String> = sqlx::query_scalar(
            "SELECT package_name FROM device_pending_uninstalls WHERE device_id = ?",
        )
        .bind(device.id)
        .fetch_all(&state.db)
        .await
        .unwrap_or_default();
        for package_name in pending {
            if !installed_names.contains(package_name.as_str()) {
                sqlx::query(
                    "DELETE FROM device_pending_uninstalls WHERE device_id = ? AND package_name = ?",
                )
                .bind(device.id)
                .bind(&package_name)
                .execute(&state.db)
                .await
                .ok();
            }
        }
    }

    // A brand-new device's allowlist starts on its first heartbeat (a NULL allowlist is an
    // unmanaged phone), with only the safe defaults it has ([SAFE_DEFAULT_APPS], security review
    // M10) and its phone and SMS apps ([CALL_AND_TEXT_APPS], its reported roles - QA of the
    // security wave, Medium 3) - never everything installed, which let in Settings, the browser and the file manager
    // (and whatever a fake phone claimed to have). "[]" is fine: the launcher pins only itself in
    // kiosk then. Only ever fires once per device - `allowlist_json` being non-null (even "[]")
    // means an admin or an earlier toggle has already taken ownership of it, so this never
    // overwrites a real, intentional selection.
    // One conditional UPDATE, not read-then-write: a read error used to count as "not set" and
    // overwrite the parent's allowlist with every installed app, and a separate read and write
    // raced `toggle_app`.
    if let Some(installed) = report
        .installed_apps
        .as_ref()
        .filter(|apps| !apps.is_empty())
    {
        let roles = call_and_text_roles(report.call_state.as_ref());
        let package_names: Vec<&str> = installed
            .iter()
            .map(|a| a.package_name.as_str())
            .filter(|p| {
                SAFE_DEFAULT_APPS.contains(p)
                    || CALL_AND_TEXT_APPS.contains(p)
                    || roles.iter().any(|role| role == p)
            })
            .collect();
        let json =
            serde_json::to_string(&package_names).expect("a list of strings always serializes");
        if let Err(err) = sqlx::query(
            "UPDATE device_policy SET allowlist_json = ?, updated_at = datetime('now') \
             WHERE device_id = ? AND allowlist_json IS NULL",
        )
        .bind(&json)
        .bind(device.id)
        .execute(&state.db)
        .await
        {
            tracing::error!(device_id = device.id, %err, "allowlist bootstrap failed");
        }
    }

    // Catalog apps checked for this phone that the launcher has installed here are allowed here
    // (QA of the security wave, Medium 2): the phone's evidence, never a release by itself. The
    // package must be the one pinned for the catalog app; a phone's report never sets it (Low 4 -
    // the app's page lets the parent confirm one when the APK didn't give it).
    if let Some(installed) = &report.installed_apps {
        allow_installed_catalog_apps(&state, device.id, installed).await;
    }

    // Attached on every regular heartbeat when the device has a location
    // reading available, not just after a `locate` command - see
    // LocationReport's doc comment. Pruned by retention::prune (the phone's
    // location_retention_days, default 7; the newest fix is kept).
    // Only a fix that is what it says (security review H2): the time is RFC 3339 (stored
    // normalised, UTC with milliseconds), the coordinates are on Earth and the accuracy isn't
    // negative. Anything else is dropped (and logged); the rest of the report is kept.
    // Design 64: stored once (the launcher re-sends its cached fix in every report), then the
    // phone's zones judged on it (`zones::intake`). Step 2: the report's `extra_locations` (a
    // crossing's fixes, a report that didn't get through) too - every fix stored first, the
    // zones judged once after them (code QA N-4).
    let (fixes, dropped) =
        crate::zones::report_fixes(&report.extra_locations, report.location.as_ref());
    if dropped > 0 {
        tracing::warn!(
            device_id = device.id,
            dropped,
            "dropped locations that aren't valid (time, coordinates or accuracy) or too many"
        );
    }
    // Q-3: a `still` report's own fix is current as of now ("Here now" counts from it).
    let resent = report
        .location
        .as_ref()
        .filter(|_| crate::zones::report_still(report.zone_watch.as_ref()))
        .and_then(|loc| crate::models::checked_location(loc).map(|at| (loc, at)));
    crate::zones::intake(&state, device.id, &fixes, resent.as_ref()).await;

    StatusCode::NO_CONTENT
}

/// The phone's report before this one, as the intake compares with it.
#[derive(sqlx::FromRow)]
struct PreviousReport {
    id: i64,
    installed_apps_json: Option<String>,
    install_mode_until_ms: Option<i64>,
    call_state_json: Option<String>,
    foreign_access_json: Option<String>,
    unseen_boots_count: Option<i64>,
    app_version: Option<String>,
    app_version_code: Option<i64>,
    music_state_json: Option<String>,
}

/// The launcher's and the music app's versionCodes a stored report names (app update notices).
fn installed_codes(
    app_version: Option<&str>,
    app_version_code: Option<i64>,
    music_state_json: Option<&str>,
) -> crate::handlers::tracked_apps::InstalledCodes {
    crate::handlers::tracked_apps::InstalledCodes {
        launcher: app_version_code,
        launcher_name: app_version.map(str::to_string),
        music: crate::music::parse_music_state(music_state_json).and_then(|m| m.version_code),
    }
}

/// A stored `call_state`'s last emergency call (`last_emergency_call_at`, RFC 3339, read as the
/// Calls page reads it).
fn emergency_call_at(call_state_json: Option<&str>) -> Option<chrono::DateTime<chrono::Utc>> {
    let state: serde_json::Value = serde_json::from_str(call_state_json?).ok()?;
    crate::handlers::calls::parse_time(state.get("last_emergency_call_at")?.as_str()?)
}

/// Security-log lines from a status report compared with the previous one: Play install mode
/// started (a new `install_mode.until_ms`) and newly installed apps (with their installer). Only
/// when there is a previous report, so enrollment doesn't log every preinstalled app.
async fn log_play_events(
    state: &AppState,
    device_id: i64,
    report: &StatusReportRequest,
    previous: Option<(Option<String>, Option<i64>)>,
) {
    let Some((previous_apps, previous_until)) = previous else {
        return;
    };
    if let Some(mode) = report.install_mode
        && previous_until != Some(mode.until_ms)
    {
        let until = chrono::DateTime::from_timestamp_millis(mode.until_ms)
            .map(|t| t.format("%Y-%m-%d %H:%M UTC").to_string())
            .unwrap_or_else(|| mode.until_ms.to_string());
        crate::security::record_security_event(
            &state.db,
            "play_install_mode",
            None,
            None,
            Some(&format!(
                "device {device_id}: Play install mode until {until}"
            )),
        )
        .await;
    }
    let (Some(previous_apps), Some(installed)) = (previous_apps, report.installed_apps.as_ref())
    else {
        return;
    };
    let before: std::collections::HashSet<String> =
        serde_json::from_str::<Vec<InstalledApp>>(&previous_apps)
            .unwrap_or_default()
            .into_iter()
            .map(|a| a.package_name)
            .collect();
    if before.is_empty() {
        return;
    }
    for app in installed
        .iter()
        .filter(|a| !before.contains(&a.package_name))
        .take(20)
    {
        let source = app.installer.as_deref().unwrap_or("unknown source");
        crate::security::record_security_event(
            &state.db,
            "app_installed",
            None,
            None,
            Some(&format!(
                "device {device_id}: {} ({}) installed from {source}",
                app.label.chars().take(80).collect::<String>(),
                app.package_name.chars().take(200).collect::<String>()
            )),
        )
        .await;
    }
}

/// At an enrollment: the device's commands not yet delivered are cancelled, and the delivered ones
/// its phone never confirmed are used up (`deliveries` at the maximum), so none of them reaches the
/// newly enrolled phone (QA of the launcher wave, Low 2). Returns how many; a DB error is logged.
async fn end_pending_commands(db: &sqlx::SqlitePool, device_id: i64) -> u64 {
    // Cancelled, not deleted: the Locate page keeps them as history ("a new phone was enrolled").
    // Every one, expired ones too - expiry is read against the clock, which may be behind after a
    // power cut (QA of cmd-expiry, Low 4); the page still says "Expired" for one that was.
    let cancelled = sqlx::query(
        "UPDATE device_commands SET cancelled_at = datetime('now'), cancel_reason = 'enrolled' \
         WHERE device_id = ? AND delivered_at IS NULL AND cancelled_at IS NULL",
    )
    .bind(device_id)
    .execute(db)
    .await;
    let used_up = sqlx::query(
        "UPDATE device_commands SET deliveries = ? WHERE device_id = ? AND delivered_at IS NOT NULL \
         AND received_at IS NULL AND acknowledged_at IS NULL AND deliveries < ?",
    )
    .bind(crate::models::MAX_COMMAND_DELIVERIES)
    .bind(device_id)
    .bind(crate::models::MAX_COMMAND_DELIVERIES)
    .execute(db)
    .await;
    let mut count = 0;
    for outcome in [cancelled, used_up] {
        match outcome {
            Ok(done) => count += done.rows_affected(),
            Err(err) => tracing::error!(device_id, %err, "can't end the device's pending commands"),
        }
    }
    count
}

/// At server start: every ring, pause or location update still waiting is cancelled (`restart`),
/// whatever its age by the clock now - the clock may be behind after a power cut (no RTC), and the
/// hour couldn't be trusted (QA of cmd-expiry, Low 4). A wipe or a lock keeps waiting. Logged on
/// error, never fails the start.
pub(crate) async fn end_expiring_at_start(db: &sqlx::SqlitePool) {
    let ended = sqlx::query(
        "UPDATE device_commands SET cancelled_at = datetime('now'), cancel_reason = 'restart' \
         WHERE delivered_at IS NULL AND cancelled_at IS NULL AND command IN (?, ?, ?, ?)",
    )
    .bind(crate::models::EXPIRING_COMMANDS[0])
    .bind(crate::models::EXPIRING_COMMANDS[1])
    .bind(crate::models::EXPIRING_COMMANDS[2])
    .bind(crate::models::EXPIRING_COMMANDS[3])
    .execute(db)
    .await;
    match ended {
        Ok(done) if done.rows_affected() > 0 => tracing::info!(
            count = done.rows_affected(),
            "waiting rings, pauses and location updates ended at start"
        ),
        Ok(_) => {}
        Err(err) => tracing::error!(%err, "can't end the waiting expiring commands at start"),
    }
}

/// After a command went to a phone that confirms commands: once the re-delivery gap has passed,
/// nudge the phone over its stream if it still hasn't confirmed, so a lost answer is made good
/// within [crate::models::REDELIVER_WINDOW_MINUTES] rather than at the phone's 30-minute backstop
/// (QA of the launcher wave, High 1). One nudge per delivery; nothing happens without a stream.
fn spawn_redelivery_nudge(state: &AppState, device_id: i64, command_id: i64) {
    let db = state.db.clone();
    let notify = state.command_notify.clone();
    tokio::spawn(async move {
        tokio::time::sleep(Duration::from_secs(
            crate::models::REDELIVER_AFTER_MINUTES as u64 * 60 + 5,
        ))
        .await;
        redelivery_nudge(&db, &notify, device_id, command_id).await;
    });
}

/// The nudge itself ([spawn_redelivery_nudge]): sent when the command is still unconfirmed and may
/// be offered again. Returns whether it nudged.
pub(crate) async fn redelivery_nudge(
    db: &sqlx::SqlitePool,
    notify: &tokio::sync::broadcast::Sender<i64>,
    device_id: i64,
    command_id: i64,
) -> bool {
    let pending: Result<Option<i64>, sqlx::Error> = sqlx::query_scalar(
        "SELECT id FROM device_commands WHERE id = ? AND device_id = ? AND received_at IS NULL \
         AND acknowledged_at IS NULL AND cancelled_at IS NULL AND deliveries < ? \
         AND first_delivered_at > datetime('now', ?)",
    )
    .bind(command_id)
    .bind(device_id)
    .bind(crate::models::MAX_COMMAND_DELIVERIES)
    .bind(format!(
        "-{} minutes",
        crate::models::REDELIVER_WINDOW_MINUTES
    ))
    .fetch_optional(db)
    .await;
    match pending {
        Ok(Some(_)) => {
            let _ = notify.send(device_id);
            true
        }
        Ok(None) => false,
        Err(err) => {
            tracing::warn!(device_id, command_id, %err, "can't check a command for re-delivery");
            false
        }
    }
}

/// Whether this device's launcher confirms commands before running them (its latest status
/// report has [crate::models::COMMAND_RECEIPT_CAPABILITY], security review L12).
pub(crate) async fn sends_command_receipts(
    db: &sqlx::SqlitePool,
    device_id: i64,
) -> Result<bool, sqlx::Error> {
    Ok(last_capabilities(db, device_id)
        .await?
        .iter()
        .any(|c| c == crate::models::COMMAND_RECEIPT_CAPABILITY))
}

/// The capabilities of this device's latest status report since its enrollment (none without one,
/// or unreadable): a phone enrolled into the row anew is not the install that reported before it
/// (design 59 S59-3).
pub(crate) async fn last_capabilities(
    db: &sqlx::SqlitePool,
    device_id: i64,
) -> Result<Vec<String>, sqlx::Error> {
    let capabilities: Option<Option<String>> = sqlx::query_scalar(&format!(
        "SELECT capabilities_json FROM device_status WHERE device_id = ?1 AND reported_at >= {} \
         ORDER BY reported_at DESC, id DESC LIMIT 1",
        crate::models::ENROLLED_AT
    ))
    .bind(device_id)
    .fetch_optional(db)
    .await?;
    Ok(capabilities
        .flatten()
        .and_then(|json| serde_json::from_str(&json).ok())
        .unwrap_or_default())
}

/// The phone got a command and is about to run it (security review L12) - sent before it runs,
/// so a wipe is confirmed too. Scoped to this device's own commands - and, when the phone sends
/// the command's `requested_at`, to that command, not a reuse of its id after a restore (QA
/// Medium 1); the first receipt counts.
pub async fn command_receipt(
    State(state): State<AppState>,
    Extension(AuthedDevice(device)): Extension<AuthedDevice>,
    Json(req): Json<CommandReceiptRequest>,
) -> impl IntoResponse {
    let stored = sqlx::query(
        "UPDATE device_commands SET received_at = COALESCE(received_at, datetime('now')) \
         WHERE id = ? AND device_id = ? AND (? IS NULL OR requested_at = ?)",
    )
    .bind(req.command_id)
    .bind(device.id)
    .bind(&req.requested_at)
    .bind(&req.requested_at)
    .execute(&state.db)
    .await;
    match stored {
        Ok(_) => StatusCode::NO_CONTENT,
        Err(err) => {
            tracing::error!(device_id = device.id, %err, "can't store a command receipt");
            StatusCode::INTERNAL_SERVER_ERROR
        }
    }
}

/// The device reports back whether a delivered command actually succeeded (a wipe only when it
/// was refused - a wiped phone can't). A result implies the receipt. Scoped to this device's own
/// commands only, so one device can't ack another's queue entry.
pub async fn command_result(
    State(state): State<AppState>,
    Extension(AuthedDevice(device)): Extension<AuthedDevice>,
    Json(req): Json<CommandResultRequest>,
) -> impl IntoResponse {
    let result = if req.success {
        req.message.unwrap_or_else(|| "ok".to_string())
    } else {
        req.message.unwrap_or_else(|| "failed".to_string())
    };
    let stored = sqlx::query(
        "UPDATE device_commands SET acknowledged_at = datetime('now'), result = ?, succeeded = ?, \
         received_at = COALESCE(received_at, datetime('now')) \
         WHERE id = ? AND device_id = ? AND (? IS NULL OR requested_at = ?)",
    )
    .bind(result.chars().take(500).collect::<String>())
    .bind(req.success)
    .bind(req.command_id)
    .bind(device.id)
    .bind(&req.requested_at)
    .bind(&req.requested_at)
    .execute(&state.db)
    .await;
    match stored {
        Ok(_) => StatusCode::NO_CONTENT,
        Err(err) => {
            tracing::error!(device_id = device.id, %err, "can't store a command result");
            StatusCode::INTERNAL_SERVER_ERROR
        }
    }
}

/// Upserts this device's current download/install status for one tracked app - purely transient,
/// driving the unified Apps list's "Installing NN%"/"Install failed" status label (see
/// `handlers::devices::view_device`), not permanent history. The device calls this on its own
/// throttled schedule during a download (see kids-launcher-mdm's `checkForTrackedAppUpdates`), and
/// once more on a failure (from either that function or `AppInstallReceiver`) so a failed install
/// is visible here instead of silently vanishing - previously a failure only ever showed up as a
/// client-local, permanently-sticky "don't retry" marker with nothing surfaced server-side at all,
/// which looked to the admin exactly like the request never left the device. Best-effort,
/// fire-and-forget from the client's side either way - a dropped report just means one
/// stale-looking status until the next one lands or the row goes stale entirely (see the staleness
/// window in `view_device`).
pub async fn install_progress(
    State(state): State<AppState>,
    Extension(AuthedDevice(device)): Extension<AuthedDevice>,
    Json(report): Json<InstallProgressReport>,
) -> impl IntoResponse {
    sqlx::query(
        "INSERT INTO device_install_progress (device_id, tracked_app_id, percent, failed, updated_at) \
         VALUES (?, ?, ?, ?, datetime('now')) \
         ON CONFLICT(device_id, tracked_app_id) DO UPDATE SET \
         percent = excluded.percent, failed = excluded.failed, updated_at = excluded.updated_at",
    )
    .bind(device.id)
    .bind(report.tracked_app_id)
    .bind(report.percent)
    .bind(report.failed)
    .execute(&state.db)
    .await
    .ok();

    StatusCode::NO_CONTENT
}

/// Held open by the launcher's anchor service (`CommandListenerService`) - since design 19 the
/// only way this server nudges a phone; the phone's own backstop sync (every 30 min) covers
/// whatever a dropped stream missed. Every event is a content-free "something changed, go check"
/// nudge, not the command payload itself - the client always re-fetches `GET /api/devices/policy`
/// to get the real `pending_command`, reusing the exact same dispatch path as a normal scheduled
/// sync. `KeepAlive` comments (every `SSE_KEEPALIVE_SECS`) let the client detect a silently-dead
/// connection and reconnect; a proxy in front of this server needs its idle/read timeout for the
/// response above it (Caddy 2.11+: `write_idle`, 1 minute by default - DEPLOY.md, "With your own
/// Caddy"; found 2026-10-20: every stream reset 60 s after each keepalive).
/// While the response lives, `AppState.command_streams` counts it (the device page's "Instant
/// changes" line).
pub async fn commands_stream(
    State(state): State<AppState>,
    Extension(AuthedDevice(device)): Extension<AuthedDevice>,
) -> Sse<impl tokio_stream::Stream<Item = Result<Event, Infallible>>> {
    let device_id = device.id;
    let rx = state.command_notify.subscribe();
    // Moved into the stream: dropped (stream counted closed) when the connection ends.
    let guard = state.command_streams.open(device_id);
    tracing::info!(device = device_id, "command stream opened");
    // The stream ends when its close signal turns true (shutdown, design 22 §2).
    let close = WatchStream::new(guard.closed()).filter_map(|closed| closed.then_some(None));
    // A lagged receiver lost ids - possibly this device's - so it nudges (QA 07 #16): one extra
    // sync is harmless, a lost ring isn't.
    let nudges = BroadcastStream::new(rx).filter_map(move |msg| match msg {
        Ok(id) if id == device_id => Some(Some(Event::default().data("command"))),
        Err(tokio_stream::wrappers::errors::BroadcastStreamRecvError::Lagged(_)) => {
            Some(Some(Event::default().data("command")))
        }
        _ => None,
    });
    let stream = nudges
        .merge(close)
        .take_while(Option::is_some)
        .filter_map(move |event| {
            let _open: &crate::streams::StreamGuard = &guard;
            event.map(Ok)
        });
    Sse::new(stream)
        .keep_alive(KeepAlive::new().interval(Duration::from_secs(state.config.sse_keepalive_secs)))
}

/// The tracked apps this device may get: enabled, with a cached release, and either the launcher
/// itself (`is_launcher`, always included - see migrations/0013's doc comment) or selected for this
/// device (`device_tracked_apps`). One query for the list and the download (design 13 QA #4), so a
/// device token can only fetch what its own list advertises; `only` narrows it to one app.
async fn scoped_apps(
    state: &AppState,
    device_id: i64,
    only: Option<i64>,
) -> Result<Vec<TrackedApp>, sqlx::Error> {
    sqlx::query_as::<_, TrackedApp>(
        "SELECT ta.* FROM tracked_apps ta \
         WHERE ta.enabled = 1 AND ta.latest_release_tag IS NOT NULL \
         AND (ta.is_launcher = 1 OR EXISTS ( \
             SELECT 1 FROM device_tracked_apps dta \
             WHERE dta.device_id = ? AND dta.tracked_app_id = ta.id)) \
         AND (? IS NULL OR ta.id = ?)",
    )
    .bind(device_id)
    .bind(only)
    .bind(only)
    .fetch_all(&state.db)
    .await
}

/// Nudges every enrolled phone whose app list carries catalog app `app_id` - [scoped_apps]' own
/// rule, so the nudge and the list never disagree - after a new release of it was stored (a sync
/// or an upload): the phone fetches the list in that sync instead of at its 30-minute backstop;
/// its Wi-Fi and night-window gates are its own. A DB error is logged: the backstop still comes.
pub(crate) async fn nudge_phones_with_app(state: &AppState, app_id: i64) {
    let devices: Vec<i64> =
        match sqlx::query_scalar("SELECT id FROM devices WHERE token_hash IS NOT NULL")
            .fetch_all(&state.db)
            .await
        {
            Ok(devices) => devices,
            Err(err) => {
                tracing::warn!(app_id, %err, "couldn't read the phones to nudge for a new release");
                return;
            }
        };
    for device in devices {
        match scoped_apps(state, device, Some(app_id)).await {
            Ok(apps) if !apps.is_empty() => {
                let _ = state.command_notify.send(device);
            }
            Ok(_) => {}
            Err(err) => {
                tracing::warn!(app_id, device, %err, "couldn't check a phone's apps for a new release")
            }
        }
    }
}

/// `release_tag` as the device sees it: composited with the asset id when one's cached
/// (GitHub-sourced apps) - see `handlers::tracked_apps::sync_one_app`'s doc comment for why a
/// rolling tag alone can't be trusted to signal "this is a new build" client-side.
pub(crate) fn device_release_tag(app: &TrackedApp) -> Option<String> {
    let tag = app.latest_release_tag.as_ref()?;
    Some(match app.latest_release_asset_id {
        Some(asset_id) => format!("{tag}@{asset_id}"),
        None => tag.clone(),
    })
}

/// [device_release_tag] as the `X-Release-Tag` header of a download: every byte outside visible
/// ASCII, and `%` itself, percent-encoded (a manual upload's label may hold spaces or letters like
/// "ø"). The launcher encodes the tag it expects the same way and compares the strings.
pub(crate) fn release_tag_header(tag: &str) -> String {
    let mut out = String::with_capacity(tag.len());
    for byte in tag.bytes() {
        if (0x21..=0x7e).contains(&byte) && byte != b'%' {
            out.push(byte as char);
        } else {
            out.push_str(&format!("%{byte:02X}"));
        }
    }
    out
}

/// The header naming the release a download response carries (design 13 QA #2): the phone checks
/// it against the release it asked for, since a sync may have replaced the file in between.
pub const RELEASE_TAG_HEADER: &str = "x-release-tag";

/// Every tracked app this device may get ([scoped_apps]). `download_url` is computed per-row, since
/// there's one download endpoint per app id; `sha256` is the cached file's hash (`null` until the
/// server has computed it) - the phone checks the whole download against it.
pub async fn tracked_app_updates(
    State(state): State<AppState>,
    Extension(AuthedDevice(device)): Extension<AuthedDevice>,
) -> impl IntoResponse {
    let apps = scoped_apps(&state, device.id, None)
        .await
        .unwrap_or_default();

    let updates: Vec<TrackedAppUpdate> = apps
        .into_iter()
        .filter_map(|app| {
            let release_tag = device_release_tag(&app)?;
            Some(TrackedAppUpdate {
                id: app.id,
                name: app.name,
                package_name: app.package_name,
                release_tag,
                download_url: format!("/api/devices/apps/{}/download", app.id),
                is_launcher: app.is_launcher,
                sha256: app.latest_release_sha256,
                signer_sha256: app.signer_sha256,
            })
        })
        .collect();

    Json(updates).into_response()
}

/// The cached APK of a tracked app this device may get ([scoped_apps] - anything else is a 404),
/// served by tower-http's `ServeFile` (design 13 §5): streamed from disk in 64 KiB chunks, with
/// `Content-Length`, a strong `ETag` (mtime + size), a single `Range` answered with 206 and
/// `Content-Range`, `If-Match` (412 when the file changed) and 416 past the end. `X-Release-Tag`
/// names the release the file belongs to - read from the same row as its path, so the two always
/// match. A sync replacing the file meanwhile is harmless: the old one is either served whole or
/// gone (404, the phone asks for the list again).
pub async fn tracked_app_download(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    Extension(AuthedDevice(device)): Extension<AuthedDevice>,
    request: axum::extract::Request,
) -> axum::response::Response {
    let app = match scoped_apps(&state, device.id, Some(id)).await {
        Ok(apps) => apps.into_iter().next(),
        Err(err) => {
            tracing::error!(device_id = device.id, %err, "couldn't read the tracked app");
            return StatusCode::INTERNAL_SERVER_ERROR.into_response();
        }
    };
    let Some(app) = app else {
        return StatusCode::NOT_FOUND.into_response();
    };
    let (Some(file_path), Some(tag)) = (
        app.latest_release_file_path.clone(),
        device_release_tag(&app),
    ) else {
        return StatusCode::NOT_FOUND.into_response();
    };

    let response = match tower_http::services::ServeFile::new(&file_path)
        .try_call(request)
        .await
    {
        Ok(response) => response,
        Err(err) => {
            tracing::error!(device_id = device.id, app_id = id, %err, "couldn't serve the APK");
            return StatusCode::INTERNAL_SERVER_ERROR.into_response();
        }
    };
    let mut response = response.map(axum::body::Body::new);
    if response.status() == StatusCode::NOT_FOUND {
        return response;
    }
    let headers = response.headers_mut();
    // ServeFile guesses the type from the extension on the file's own responses (200, 206, 416).
    if headers.contains_key(header::CONTENT_TYPE) {
        headers.insert(
            header::CONTENT_TYPE,
            header::HeaderValue::from_static("application/vnd.android.package-archive"),
        );
    }
    if let Ok(value) = header::HeaderValue::from_str(&release_tag_header(&tag)) {
        headers.insert(RELEASE_TAG_HEADER, value);
    }
    response
}

/// A contact photo by its hash (`call_policy.contacts[].photo`). Only for a contact on the
/// requesting device - another device's contacts, an invalid hash or a missing file are all 404,
/// so a device token can't probe the address book.
pub async fn contact_photo(
    State(state): State<AppState>,
    Path(hash): Path<String>,
    Extension(AuthedDevice(device)): Extension<AuthedDevice>,
) -> impl IntoResponse {
    let Some(path) = crate::photos::path_for(&state.photo_dir, &hash) else {
        return StatusCode::NOT_FOUND.into_response();
    };
    let visible: Result<bool, sqlx::Error> = sqlx::query_scalar(
        "SELECT EXISTS(SELECT 1 FROM device_contacts dc JOIN contacts c ON c.id = dc.contact_id \
         WHERE dc.device_id = ? AND c.photo_hash = ?)",
    )
    .bind(device.id)
    .bind(&hash)
    .fetch_one(&state.db)
    .await;
    match visible {
        Ok(true) => {}
        Ok(false) => return StatusCode::NOT_FOUND.into_response(),
        Err(err) => {
            tracing::error!(device_id = device.id, %err, "contact photo lookup failed");
            return StatusCode::INTERNAL_SERVER_ERROR.into_response();
        }
    }
    match tokio::fs::read(&path).await {
        Ok(bytes) => ([(header::CONTENT_TYPE, "image/jpeg")], bytes).into_response(),
        Err(_) => StatusCode::NOT_FOUND.into_response(),
    }
}

/// A wallpaper image by its hash (`launcher_ui.wallpapers[].image`). Only for a wallpaper this
/// device may use - another device's upload, an invalid hash or a missing file are all 404, so a
/// device token can't fetch the parent's other photos (design 08, QA 08 #8).
pub async fn wallpaper_image(
    State(state): State<AppState>,
    Path(hash): Path<String>,
    Extension(AuthedDevice(device)): Extension<AuthedDevice>,
) -> impl IntoResponse {
    let Some(path) = crate::photos::path_for(&state.wallpaper_dir, &hash) else {
        return StatusCode::NOT_FOUND.into_response();
    };
    let visible: Result<bool, sqlx::Error> = sqlx::query_scalar(
        "SELECT EXISTS(SELECT 1 FROM device_wallpapers dw JOIN wallpapers w ON w.id = dw.wallpaper_id \
         WHERE dw.device_id = ? AND w.image_hash = ?)",
    )
    .bind(device.id)
    .bind(&hash)
    .fetch_one(&state.db)
    .await;
    match visible {
        Ok(true) => {}
        Ok(false) => return StatusCode::NOT_FOUND.into_response(),
        Err(err) => {
            tracing::error!(device_id = device.id, %err, "wallpaper lookup failed");
            return StatusCode::INTERNAL_SERVER_ERROR.into_response();
        }
    }
    match tokio::fs::read(&path).await {
        Ok(bytes) => ([(header::CONTENT_TYPE, "image/jpeg")], bytes).into_response(),
        Err(_) => StatusCode::NOT_FOUND.into_response(),
    }
}

/// `POST /api/devices/crashes` (cleanup 2026-10-06): the launcher's crash reports - hash, short
/// trace, count, times, build - checked by `crashes::sanitize` (a report in another shape is
/// skipped) and kept per phone and hash (counts add up). 204 once stored; the launcher drops what
/// it sent only then. 400 for more than `crashes::MAX_PER_BATCH`, 500 on a DB error.
pub async fn crash_reports(
    State(state): State<AppState>,
    Extension(AuthedDevice(device)): Extension<AuthedDevice>,
    Json(batch): Json<crate::crashes::CrashReportBatch>,
) -> impl IntoResponse {
    if batch.crashes.len() > crate::crashes::MAX_PER_BATCH {
        return StatusCode::BAD_REQUEST;
    }
    let Ok(mut tx) = state.db.begin().await else {
        return StatusCode::INTERNAL_SERVER_ERROR;
    };
    for crash in batch.crashes.iter().filter_map(crate::crashes::sanitize) {
        let result = sqlx::query(
            "INSERT INTO device_crashes \
             (device_id, hash, trace, count, first_at_ms, last_at_ms, app_version_code) \
             VALUES (?, ?, ?, ?, ?, ?, ?) \
             ON CONFLICT(device_id, hash) DO UPDATE SET \
                trace = excluded.trace, \
                count = MIN(device_crashes.count + excluded.count, 1000000000), \
                first_at_ms = MIN(device_crashes.first_at_ms, excluded.first_at_ms), \
                last_at_ms = MAX(device_crashes.last_at_ms, excluded.last_at_ms), \
                app_version_code = excluded.app_version_code, \
                reported_at = datetime('now')",
        )
        .bind(device.id)
        .bind(&crash.hash)
        .bind(&crash.trace)
        .bind(crash.count)
        .bind(crash.first_at_ms)
        .bind(crash.last_at_ms)
        .bind(crash.app_version_code)
        .execute(&mut *tx)
        .await;
        if let Err(err) = result {
            tracing::error!(device_id = device.id, %err, "couldn't store a crash report");
            return StatusCode::INTERNAL_SERVER_ERROR;
        }
    }
    if tx.commit().await.is_err() {
        return StatusCode::INTERNAL_SERVER_ERROR;
    }
    StatusCode::NO_CONTENT
}

/// `POST /api/devices/power` takes at most this much (design 38 §2): two small objects.
pub const POWER_BODY_LIMIT: usize = 2 * 1024;

/// `POST /api/devices/power` (design 38, capability `power_v1`): `{off?, on?}` - the phone's
/// shutdown (sent from `ACTION_SHUTDOWN`, and kept by the phone until a later boot's start is
/// accepted) and its boot's start. 204, 400 for a body that isn't one or has neither part, 413
/// above [POWER_BODY_LIMIT], 500 on a DB error (the phone keeps its record). The rows and their
/// pushes come from the pure `parent_events::power_events` in one transaction: the events by their
/// dedup keys (a resent body raises nothing new), a restart's held `phone_off` push `dropped` with
/// reason `restart`. No `device_status` row, no security log line.
pub async fn power(
    State(state): State<AppState>,
    Extension(AuthedDevice(device)): Extension<AuthedDevice>,
    body: axum::body::Bytes,
) -> StatusCode {
    let Ok(mut report) = serde_json::from_slice::<crate::models::PowerReport>(&body) else {
        return StatusCode::BAD_REQUEST;
    };
    if report.off.is_none() && report.on.is_none() {
        return StatusCode::BAD_REQUEST;
    }
    // 2026-10-25: the shutdown's last fix, as a status report's fix is checked (time, place,
    // accuracy, not ahead); one that isn't valid costs the position only, never the shutdown.
    let shutdown_fix = report.off.as_mut().and_then(|off| {
        let fix = off
            .location
            .as_ref()
            .and_then(crate::models::PowerLocation::as_report)
            .and_then(|loc| crate::models::checked_location(&loc).map(|at| (loc, at)));
        if fix.is_none() && off.location.take().is_some() {
            tracing::warn!(
                device_id = device.id,
                "dropped a shutdown's location that isn't valid"
            );
        }
        // The event's parameters carry the provider word as the trail stores it (N-3).
        if let Some(location) = off.location.as_mut() {
            location.provider = crate::models::location_provider(location.provider.as_deref());
        }
        fix
    });
    let Some(phone) = crate::parent_events::Phone::load(&state.db, device.id).await else {
        return StatusCode::INTERNAL_SERVER_ERROR;
    };
    let result = async {
        // "Last report" for a start with no shutdown seen: the newest report before the boot.
        let last = match report
            .on
            .as_ref()
            .and_then(|on| chrono::DateTime::from_timestamp_millis(on.at_ms))
        {
            Some(boot) => {
                let row: Option<(String, Option<i64>, Option<bool>)> = sqlx::query_as(
                    "SELECT reported_at, battery_level, battery_charging FROM device_status \
                     WHERE device_id = ? AND reported_at < ? \
                     ORDER BY reported_at DESC, id DESC LIMIT 1",
                )
                .bind(device.id)
                .bind(crate::push::db_time(boot))
                .fetch_optional(&state.db)
                .await?;
                row.and_then(|(at, level, charging)| {
                    Some(crate::parent_events::LastReport {
                        at: crate::display_time::parse_db_utc(&at)?,
                        level,
                        charging,
                    })
                })
            }
            None => None,
        };
        let plan = crate::parent_events::power_events(&report, last.as_ref(), &phone);
        for why in &plan.dropped {
            tracing::warn!(device_id = device.id, why, "dropped part of a power report");
        }
        let mut tx = state.db.begin().await?;
        let mut changed = false;
        for event in &plan.events {
            let raised = crate::parent_events::raise(&mut tx, event).await?;
            changed |= raised.is_some();
            // A newer shutdown supersedes an older one whose push still waits (two shutdowns
            // before a start got through, qa-38-code nit 3): the newest off is kept and told, the
            // older's push dropped - never a stray "turned off" for a boot long over.
            if let (Some(new_id), crate::parent_events::Kind::PhoneOff) = (raised, event.kind) {
                changed |= sqlx::query(
                    "UPDATE push_deliveries SET status = 'dropped', reason = 'superseded', \
                     next_try_at = NULL WHERE status = 'queued' AND event_id IN \
                     (SELECT o.id FROM parent_events o WHERE o.device_id = ?1 \
                      AND o.kind = 'phone_off' AND o.id != ?2 AND o.happened_at < \
                      (SELECT n.happened_at FROM parent_events n WHERE n.id = ?2))",
                )
                .bind(device.id)
                .bind(new_id)
                .execute(&mut *tx)
                .await?
                .rows_affected()
                    > 0;
            }
        }
        // The `off`'s still-queued pushes: dropped by a restart, else sent now - before the
        // "turned on" (only one never tried: a retry keeps its backoff).
        let off_deliveries = "event_id = (SELECT id FROM parent_events \
             WHERE device_id = ? AND kind = 'phone_off' AND dedup_key = ?) AND status = 'queued'";
        if let Some(key) = &plan.drop_held_off {
            changed |= sqlx::query(&format!(
                "UPDATE push_deliveries SET status = 'dropped', reason = 'restart', \
                 next_try_at = NULL WHERE {off_deliveries}"
            ))
            .bind(device.id)
            .bind(key)
            .execute(&mut *tx)
            .await?
            .rows_affected()
                > 0;
        }
        if let Some(key) = &plan.release_held_off {
            changed |= sqlx::query(&format!(
                "UPDATE push_deliveries SET next_try_at = NULL \
                 WHERE {off_deliveries} AND attempts = 0 AND next_try_at IS NOT NULL"
            ))
            .bind(device.id)
            .bind(key)
            .execute(&mut *tx)
            .await?
            .rows_affected()
                > 0;
        }
        tx.commit().await?;
        // The shutdown kept (not dropped as too old, ahead or after the start): its last fix is
        // one of the phone's fixes, stored once and judged by the zones as a report's, then
        // marked as the shutdown's for Locate's line.
        let off_at = plan
            .events
            .iter()
            .find(|event| event.kind == crate::parent_events::Kind::PhoneOff)
            .map(|event| event.happened_at);
        Ok::<_, sqlx::Error>((changed, off_at))
    };
    match result.await {
        Ok((changed, off_at)) => {
            if changed {
                state.push.wake();
            }
            if let (Some(off_at), Some((loc, captured_at))) = (off_at, shutdown_fix.as_ref()) {
                crate::zones::intake(&state, device.id, &[(loc, captured_at.clone())], None).await;
                if let Err(err) =
                    crate::zones::mark_shutdown(&state.db, device.id, loc, captured_at, off_at)
                        .await
                {
                    tracing::error!(device_id = device.id, %err, "can't mark the shutdown's fix");
                }
            }
            StatusCode::NO_CONTENT
        }
        Err(err) => {
            tracing::error!(device_id = device.id, %err, "couldn't store a power report");
            StatusCode::INTERNAL_SERVER_ERROR
        }
    }
}

/// `POST /api/devices/time-request` takes at most this much (design r13 §2.5): an id and an instant.
pub const TIME_REQUEST_BODY_LIMIT: usize = 1024;

/// The phone's request for more screen time (design r13 §2.5, `server/testdata/time_request.json`'s
/// `request`): its own `id` (16-64 lowercase hex, the event's key) and when the kid asked.
#[derive(Debug, serde::Deserialize)]
pub struct TimeRequestBody {
    pub id: String,
    pub asked_at_ms: i64,
}

/// The answer (`time_request.json`'s `answers`): taken, or not with why - `capped` (the day's
/// [crate::parent_events::EXTRA_TIME_REQUESTS_A_DAY] were asked already) or `stale` (asked before
/// the server zone's today).
#[derive(Debug, PartialEq, Eq, serde::Serialize)]
pub struct TimeRequestAnswer {
    pub accepted: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub reason: Option<&'static str>,
}

impl TimeRequestAnswer {
    pub const ACCEPTED: TimeRequestAnswer = TimeRequestAnswer {
        accepted: true,
        reason: None,
    };
    pub const CAPPED: TimeRequestAnswer = TimeRequestAnswer {
        accepted: false,
        reason: Some("capped"),
    };
    pub const STALE: TimeRequestAnswer = TimeRequestAnswer {
        accepted: false,
        reason: Some("stale"),
    };
}

/// The zone whose day a request for more time is counted in (qa-r13-rule-card-code Q-2): the
/// phone's own IANA zone from its report (`time_state.zone`), which its ledger - and so its own
/// count of three - counts the day in; Phone Setup's when the report names none or one that doesn't
/// read.
pub fn extra_time_day_zone(
    report: Option<&crate::time_state::TimeReport>,
    setup: &crate::display_time::Zone,
) -> crate::display_time::Zone {
    report
        .and_then(|r| r.zone.as_deref())
        .map(crate::display_time::Zone::from_setting)
        .filter(|zone| !zone.is_fallback())
        .unwrap_or(*setup)
}

/// A request id: 16-64 lowercase hex characters (the phone's 32 from `SecureRandom`).
pub fn time_request_id_ok(id: &str) -> bool {
    (16..=64).contains(&id.len())
        && id
            .bytes()
            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}

/// `POST /api/devices/time-request` (design r13 §2.5-2.6): the kid tapped "Be om mer tid" in the
/// phone's notification. 200 with [TimeRequestAnswer], 400 for a body that doesn't parse or an id
/// that isn't 16-64 lowercase hex, 413 above [TIME_REQUEST_BODY_LIMIT], 500 on a DB error (the phone
/// keeps its request and retries). "Today" is the phone's own day ([extra_time_day_zone]: the zone
/// its report names, else Phone Setup's). A request asked before it isn't stored (`stale`); a
/// future `asked_at` counts as now. In one `BEGIN IMMEDIATE` transaction: the fourth request of
/// the day (rows of today other than this id) isn't stored (`capped`, one log line);
/// else the event `extra_time_request` keyed by the id (a retry is one row, and a retried third
/// request is no fourth), pushed only while it is news (asked under 30 min ago at intake), to every
/// parent with "Asks for more time" on. Where the phone stood comes from its own latest report, when
/// that is at most 15 minutes old.
pub async fn time_request(
    State(state): State<AppState>,
    Extension(AuthedDevice(device)): Extension<AuthedDevice>,
    body: axum::body::Bytes,
) -> axum::response::Response {
    use crate::parent_events::{self, EXTRA_TIME_NEWS_MINUTES, EXTRA_TIME_REQUESTS_A_DAY, Push};
    let Ok(request) = serde_json::from_slice::<TimeRequestBody>(&body) else {
        return StatusCode::BAD_REQUEST.into_response();
    };
    if !time_request_id_ok(&request.id) {
        return StatusCode::BAD_REQUEST.into_response();
    }
    let Some(asked) = chrono::DateTime::from_timestamp_millis(request.asked_at_ms) else {
        return StatusCode::BAD_REQUEST.into_response();
    };
    let Some(phone) = parent_events::Phone::load(&state.db, device.id).await else {
        return StatusCode::INTERNAL_SERVER_ERROR.into_response();
    };
    let now = phone.now;
    let asked = asked.min(now);
    let latest: Option<(String, String, Option<String>)> = match sqlx::query_as(
        "SELECT reported_at, lock_reason, time_state_json FROM device_status \
         WHERE device_id = ? ORDER BY reported_at DESC, id DESC LIMIT 1",
    )
    .bind(device.id)
    .fetch_optional(&state.db)
    .await
    {
        Ok(latest) => latest,
        Err(err) => {
            tracing::error!(device_id = device.id, %err, "couldn't read the last report");
            return StatusCode::INTERNAL_SERVER_ERROR.into_response();
        }
    };
    let report = latest.and_then(|(at, reason, json)| {
        crate::handlers::devices::time_report(&at, &reason, json.as_deref())
    });
    // The day the cap and "stale" go by (qa-r13-rule-card-code Q-2): the phone's own, in the zone it
    // reports (`time_state.zone`, the zone its ledger counts its day in), else Phone Setup's.
    let day = extra_time_day_zone(report.as_ref().map(|(_, r)| r), &phone.zone);
    let start_of_day = day.start_of_today(now);
    if asked < start_of_day {
        return Json(TimeRequestAnswer::STALE).into_response();
    }
    // Where the phone stood, from a report of the last 15 minutes only (Q-9: an older one may say
    // "School" from the morning); the sync posts its report just before the request.
    let ask = report
        .filter(|(at, _)| {
            now - *at <= chrono::Duration::minutes(crate::time_state::AWAY_STALE_MINUTES)
        })
        .map(|(_, report)| parent_events::TimeAsk::of(&report, &phone.zone, now))
        .unwrap_or(parent_events::TimeAsk::Unknown);
    let result = async {
        let mut tx = state.db.begin_with("BEGIN IMMEDIATE").await?;
        let earlier: i64 = sqlx::query_scalar(
            "SELECT COUNT(*) FROM parent_events WHERE device_id = ? AND kind = 'extra_time_request' \
             AND dedup_key != ? AND datetime(happened_at) >= datetime(?)",
        )
        .bind(device.id)
        .bind(&request.id)
        .bind(crate::push::db_time(start_of_day))
        .fetch_one(&mut *tx)
        .await?;
        if earlier >= EXTRA_TIME_REQUESTS_A_DAY {
            tx.rollback().await?;
            return Ok::<Option<bool>, sqlx::Error>(None);
        }
        let push = if now - asked < chrono::Duration::minutes(EXTRA_TIME_NEWS_MINUTES) {
            Push::Now
        } else {
            Push::No
        };
        let event = parent_events::extra_time_request(
            device.id,
            &phone.name,
            &request.id,
            asked,
            &ask,
            push,
        );
        let raised = parent_events::raise(&mut tx, &event).await?.is_some();
        tx.commit().await?;
        Ok(Some(raised))
    };
    match result.await {
        Ok(None) => {
            tracing::info!(
                device_id = device.id,
                "a request for more time over the day's cap"
            );
            Json(TimeRequestAnswer::CAPPED).into_response()
        }
        Ok(Some(raised)) => {
            if raised {
                state.push.wake();
            }
            Json(TimeRequestAnswer::ACCEPTED).into_response()
        }
        Err(err) => {
            tracing::error!(device_id = device.id, %err, "couldn't store a request for more time");
            StatusCode::INTERNAL_SERVER_ERROR.into_response()
        }
    }
}
