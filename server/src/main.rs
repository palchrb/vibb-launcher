mod app_display;
mod app_downloads;
mod app_icons;
mod config;
mod crashes;
mod dns_engine;
mod handlers;
mod kid_lock;
mod kiosk_escapes;
mod models;
mod music;
mod music_icons;
mod music_import;
mod music_secret;
mod phone;
mod photos;
mod play;
mod retention;
mod security;
mod sound_mode;
mod streams;
#[cfg(test)]
mod tests;
mod time_rules;
mod wallpapers;

use axum::Router;
use axum::extract::DefaultBodyLimit;
use axum::middleware::from_fn_with_state;
use axum::response::Redirect;
use axum::routing::{get, post};
use sqlx::SqlitePool;
use sqlx::sqlite::{SqliteConnectOptions, SqlitePoolOptions, SqliteSynchronous};
use std::str::FromStr;
use tower_http::services::ServeDir;
use tower_sessions::cookie::time::Duration as CookieDuration;
use tower_sessions::session_store::ExpiredDeletion;
use tower_sessions::{Expiry, SessionManagerLayer};
use tower_sessions_sqlx_store::SqliteStore;

#[derive(Clone)]
pub struct AppState {
    pub db: SqlitePool,
    /// The compiled blocklist every device's on-device filter resolves its own effective list
    /// from - see `dns_engine::CompiledBlocklist`'s doc comment. This server no longer runs a
    /// live DNS server itself (Phase E of the on-device-filtering migration retired it, see
    /// CLAUDE.md) - filtering happens entirely on-device now.
    pub dns_compiled: dns_engine::SharedCompiledBlocklist,
    /// Broadcasts a device id the instant a command is queued for it (see
    /// `handlers::locate::queue_command`) - lets `handlers::device_api::commands_stream`'s SSE
    /// connection wake a connected device immediately instead of waiting for its next 2-minute
    /// policy poll. A `send` with no active subscribers (device offline/asleep) is expected and
    /// harmless - that device just picks the command up on its next regular poll instead, same as
    /// before this existed.
    pub command_notify: tokio::sync::broadcast::Sender<i64>,
    /// Fork-specific settings from env vars (release repo, launcher provisioning values) - see
    /// `config::ForkConfig`.
    pub config: std::sync::Arc<config::ForkConfig>,
    /// Where contact photos are stored (`data/contact_photos`; a temp dir in tests) - see
    /// `photos`.
    pub photo_dir: std::sync::Arc<std::path::PathBuf>,
    /// Where wallpaper images are stored (`data/wallpapers`; a temp dir in tests) - its own
    /// directory, because `photos::prune` deletes every file there no contact references.
    pub wallpaper_dir: std::sync::Arc<std::path::PathBuf>,
    /// Which phones hold the SSE command stream open right now, in memory only (design 19 Q1):
    /// the device page's "Instant changes" line. See `streams`.
    pub command_streams: std::sync::Arc<streams::CommandStreams>,
    /// Which catalog apps are syncing and how each one's last sync ended - one sync per app at a
    /// time (`handlers::tracked_apps::AppSyncs`).
    pub app_syncs: std::sync::Arc<handlers::tracked_apps::AppSyncs>,
    /// Where catalog apps' cached APKs are stored (`data/tracked_apps/<app id>/`; a temp dir in
    /// tests) - see `handlers::tracked_apps`.
    pub tracked_apps_dir: std::sync::Arc<std::path::PathBuf>,
    /// Vibb music (design 21): the parent's own audio files (`data/music_files/<entry id>/`, never
    /// backed up) and the cover store (`data/music_covers`, `photos::MUSIC_COVERS`).
    pub music_files_dir: std::sync::Arc<std::path::PathBuf>,
    pub music_cover_dir: std::sync::Arc<std::path::PathBuf>,
    /// The key the Storytel login is sealed with (`music_secret`); `None` = Storytel is off.
    pub music_key: Option<std::sync::Arc<music_secret::MusicKey>>,
    /// The add check's one GET (`music::HttpFetch`; canned in the tests).
    pub music_fetch: std::sync::Arc<dyn music::Fetch>,
    /// Each phone's built music library, under the library revision (`music::LibraryCache`).
    pub music_libraries: std::sync::Arc<music::LibraryCache>,
}

pub const APP_VERSION: &str = concat!("v", env!("CARGO_PKG_VERSION"));

#[tokio::main]
async fn main() {
    dotenvy::dotenv().ok();
    tracing_subscriber::fmt::init();
    tracing::info!("Kids Device MDM {APP_VERSION} starting");

    // Rustls 0.23+ requires an explicit process-wide default crypto provider
    // before any TLS connection works - without this, the DNS filter's
    // upstream (DNS-over-TLS) forwarding silently fails with
    // NetError(NoConnections), no clearer error given. "ring" to match
    // hickory-resolver's "tls-ring" feature.
    rustls::crypto::ring::default_provider()
        .install_default()
        .expect("failed to install default rustls crypto provider");

    let database_url =
        std::env::var("DATABASE_URL").unwrap_or_else(|_| "sqlite://data/kidphone.db".into());
    let bind_addr = std::env::var("BIND_ADDR").unwrap_or_else(|_| "127.0.0.1:3100".into());

    std::fs::create_dir_all("data").expect("failed to create data directory");

    let db = connect_db(&database_url).await;

    security::bootstrap_admin(&db).await;

    let session_store = SqliteStore::new(db.clone());
    session_store
        .migrate()
        .await
        .expect("failed to run session store migrations");

    tokio::task::spawn(
        session_store
            .clone()
            .continuously_delete_expired(tokio::time::Duration::from_secs(60 * 60)),
    );

    let insecure_cookies = std::env::var("INSECURE_COOKIES")
        .map(|v| v == "true" || v == "1")
        .unwrap_or(false);
    if insecure_cookies {
        tracing::warn!(
            "INSECURE_COOKIES is set - session cookies will be sent over plain HTTP. \
             This is for local LAN testing only and must never be set in production."
        );
    }

    let session_layer = SessionManagerLayer::new(session_store)
        .with_expiry(Expiry::OnInactivity(CookieDuration::days(30)))
        .with_secure(!insecure_cookies);

    let fork_config = config::ForkConfig::from_env();
    if fork_config.launcher_signature_checksum.is_none() {
        tracing::warn!(
            "LAUNCHER_SIGNATURE_CHECKSUM is not set - the provisioning page shows no QR code \
             until it is (see DEPLOY.md)"
        );
    }

    // FCM is gone (design 19): the SSE stream is the only nudge. A key left configured is
    // ignored - say so once, so it gets removed and revoked (DEPLOY.md, "Removing FCM").
    if std::env::var("FCM_SERVICE_ACCOUNT_FILE").is_ok_and(|v| !v.trim().is_empty()) {
        tracing::warn!(
            "FCM_SERVICE_ACCOUNT_FILE is set but FCM was removed in 0.20.0 - it is ignored. \
             Remove it from .env, delete the key file and revoke the key (DEPLOY.md, \
             \"Removing FCM\")"
        );
    }

    // The Storytel login's key (design 21, QA #10): .env's MUSIC_SECRET_KEY, else our own key file
    // in data/keys/ (the only place the unit lets us write; no backup copies it), made on the first
    // start.
    let music_key_file = std::env::var("MUSIC_SECRET_KEY_FILE")
        .ok()
        .filter(|v| !v.trim().is_empty())
        .unwrap_or_else(|| music_secret::DEFAULT_KEY_FILE.to_string());
    let music_key = match music_secret::load(
        std::env::var("MUSIC_SECRET_KEY").ok().as_deref(),
        std::path::Path::new(&music_key_file),
    ) {
        Ok(key) => {
            if std::env::var("MUSIC_SECRET_KEY").is_ok_and(|v| !v.trim().is_empty()) {
                tracing::info!("Storytel logins are sealed with MUSIC_SECRET_KEY from .env");
            } else {
                tracing::info!("Storytel logins are sealed with the key in {music_key_file}");
            }
            Some(std::sync::Arc::new(key))
        }
        Err(err) => {
            tracing::error!("Storytel logins are off: {err}");
            None
        }
    };

    let (command_notify, _) = tokio::sync::broadcast::channel(64);
    let state = AppState {
        db,
        dns_compiled: dns_engine::empty_compiled_blocklist(),
        command_notify,
        config: std::sync::Arc::new(fork_config),
        photo_dir: std::sync::Arc::new(std::path::PathBuf::from("data/contact_photos")),
        wallpaper_dir: std::sync::Arc::new(std::path::PathBuf::from("data/wallpapers")),
        command_streams: Default::default(),
        app_syncs: Default::default(),
        tracked_apps_dir: std::sync::Arc::new(std::path::PathBuf::from(
            handlers::tracked_apps::TRACKED_APPS_DIR,
        )),
        music_files_dir: std::sync::Arc::new(std::path::PathBuf::from(music::MUSIC_FILES_DIR)),
        music_cover_dir: std::sync::Arc::new(std::path::PathBuf::from(music::MUSIC_COVERS_DIR)),
        music_key,
        music_fetch: std::sync::Arc::new(music::HttpFetch),
        music_libraries: Default::default(),
    };
    dns_engine::compile_blocklist(&state, &state.dns_compiled).await;
    // After a restore the database may name photos or wallpapers that aren't on disk: take them
    // from the backups, or drop the reference (design 05, 08).
    photos::recover_missing(&state, std::path::Path::new(handlers::backups::BACKUP_DIR)).await;
    // Own music files are never backed up: one that isn't on disk is listed as missing (design 21,
    // QA #3), and an upload a crash interrupted leaves no temp file.
    music::remove_partial_uploads(&state.music_files_dir).await;
    music::flag_missing_files(&state.db, &state.music_files_dir).await;
    // The conversation journal is gone (migration 0038); so are its media files.
    retention::remove_journal_media(std::path::Path::new(retention::JOURNAL_MEDIA_DIR)).await;

    tokio::task::spawn(handlers::backups::run_scheduled_backups(state.clone()));
    tokio::task::spawn(handlers::backups::run_live_mirror(state.clone()));
    tokio::task::spawn(handlers::system_update::run_scheduled_app_update_check(
        state.clone(),
    ));
    // Partial downloads a crash left behind - before the scheduled sync can start a new one.
    handlers::tracked_apps::remove_partial_downloads(&state.tracked_apps_dir).await;
    // Hashes for cached files from before migration 0043 (design 13): the phones check them.
    tokio::task::spawn(handlers::tracked_apps::backfill_release_hashes(
        state.clone(),
    ));
    tokio::task::spawn(handlers::tracked_apps::run_scheduled_tracked_app_sync(
        state.clone(),
    ));
    tokio::task::spawn(handlers::dns_filter::run_blocklist_refresh(state.clone()));
    // Blocked domains, location history and status history (src/retention.rs).
    tokio::task::spawn(retention::run_pruning(state.clone()));

    let app = build_router(state, session_layer);

    tracing::info!("listening on {bind_addr}");
    let listener = tokio::net::TcpListener::bind(&bind_addr).await.unwrap();
    axum::serve(
        listener,
        app.into_make_service_with_connect_info::<std::net::SocketAddr>(),
    )
    .await
    .unwrap();
}

/// Every route, middleware and the session layer, given ready-made state. Split out of `main()`
/// so tests can drive the exact same router in-process (see `tests`) - nothing here may spawn
/// background tasks or touch the filesystem beyond what serving a request does.
pub fn build_router(state: AppState, session_layer: SessionManagerLayer<SqliteStore>) -> Router {
    // Reachable without any session at all. /sw.js lives here too - a
    // service-worker fetch has no session cookie context the way a page
    // load does, so it can't sit behind require_full_auth.
    let public_routes = Router::new()
        .route(
            "/login",
            get(handlers::auth::login_form).post(handlers::auth::login),
        )
        .route(
            "/auth/verify-2fa",
            get(handlers::auth::verify_2fa_form).post(handlers::auth::verify_2fa),
        )
        .route("/sw.js", get(handlers::sw::serve_sw));

    // Reachable with a valid session, even mid-onboarding (forced password
    // change / mandatory 2FA setup) - these routes ARE the onboarding gate,
    // so they can't themselves require onboarding to be complete.
    let onboarding_routes = Router::new()
        .route(
            "/auth/change-password",
            get(handlers::auth::change_password_form).post(handlers::auth::change_password),
        )
        .route(
            "/auth/setup-2fa",
            get(handlers::auth::setup_2fa_form).post(handlers::auth::setup_2fa_verify),
        )
        .route("/logout", post(handlers::auth::logout))
        .layer(from_fn_with_state(state.clone(), security::require_session));

    let admin_routes = Router::new()
        .route("/", get(|| async { Redirect::to("/devices") }))
        .route("/devices", get(handlers::devices::list_devices))
        .route("/schedules", get(handlers::schedules::show_schedules))
        .route(
            "/schedules/global",
            post(handlers::schedules::save_global_schedule),
        )
        .route(
            "/schedules/device/{id}",
            post(handlers::schedules::save_device_schedule),
        )
        .route("/schedules/rules", post(handlers::schedules::create_rule))
        .route(
            "/schedules/rules/{rule_id}",
            post(handlers::schedules::update_rule),
        )
        .route(
            "/schedules/rules/{rule_id}/delete",
            post(handlers::schedules::delete_rule),
        )
        .route("/devices/{id}/lifts", post(handlers::lifts::create_lift))
        .route(
            "/devices/{id}/lifts/{lift_id}/end",
            post(handlers::lifts::end_lift),
        )
        .route(
            "/devices/{id}/location-policy",
            post(handlers::locate::update_location_policy),
        )
        .route(
            "/devices/{id}/location-retention",
            post(handlers::locate::update_location_retention),
        )
        .route(
            "/devices/{id}/command/locate",
            post(handlers::locate::locate),
        )
        .route(
            "/devices/new",
            get(handlers::devices::new_device_form).post(handlers::devices::create_device),
        )
        .route(
            "/devices/{id}/provision",
            get(handlers::provisioning::provision_form),
        )
        .route("/devices/{id}", get(handlers::devices::view_device))
        .route(
            "/devices/{id}/policy",
            post(handlers::devices::update_policy),
        )
        .route(
            "/devices/{id}/hardening",
            post(handlers::devices::update_hardening),
        )
        .route(
            "/devices/{id}/kid-lock",
            post(handlers::devices::update_kid_lock),
        )
        .route(
            "/devices/{id}/regenerate-code",
            post(handlers::devices::regenerate_code),
        )
        .route(
            "/devices/{id}/delete",
            post(handlers::devices::delete_device),
        )
        .route("/devices/{id}/calls", get(handlers::calls::show_calls))
        .route(
            "/devices/{id}/calls/settings",
            post(handlers::calls::save_settings),
        )
        .route(
            "/devices/{id}/kiosk-block",
            post(handlers::devices::update_kiosk_block),
        )
        .route(
            "/devices/{id}/kiosk-escapes",
            post(handlers::devices::update_kiosk_escapes),
        )
        .route("/devices/{id}/contacts", post(handlers::calls::add_contact))
        .route(
            "/devices/{id}/contacts/{contact_id}",
            post(handlers::calls::update_contact),
        )
        .route(
            "/devices/{id}/contacts/{contact_id}/remove",
            post(handlers::calls::remove_contact),
        )
        .route(
            "/devices/{id}/contacts/{contact_id}/photo",
            post(handlers::calls::upload_photo)
                .layer(DefaultBodyLimit::max(photos::MAX_UPLOAD_BYTES + 64 * 1024)),
        )
        .route(
            "/devices/{id}/contacts/{contact_id}/photo/remove",
            post(handlers::calls::remove_photo),
        )
        .route("/contact-photos/{hash}", get(handlers::calls::view_photo))
        .route(
            "/devices/{id}/launcher",
            post(handlers::devices::update_launcher_ui),
        )
        .route(
            "/devices/{id}/screen-timeout",
            post(handlers::devices::update_screen_timeout),
        )
        .route(
            "/devices/{id}/wallpapers",
            post(handlers::wallpapers::save_device_wallpapers),
        )
        .route(
            "/wallpapers",
            get(handlers::wallpapers::show)
                .post(handlers::wallpapers::upload)
                .layer(DefaultBodyLimit::max(photos::MAX_UPLOAD_BYTES + 64 * 1024)),
        )
        .route(
            "/wallpapers/{wallpaper_id}/lock-screen",
            post(handlers::wallpapers::set_lock_screen),
        )
        .route(
            "/wallpapers/{wallpaper_id}/delete",
            post(handlers::wallpapers::delete),
        )
        .route(
            "/wallpaper-images/{hash}",
            get(handlers::wallpapers::view_image),
        )
        .route("/settings/calls", post(handlers::calls::save_call_settings))
        .route("/devices/locate", get(handlers::locate::show_locate))
        .route(
            "/devices/{id}/locations.json",
            get(handlers::locate::locations_json),
        )
        .route(
            "/devices/{id}/locate-result.json",
            get(handlers::locate::locate_result_json),
        )
        .route("/devices/{id}/command/ring", post(handlers::locate::ring))
        .route(
            "/devices/{id}/command/stop-ring",
            post(handlers::locate::stop_ring),
        )
        .route("/devices/{id}/command/lock", post(handlers::locate::lock))
        .route("/devices/{id}/command/wipe", post(handlers::locate::wipe))
        .route("/apps", get(handlers::tracked_apps::list_apps))
        .route(
            "/apps/tracked/new",
            get(handlers::tracked_apps::new_tracked_app_form)
                .post(handlers::tracked_apps::create_tracked_app),
        )
        .route(
            "/apps/tracked/{id}",
            // The Details form posts to the page's own path, so a refused save (400, the page
            // again) keeps the scroll position and the next form leaves from the right path.
            get(handlers::tracked_apps::view_tracked_app)
                .post(handlers::tracked_apps::update_tracked_app),
        )
        .route(
            "/apps/tracked/{id}/upload",
            post(handlers::tracked_apps::upload_tracked_app_release)
                .layer(DefaultBodyLimit::max(200 * 1024 * 1024)),
        )
        .route(
            "/apps/tracked/{id}/check",
            post(handlers::tracked_apps::check_now),
        )
        .route(
            "/apps/tracked/{id}/enabled",
            post(handlers::tracked_apps::set_enabled),
        )
        .route(
            "/apps/tracked/{id}/include-prereleases",
            post(handlers::tracked_apps::set_include_prereleases),
        )
        .route(
            "/apps/tracked/{id}/is-launcher",
            post(handlers::tracked_apps::set_is_launcher),
        )
        .route(
            "/apps/tracked/{id}/forget-package-name",
            post(handlers::tracked_apps::forget_package_name),
        )
        .route(
            "/apps/tracked/{id}/delete",
            post(handlers::tracked_apps::delete_tracked_app),
        )
        .route(
            "/devices/{id}/apps/toggle",
            post(handlers::devices::toggle_app),
        )
        .route(
            "/devices/{id}/app-updates",
            post(handlers::devices::update_app_updates),
        )
        .route(
            "/devices/{id}/apps/display",
            post(handlers::devices::save_app_display),
        )
        .route(
            "/apps/tracked/{id}/display",
            post(handlers::tracked_apps::save_display),
        )
        // Vibb music (design 21).
        .route(
            "/music",
            get(handlers::music::show).post(handlers::music::add_link),
        )
        .route("/music/own", post(handlers::music::add_own))
        .route(
            "/music/orphans/delete",
            post(handlers::music::delete_orphans),
        )
        .route(
            "/music/import",
            post(handlers::music_import::preview).layer(DefaultBodyLimit::max(
                music_import::MAX_IMPORT_BYTES + 64 * 1024,
            )),
        )
        .route(
            "/music/import/confirm",
            // The preview's document comes back form-encoded (up to about three times its size).
            post(handlers::music_import::confirm)
                .layer(DefaultBodyLimit::max(4 * music_import::MAX_IMPORT_BYTES)),
        )
        .route("/music/catalog-row", post(handlers::music::add_catalog_row))
        .route("/music/categories", post(handlers::music::add_category))
        .route(
            "/music/categories/{id}",
            post(handlers::music::save_category),
        )
        .route(
            "/music/categories/{id}/move",
            post(handlers::music::move_category),
        )
        .route(
            "/music/categories/{id}/delete",
            post(handlers::music::delete_category),
        )
        .route("/music/storytel", post(handlers::music::save_storytel))
        .route(
            "/music/storytel/clear",
            post(handlers::music::clear_storytel),
        )
        .route(
            "/music/entries/{id}",
            get(handlers::music::show_entry).post(handlers::music::save_entry),
        )
        .route(
            "/music/entries/{id}/move",
            post(handlers::music::move_entry),
        )
        .route(
            "/music/entries/{id}/delete",
            post(handlers::music::delete_entry),
        )
        .route(
            "/music/entries/{id}/cover",
            post(handlers::music::upload_cover)
                .layer(DefaultBodyLimit::max(photos::MAX_UPLOAD_BYTES + 64 * 1024)),
        )
        .route(
            "/music/entries/{id}/cover/remove",
            post(handlers::music::remove_cover),
        )
        .route(
            "/music/entries/{id}/files",
            post(handlers::music::upload_files)
                .layer(DefaultBodyLimit::max(music::MAX_UPLOAD_REQUEST_BYTES)),
        )
        .route(
            "/music/entries/{id}/files/{file_id}/delete",
            post(handlers::music::delete_file),
        )
        .route("/music-covers/{hash}", get(handlers::music::view_cover))
        .route(
            "/devices/{id}/music",
            post(handlers::music::save_device_settings),
        )
        .route(
            "/devices/{id}/music/entries/{entry_id}",
            post(handlers::music::set_device_entry),
        )
        .route("/dns", get(handlers::dns_filter::show_dns_filter))
        .route("/dns/upstream", post(handlers::dns_filter::set_upstream))
        .route(
            "/dns/blocklists/new",
            post(handlers::dns_filter::create_blocklist),
        )
        .route(
            "/dns/blocklists/{id}/toggle",
            post(handlers::dns_filter::toggle_blocklist),
        )
        .route(
            "/dns/blocklists/{id}/delete",
            post(handlers::dns_filter::delete_blocklist),
        )
        .route(
            "/dns/domains/new",
            post(handlers::dns_filter::create_custom_domain),
        )
        .route(
            "/dns/domains/{id}/delete",
            post(handlers::dns_filter::delete_custom_domain),
        )
        .route(
            "/dns/devices/{device_id}/blocklists/{blocklist_id}/override",
            post(handlers::dns_filter::set_device_blocklist_override),
        )
        .route("/dns/log", get(handlers::dns_filter::show_dns_log))
        .route(
            "/dns/log/{device_id}",
            post(handlers::dns_filter::set_dns_log),
        )
        .route("/settings", get(handlers::settings::settings_hub))
        .route(
            "/settings/provisioning",
            get(handlers::settings::provisioning_settings_form)
                .post(handlers::settings::update_provisioning_settings),
        )
        .route("/backups", get(handlers::backups::list_backups))
        .route("/backups/create", post(handlers::backups::create_backup))
        .route(
            "/backups/upload",
            post(handlers::backups::upload_backup).layer(DefaultBodyLimit::max(200 * 1024 * 1024)),
        )
        .route(
            "/backups/{filename}/download",
            get(handlers::backups::download_backup),
        )
        .route(
            "/backups/{filename}/delete",
            post(handlers::backups::delete_backup),
        )
        .route(
            "/backups/{filename}/restore",
            post(handlers::backups::restore_backup),
        )
        .route(
            "/backups/schedule",
            post(handlers::backups::save_backup_schedule),
        )
        .route(
            "/backups/format-drive",
            post(handlers::backups::format_drive),
        )
        .route("/updates", get(handlers::updates::show_updates_page))
        .route(
            "/update/trigger",
            post(handlers::system_update::trigger_update),
        )
        .route(
            "/update/restart",
            post(handlers::system_update::trigger_restart),
        )
        .route(
            "/update/schedule",
            post(handlers::system_update::save_app_update_schedule),
        )
        .route(
            "/system/os/check",
            post(handlers::system_maintenance::trigger_os_check),
        )
        .route(
            "/system/os/upgrade",
            post(handlers::system_maintenance::trigger_os_upgrade),
        )
        .route(
            "/system/tailscale/update",
            post(handlers::system_maintenance::trigger_tailscale_update),
        )
        .route(
            "/system/reboot",
            post(handlers::system_maintenance::trigger_reboot),
        )
        .route(
            "/system/schedule",
            post(handlers::system_maintenance::save_schedule),
        )
        .route("/security", get(handlers::admin::security_log))
        .route("/security/unban/{ip}", post(handlers::admin::unban_ip))
        .route("/account", get(handlers::auth::account_page))
        .route(
            "/account/password",
            post(handlers::auth::update_account_password),
        )
        .route("/account/reset-2fa", post(handlers::auth::reset_totp))
        .layer(from_fn_with_state(
            state.clone(),
            security::require_full_auth,
        ));

    // Device-facing API. Enrollment is unauthenticated (the enrollment code
    // itself is the one-shot credential); policy/status require the bearer
    // token issued at enrollment.
    let device_public_routes =
        Router::new().route("/api/devices/enroll", post(handlers::device_api::enroll));

    let device_authed_routes = Router::new()
        .route("/api/devices/policy", get(handlers::device_api::policy))
        .route("/api/devices/status", post(handlers::device_api::status))
        .route(
            "/api/devices/command-result",
            post(handlers::device_api::command_result),
        )
        .route(
            "/api/devices/commands/stream",
            get(handlers::device_api::commands_stream),
        )
        .route(
            "/api/devices/apps",
            get(handlers::device_api::tracked_app_updates),
        )
        .route(
            "/api/devices/wallpapers/{hash}",
            get(handlers::device_api::wallpaper_image),
        )
        .route(
            "/api/devices/contact-photos/{hash}",
            get(handlers::device_api::contact_photo),
        )
        .route(
            "/api/devices/apps/{id}/download",
            get(handlers::device_api::tracked_app_download),
        )
        .route(
            "/api/devices/apps/progress",
            post(handlers::device_api::install_progress),
        )
        .route(
            "/api/devices/dns-blocklist",
            get(handlers::device_api::dns_blocklist),
        )
        .route(
            "/api/devices/dns-events",
            post(handlers::device_api::dns_events),
        )
        .route(
            "/api/devices/crashes",
            post(handlers::device_api::crash_reports),
        )
        .route(
            "/api/devices/music/library",
            get(handlers::music_api::library),
        )
        .route(
            "/api/devices/music/covers/{hash}",
            get(handlers::music_api::cover),
        )
        .route(
            "/api/devices/music/files/{id}",
            get(handlers::music_api::file),
        )
        .route(
            "/api/devices/music/storytel",
            get(handlers::music_api::storytel),
        )
        .layer(from_fn_with_state(
            state.clone(),
            security::require_device_token,
        ));

    Router::new()
        .merge(public_routes)
        .merge(onboarding_routes)
        .merge(admin_routes)
        .merge(device_public_routes)
        .merge(device_authed_routes)
        .nest_service("/static", ServeDir::new("static"))
        .with_state(state)
        .layer(session_layer)
}

/// Opens the SQLite pool with this app's connection settings and runs all migrations.
pub async fn connect_db(database_url: &str) -> SqlitePool {
    let connect_options = SqliteConnectOptions::from_str(database_url)
        .expect("invalid DATABASE_URL")
        .create_if_missing(true)
        .foreign_keys(true)
        .journal_mode(sqlx::sqlite::SqliteJournalMode::Wal)
        .synchronous(SqliteSynchronous::Normal)
        .busy_timeout(std::time::Duration::from_secs(5))
        // Deleted rows (retention pruning, the dropped monitoring tables) are overwritten in the
        // file instead of lingering in free pages.
        .pragma("secure_delete", "on");

    let db = SqlitePoolOptions::new()
        .connect_with(connect_options)
        .await
        .expect("failed to connect to database");

    sqlx::migrate!("./migrations")
        .run(&db)
        .await
        .expect("failed to run migrations");
    // The old weekday/weekend/bedtime schedule becomes time rules, once per row (handy step 6).
    time_rules::migrate_legacy(&db)
        .await
        .expect("failed to convert the old schedules into time rules");

    db
}
