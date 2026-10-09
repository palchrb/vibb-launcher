use std::convert::Infallible;
use std::time::Duration;

use axum::extract::{Path, State};
use axum::http::{StatusCode, header};
use axum::response::IntoResponse;
use axum::response::sse::{Event, KeepAlive, Sse};
use axum::{Extension, Json};
use tokio_stream::StreamExt;
use tokio_stream::wrappers::BroadcastStream;

use crate::AppState;
use crate::models::{
    CallPolicy, CommandResultRequest, Device, DeviceContactRow, DevicePolicy, DnsBlocklistCategory,
    DnsEventReport, DnsFilterSettings, EnrollRequest, EnrollResponse, GlobalSchedule,
    InstallProgressReport, InstalledApp, LauncherUi, PendingCommand, PolicyContact, PolicyResponse,
    StatusReportRequest, TrackedApp, TrackedAppUpdate,
};
use crate::security::{self, AuthedDevice};
use crate::time_rules::{LocationPolicy, TimePolicy};

pub async fn enroll(
    State(state): State<AppState>,
    Json(req): Json<EnrollRequest>,
) -> impl IntoResponse {
    let device = sqlx::query_as::<_, Device>(
        "SELECT * FROM devices WHERE enrollment_code = ? \
         AND enrollment_code_expires_at > datetime('now')",
    )
    .bind(&req.enrollment_code)
    .fetch_optional(&state.db)
    .await
    .ok()
    .flatten();

    let Some(device) = device else {
        return (
            StatusCode::UNAUTHORIZED,
            "invalid or expired enrollment code",
        )
            .into_response();
    };

    let token = security::generate_device_token();
    let token_hash = security::hash_token(&token);

    sqlx::query(
        "UPDATE devices SET token_hash = ?, enrollment_code = NULL, \
         enrollment_code_expires_at = NULL, enrolled_at = datetime('now') WHERE id = ?",
    )
    .bind(&token_hash)
    .bind(device.id)
    .execute(&state.db)
    .await
    .ok();

    Json(EnrollResponse {
        device_id: device.id,
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
    let location_policy = LocationPolicy {
        mode: policy.location_mode.clone(),
        interval_minutes: policy.location_interval_minutes,
    };

    let dns_upstream_provider =
        sqlx::query_as::<_, DnsFilterSettings>("SELECT * FROM dns_filter_settings WHERE id = 1")
            .fetch_optional(&state.db)
            .await?
            .map(|s| s.upstream)
            .unwrap_or_else(|| "cloudflare".to_string());

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

    // Popped last, after every read above has succeeded, and in one statement: a 500 never
    // consumes a command, and two concurrent polls can't both get the same one. Delivery is
    // still at-most-once - a command popped into a response the launcher then refuses (it
    // rejects a suspicious policy, or can't decode it) is lost, which is why every command
    // (ring/stop_ring/lock/wipe) must be safe to simply queue again. See
    // migrations/0010_find_my_device.sql and handlers::locate.
    let pending_command = sqlx::query_as::<_, (i64, String)>(
        "UPDATE device_commands SET delivered_at = datetime('now') WHERE id = \
         (SELECT id FROM device_commands WHERE device_id = ? AND delivered_at IS NULL \
          ORDER BY requested_at ASC, id ASC LIMIT 1) \
         RETURNING id, command",
    )
    .bind(device_id)
    .fetch_optional(&state.db)
    .await?
    .map(|(id, command)| PendingCommand { id, command });

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
        packages_to_uninstall,
        call_policy,
        hardening: policy.hardening,
        launcher_ui: LauncherUi {
            language: policy.launcher_language,
            home_columns: policy.home_columns,
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
        },
        time_policy,
        location_policy,
        music,
    })
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

/// Opaque per-device token combining the global compiled blocklist's content
/// hash with a summary of this device's own overrides/custom domains, so the
/// client can tell "has my effective blocklist changed since I last fetched
/// it" (see `PolicyResponse.dns_filter_version`'s doc comment) without
/// needing to compare the full ~100k+ domain list on every poll.
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
    Ok(hex::encode(hasher.finalize()))
}

/// This device's fully-resolved effective blocklist: every feed the global
/// default has on (minus this device's overrides that turn one off), plus
/// any feed this device's override turns on even if the global default has
/// it off, plus global + device-scoped custom block domains, minus global +
/// device-scoped custom allow domains. Only fetched by the client when
/// `PolicyResponse.dns_filter_version` changes - see that field's doc
/// comment - since this can be a large (~100k+ domain) payload.
pub async fn dns_blocklist(
    State(state): State<AppState>,
    Extension(AuthedDevice(device)): Extension<AuthedDevice>,
) -> impl IntoResponse {
    let global_enabled: std::collections::HashMap<i64, bool> =
        sqlx::query_as::<_, (i64, bool)>("SELECT id, enabled FROM dns_blocklists")
            .fetch_all(&state.db)
            .await
            .unwrap_or_default()
            .into_iter()
            .collect();

    let overrides: std::collections::HashMap<i64, bool> = sqlx::query_as::<_, (i64, bool)>(
        "SELECT blocklist_id, enabled FROM device_blocklist_overrides WHERE device_id = ?",
    )
    .bind(device.id)
    .fetch_all(&state.db)
    .await
    .unwrap_or_default()
    .into_iter()
    .collect();

    let device_custom = sqlx::query_as::<_, crate::models::DnsCustomDomain>(
        "SELECT * FROM dns_custom_domains WHERE device_id = ?",
    )
    .bind(device.id)
    .fetch_all(&state.db)
    .await
    .unwrap_or_default();
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

    let compiled = state.dns_compiled.read().await;

    let mut categories: Vec<DnsBlocklistCategory> = compiled
        .lists
        .iter()
        .filter(|list| {
            let effective = overrides
                .get(&list.blocklist_id)
                .copied()
                .unwrap_or_else(|| {
                    global_enabled
                        .get(&list.blocklist_id)
                        .copied()
                        .unwrap_or(false)
                });
            effective
        })
        .map(|list| DnsBlocklistCategory {
            category: list.category.clone(),
            domains: list
                .domains
                .iter()
                .filter(|d| !device_allow.contains(*d) && !crate::play::is_fcm_protected(d))
                .cloned()
                .collect(),
        })
        .collect();

    let custom_block: Vec<String> = compiled
        .global_custom_block
        .iter()
        .chain(device_block.iter())
        .filter(|d| {
            !device_allow.contains(*d)
                && !compiled.global_custom_allow.contains(*d)
                && !crate::play::is_fcm_protected(d)
        })
        .cloned()
        .collect();
    if !custom_block.is_empty() {
        categories.push(DnsBlocklistCategory {
            category: "Custom".to_string(),
            domains: custom_block,
        });
    }

    Json(categories).into_response()
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
    Json(report): Json<StatusReportRequest>,
) -> impl IntoResponse {
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
    let lock_state_json = report
        .lock_state
        .as_ref()
        .and_then(crate::kid_lock::sanitize_lock_state);
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

    // The previous report, for the security log below (install mode started, new apps).
    let previous: Option<(Option<String>, Option<i64>)> = sqlx::query_as(
        "SELECT installed_apps_json, install_mode_until_ms FROM device_status \
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
          backup_service_enabled, app_downloads_json, boot_cover_json, music_state_json) \
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
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
    .execute(&state.db)
    .await
    .ok();

    // A phone's failing downloads and streams flag their items for a re-resolve (design 21b §2.5).
    if let Some(report) = crate::music::parse_music_state(music_state_json.as_deref())
        && !report.item_errors.is_empty()
    {
        match crate::music_sweep::flag_reports(&state.db, device.id, &report, chrono::Utc::now())
            .await
        {
            Ok(0) => {}
            Ok(_) => state.music_sweep.wake(),
            Err(err) => tracing::warn!(device_id = device.id, %err, "can't flag music items"),
        }
    }

    // The package-name backfill below compares against the same previous report (read before
    // this one was stored, so a failed INSERT or two reports in one second can't confuse it).
    let previous_apps_json: Option<String> = previous.as_ref().and_then(|(apps, _)| apps.clone());
    log_play_events(&state, device.id, &report, previous).await;

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

    // Bootstraps a brand-new device's allowlist to a snapshot of whatever it reports installed on
    // its very first heartbeat - kiosk_desired is mandatory for every device now (see
    // handlers::devices), but engaging kiosk still requires a non-empty allowlist
    // (AppEnforcer.apply's shouldEngageKiosk check client-side), so without this a freshly
    // enrolled device would sit fully unpinned until an admin manually checked boxes on the Apps
    // page. Only ever fires once per device - `allowlist_json` being non-null (even "[]") means an
    // admin or an earlier toggle has already taken ownership of it, so this never overwrites a
    // real, intentional selection.
    // One conditional UPDATE, not read-then-write: a read error used to count as "not set" and
    // overwrite the parent's allowlist with every installed app, and a separate read and write
    // raced `toggle_app`.
    if let Some(installed) = report
        .installed_apps
        .as_ref()
        .filter(|apps| !apps.is_empty())
    {
        // Play Store/services/GSF are never the parent's to allow (handy step 7, `play`).
        let package_names: Vec<&str> = installed
            .iter()
            .map(|a| a.package_name.as_str())
            .filter(|p| !crate::play::is_play_core(p))
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

    // Backfills tracked_apps.package_name for a catalog app that doesn't have one on file yet
    // (a real, supported state - see tracked_app_detail.html, "Android package name (optional)" -
    // PackageInstaller determines the real package from the APK's own signed manifest, not this
    // field), the moment it becomes knowable from what's newly installed on this device.
    //
    // Without this, a no-package-name catalog app that gets pushed and installed shows up as BOTH
    // a real "Installed" row (under its real package name, with no way to associate it back to
    // its catalog entry) *and* a permanent phantom "Not installed" row (the catalog entry itself,
    // which can never match anything by package name) - confirmed live. Worse, it silently never
    // gets allowlisted either: `toggle_app`'s add_to_allowlist call at check-time is a no-op
    // without a package name, so the app installs successfully and is then immediately suspended
    // by the kid's own device under kiosk mode, with nothing on the admin site showing why.
    //
    // Heuristic, not exact - only fires when this device has exactly one catalog app selected with
    // no package name on file, and exactly one package newly appeared since its last heartbeat.
    // Ambiguous if more than one no-package app is mid-install for the same device at once (rare -
    // an admin adds one, waits for it to land, then adds the next), but even a wrong guess there is
    // strictly better than the guaranteed-wrong permanent duplicate this replaces.
    if let Some(installed) = &report.installed_apps {
        let awaiting_package_name: Vec<i64> = sqlx::query_scalar(
            "SELECT ta.id FROM tracked_apps ta \
             JOIN device_tracked_apps dta ON dta.tracked_app_id = ta.id \
             WHERE dta.device_id = ? AND ta.package_name = ''",
        )
        .bind(device.id)
        .fetch_all(&state.db)
        .await
        .unwrap_or_default();

        if let [tracked_app_id] = awaiting_package_name.as_slice() {
            let previously_installed: std::collections::HashSet<String> = previous_apps_json
                .as_deref()
                .and_then(|j| serde_json::from_str::<Vec<InstalledApp>>(j).ok())
                .unwrap_or_default()
                .into_iter()
                .map(|a| a.package_name)
                .collect();
            let newly_appeared: Vec<&InstalledApp> = installed
                .iter()
                .filter(|a| !previously_installed.contains(&a.package_name))
                .collect();

            if let [app] = newly_appeared.as_slice() {
                sqlx::query("UPDATE tracked_apps SET package_name = ? WHERE id = ?")
                    .bind(&app.package_name)
                    .bind(*tracked_app_id)
                    .execute(&state.db)
                    .await
                    .ok();
                match crate::handlers::devices::add_to_allowlist(
                    &state,
                    device.id,
                    &app.package_name,
                )
                .await
                {
                    // The phone keeps the new app hidden and suspended until it re-fetches the
                    // policy - nudged now over the SSE stream, not at the next backstop sync up to
                    // 30 min later.
                    Ok(()) => {
                        let _ = state.command_notify.send(device.id);
                    }
                    Err(err) => {
                        tracing::error!(device_id = device.id, %err, "failed to allowlist a backfilled app");
                    }
                }
            }
        }
    }

    // Attached on every regular heartbeat when the device has a location
    // reading available, not just after a `locate` command - see
    // LocationReport's doc comment. Pruned by retention::prune (the phone's
    // location_retention_days, default 7; the newest fix is kept).
    if let Some(loc) = report.location {
        sqlx::query(
            "INSERT INTO device_locations \
             (device_id, latitude, longitude, accuracy_meters, captured_at) \
             VALUES (?, ?, ?, ?, ?)",
        )
        .bind(device.id)
        .bind(loc.latitude)
        .bind(loc.longitude)
        .bind(loc.accuracy_meters)
        .bind(&loc.captured_at)
        .execute(&state.db)
        .await
        .ok();
    }

    StatusCode::NO_CONTENT
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

/// The device reports back whether a delivered command actually succeeded -
/// never called for `wipe` (the device is gone by the time it would report).
/// Scoped to this device's own commands only, so one device can't ack
/// another's queue entry.
pub async fn command_result(
    State(state): State<AppState>,
    Extension(AuthedDevice(device)): Extension<AuthedDevice>,
    Json(req): Json<CommandResultRequest>,
) -> impl IntoResponse {
    sqlx::query(
        "UPDATE device_commands SET acknowledged_at = datetime('now'), result = ? \
         WHERE id = ? AND device_id = ?",
    )
    .bind(if req.success {
        req.message.unwrap_or_else(|| "ok".to_string())
    } else {
        req.message.unwrap_or_else(|| "failed".to_string())
    })
    .bind(req.command_id)
    .bind(device.id)
    .execute(&state.db)
    .await
    .ok();

    StatusCode::NO_CONTENT
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
/// connection and reconnect; a proxy in front of this server needs a read timeout above it.
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
    // A lagged receiver lost ids - possibly this device's - so it nudges (QA 07 #16): one extra
    // sync is harmless, a lost ring isn't.
    let stream = BroadcastStream::new(rx).filter_map(move |msg| {
        let _open: &crate::streams::StreamGuard = &guard;
        match msg {
            Ok(id) if id == device_id => Some(Ok(Event::default().data("command"))),
            Err(tokio_stream::wrappers::errors::BroadcastStreamRecvError::Lagged(_)) => {
                Some(Ok(Event::default().data("command")))
            }
            _ => None,
        }
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

/// `release_tag` as the device sees it: composited with the asset id when one's cached
/// (GitHub-sourced apps) - see `handlers::tracked_apps::sync_one_app`'s doc comment for why a
/// rolling tag alone can't be trusted to signal "this is a new build" client-side.
fn device_release_tag(app: &TrackedApp) -> Option<String> {
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
