mod admin_auth;
mod apk;
mod app_display;
mod app_downloads;
mod app_icons;
mod attention;
mod audit;
mod boot_witness;
mod config;
mod crashes;
mod device_routes;
mod display_time;
mod dns_engine;
mod enrollment;
mod gate;
mod handlers;
mod kid_lock;
mod kiosk_escapes;
mod limits;
mod lock_glance;
mod models;
mod music;
mod music_icons;
mod music_import;
mod music_net;
mod music_secret;
mod music_sources;
mod music_sweep;
mod net;
mod parent_pin;
mod phone;
mod photos;
mod play;
mod retention;
mod security;
mod sound_mode;
mod startup;
mod storytel;
mod streams;
#[cfg(test)]
mod tests;
mod time_rules;
mod time_state;
mod wallpapers;
mod window_relock;

use axum::Router;
use axum::extract::DefaultBodyLimit;
use axum::http::StatusCode;
use axum::middleware::from_fn_with_state;
use axum::response::{IntoResponse, Redirect, Response};
use axum::routing::{get, post};
use sqlx::SqlitePool;
use sqlx::sqlite::{SqliteConnectOptions, SqlitePoolOptions, SqliteSynchronous};
use std::str::FromStr;
use tower_http::services::ServeDir;
use tower_sessions::SessionManagerLayer;
use tower_sessions::session_store::ExpiredDeletion;
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
    /// Each DNS blocklist feed's last good domains (`dns_engine`, QA of the security wave, Low 8):
    /// a feed that can't be downloaded after a restart is loaded from here.
    pub dns_feed_dir: std::sync::Arc<std::path::PathBuf>,
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
    /// Episode art the sweep fetched (`data/music_art`, `photos::MUSIC_ART`, design 27): its own
    /// store, never backed up.
    pub music_art_dir: std::sync::Arc<std::path::PathBuf>,
    /// The key the Storytel login is sealed with (`music_secret`); `None` = Storytel is off.
    pub music_key: Option<std::sync::Arc<music_secret::MusicKey>>,
    /// Every music source fetch - the add check, the sweep, source covers (design 21b §2.1/§2.7):
    /// `music_net::HttpSource` behind the one gate (`music_net::Gated`); canned in the tests.
    pub music_fetch: std::sync::Arc<dyn music_net::Source>,
    /// The music sweep's wake-up and what it is checking (`music_sweep::Sweep`).
    pub music_sweep: std::sync::Arc<music_sweep::Sweep>,
    /// Each phone's built music library, under the library revision (`music::LibraryCache`).
    pub music_libraries: std::sync::Arc<music::LibraryCache>,
    /// The server's one Storytel session (design 26b §2.1): the JWT and the last shelf in memory,
    /// one login at a time (`storytel::Account`).
    pub storytel: std::sync::Arc<storytel::Account>,
    /// Where this server listens and whom it believes (design 22 §2): `net::NetConfig`.
    pub net: std::sync::Arc<net::NetConfig>,
    /// Security events that come in floods, written at most once a minute per client
    /// (`audit::EventThrottle`).
    pub audit: std::sync::Arc<audit::EventThrottle>,
    /// The device API's limits and each phone's last access, in memory (`limits::Limits`).
    pub limits: std::sync::Arc<limits::Limits>,
    /// `token_hash -> device id`, so a token check never touches the database
    /// (`security::TokenIndex`).
    pub tokens: std::sync::Arc<security::TokenIndex>,
    /// Whether a typed enrollment code is live, and the typed-code pause (`enrollment`).
    pub enrollment: std::sync::Arc<enrollment::Enrollment>,
    /// argon2 off the async workers, one at a time with a short queue (`admin_auth`).
    pub passwords: std::sync::Arc<admin_auth::PasswordGate>,
}

pub const APP_VERSION: &str = concat!("v", env!("CARGO_PKG_VERSION"));

/// The log filter: `rust_log` (RUST_LOG) or `info`, with reqwest never logging below `info`
/// (design 26b QA L10, code QA L2): the Storytel login's URL carries the e-mail and the encrypted
/// password (`pwd=`), and a reqwest version may log URLs at `debug` (0.12 logged each redirect's;
/// the 0.13 in the lock doesn't). Every `reqwest`/`reqwest::*` directive of `rust_log` is dropped
/// first, so a more specific one can't get past the floor. Defence in depth: it covers reqwest's
/// own events only.
pub fn log_filter(rust_log: Option<&str>) -> tracing_subscriber::EnvFilter {
    let own: Option<String> = rust_log.map(|value| {
        value
            .split(',')
            .filter(|directive| {
                let target = directive
                    .trim()
                    .split(['[', '='])
                    .next()
                    .unwrap_or("")
                    .trim();
                target != "reqwest" && !target.starts_with("reqwest::")
            })
            .collect::<Vec<_>>()
            .join(",")
    });
    own.filter(|value| !value.trim().is_empty())
        .and_then(|value| tracing_subscriber::EnvFilter::try_new(value).ok())
        .unwrap_or_else(|| tracing_subscriber::EnvFilter::new("info"))
        .add_directive("reqwest=info".parse().expect("a valid directive"))
}

#[tokio::main]
async fn main() {
    // Everything this process writes (the database, backups, the live mirror, keys) is for the
    // service user alone (security review M2): the systemd unit says so too, the container can't.
    rustix::process::umask(rustix::fs::Mode::from_raw_mode(0o077));
    dotenvy::dotenv().ok();
    // `info` unless RUST_LOG says otherwise: the startup lines (where the server listens, the
    // generated first password's file, the Storytel key) must reach the journal and `docker
    // logs`. tracing-subscriber's own default is errors only. reqwest never logs below `info`
    // here, whatever RUST_LOG says (`log_filter`).
    tracing_subscriber::fmt()
        .with_env_filter(log_filter(std::env::var("RUST_LOG").ok().as_deref()))
        .init();

    // Subcommands (design 22 §3.2.3, §6): run against the same .env and database, then exit.
    let args: Vec<String> = std::env::args().skip(1).collect();
    if let Some(command) = args.first() {
        std::process::exit(run_command(command, &args[1..]).await);
    }

    tracing::info!("Vibb Phone server {APP_VERSION} starting");

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
    let net_config = net::NetConfig::from_env();
    if !net_config.admin_device_api && net_config.device_bind_addr.is_none() {
        tracing::error!(
            "ADMIN_DEVICE_API=off and no DEVICE_BIND_ADDR: no listener would serve the phones - \
             set DEVICE_BIND_ADDR, or ADMIN_DEVICE_API=on"
        );
        std::process::exit(2);
    }
    if net_config.device_bind_addr.as_deref() == Some(net_config.bind_addr.as_str()) {
        tracing::error!(
            "BIND_ADDR and DEVICE_BIND_ADDR are both {} - give the phone listener its own port",
            net_config.bind_addr
        );
        std::process::exit(2);
    }

    // data/ must be writable before anything else (design 22 §6: a bind mount isn't seeded from
    // the image).
    if let Err(err) = startup::check_data_writable(std::path::Path::new("data")) {
        tracing::error!("{err}");
        std::process::exit(1);
    }

    // A restore the PWA asked for in the container (no root helper there): applied before the
    // database is opened, and undone if the restored one won't open.
    let db_path = startup::sqlite_path(&database_url);
    let restored = match &db_path {
        Some(path) => match startup::apply_restore_request(std::path::Path::new("data"), path) {
            Ok(restored) => restored,
            Err(err) => {
                tracing::error!("restore refused, the database is unchanged: {err}");
                None
            }
        },
        None => None,
    };
    let (db, restored) = match try_connect_db(&database_url).await {
        Ok(db) => (db, restored),
        Err(err) => match (&restored, &db_path) {
            (Some(restore), Some(path)) => {
                tracing::error!(
                    "the restored backup {} doesn't open ({err}) - putting the previous database \
                     back",
                    restore.filename
                );
                if let Err(undo) = startup::undo_restore(path, restore) {
                    tracing::error!("{undo}");
                    std::process::exit(1);
                }
                (connect_db(&database_url).await, None)
            }
            _ => {
                tracing::error!("{err}");
                std::process::exit(1);
            }
        },
    };
    if let Some(restore) = &restored {
        tracing::info!("restored the backup {}", restore.filename);
        security::record_security_event(
            &db,
            "backup_restored",
            None,
            None,
            Some(&format!(
                "{} (the previous database is in {})",
                restore.filename,
                restore.prerestore_dir.display()
            )),
        )
        .await;
    }

    // The Pi's root helper swapped in a backup while the server was stopped.
    let restored_on_host = startup::take_restore_applied(std::path::Path::new("data"));
    if let Some(filename) = &restored_on_host {
        tracing::info!("restored the backup {filename}");
        security::record_security_event(&db, "backup_restored", None, None, Some(filename)).await;
    }

    security::bootstrap_admin(&db, std::path::Path::new("data")).await;

    let session_store = SqliteStore::new(db.clone());
    session_store
        .migrate()
        .await
        .expect("failed to run session store migrations");
    if restored.is_some() || restored_on_host.is_some() {
        startup::forget_sessions(&db).await;
    }

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

    let session_layer = security::session_layer(
        session_store,
        insecure_cookies,
        net_config.session_inactivity_days,
    );

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
        dns_feed_dir: std::sync::Arc::new(std::path::PathBuf::from("data/dns_feeds")),
        wallpaper_dir: std::sync::Arc::new(std::path::PathBuf::from("data/wallpapers")),
        command_streams: Default::default(),
        app_syncs: Default::default(),
        tracked_apps_dir: std::sync::Arc::new(std::path::PathBuf::from(
            handlers::tracked_apps::TRACKED_APPS_DIR,
        )),
        music_files_dir: std::sync::Arc::new(std::path::PathBuf::from(music::MUSIC_FILES_DIR)),
        music_cover_dir: std::sync::Arc::new(std::path::PathBuf::from(music::MUSIC_COVERS_DIR)),
        music_art_dir: std::sync::Arc::new(std::path::PathBuf::from(music::MUSIC_ART_DIR)),
        music_key,
        music_fetch: std::sync::Arc::new(music_net::Gated::new(
            std::sync::Arc::new(music_net::HttpSource::new()),
            music_net::REQUEST_SPACING,
        )),
        music_sweep: Default::default(),
        music_libraries: Default::default(),
        storytel: Default::default(),
        net: std::sync::Arc::new(net_config),
        audit: Default::default(),
        limits: Default::default(),
        tokens: Default::default(),
        enrollment: Default::default(),
        passwords: Default::default(),
    };
    state
        .tokens
        .reload(&state.db)
        .await
        .expect("failed to read the device tokens");
    state.enrollment.refresh(&state.db).await;
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
    // The live mirror feeds an external drive and the app-update schedule a root helper - neither
    // exists in the container (design 22 §6: nothing writes update_requested there).
    if state.net.deploy_kind == net::DeployKind::Systemd {
        tokio::task::spawn(handlers::backups::run_live_mirror(state.clone()));
        tokio::task::spawn(handlers::system_update::run_scheduled_app_update_check(
            state.clone(),
        ));
    }
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
    // The music sweep (design 21b): lists every NRK/RSS entry, its first pass 60 s from now.
    tokio::task::spawn(music_sweep::run(state.clone()));
    // Design 24: parent PINs stored before the easy-PIN check get it once.
    tokio::task::spawn(parent_pin::run_easy_sweep(
        state.db.clone(),
        parent_pin::easy_candidates(),
    ));
    // Counts of throttled security events (refused admin requests, failed enrollments).
    tokio::task::spawn(audit::run_flusher(state.clone()));

    serve(state, session_layer).await;
}

/// `kid_phone_server <command>`; the exit code.
async fn run_command(command: &str, args: &[String]) -> i32 {
    match (command, args) {
        ("healthcheck", []) => {
            let bind_addr =
                std::env::var("BIND_ADDR").unwrap_or_else(|_| net::DEFAULT_BIND_ADDR.into());
            match startup::healthcheck(&bind_addr).await {
                Ok(()) => 0,
                Err(err) => {
                    eprintln!("healthcheck: {err}");
                    1
                }
            }
        }
        ("selftest-tls", []) => {
            let _ = rustls::crypto::ring::default_provider().install_default();
            match startup::selftest_tls().await {
                Ok(message) => {
                    println!("{message}");
                    0
                }
                Err(err) => {
                    eprintln!("selftest-tls: {err}");
                    1
                }
            }
        }
        ("reset-2fa", [username]) => {
            let _ = rustls::crypto::ring::default_provider().install_default();
            let database_url = std::env::var("DATABASE_URL")
                .unwrap_or_else(|_| "sqlite://data/kidphone.db".into());
            if let Some(path) = startup::sqlite_path(&database_url)
                && !path.exists()
            {
                eprintln!(
                    "reset-2fa: no database at {} - run this in the server's directory \
                     (/opt/kid-phone-server, or /app in the container)",
                    path.display()
                );
                return 1;
            }
            let db = connect_db(&database_url).await;
            match admin_auth::reset_2fa(&db, username, std::path::Path::new("data")).await {
                Ok(message) => {
                    println!("{message}");
                    0
                }
                Err(message) => {
                    eprintln!("reset-2fa: {message}");
                    1
                }
            }
        }
        _ => {
            eprintln!(
                "usage: kid_phone_server [reset-2fa <user> | healthcheck | selftest-tls]\n\
                 With no arguments it runs the server."
            );
            2
        }
    }
}

/// Both listeners (design 22 §2) until SIGTERM or Ctrl-C, then a graceful stop: no new
/// connections, the SSE streams end (`streams::CommandStreams::close_all`), and requests in
/// flight get [SHUTDOWN_GRACE] to finish.
async fn serve(state: AppState, session_layer: SessionManagerLayer<SqliteStore>) {
    let net = state.net.clone();
    tracing::info!("{}", net.exposure_line());
    for warning in net.startup_warnings() {
        tracing::warn!("{warning}");
    }

    let admin_listener = match tokio::net::TcpListener::bind(&net.bind_addr).await {
        Ok(listener) => listener,
        Err(err) => {
            tracing::error!("can't listen on BIND_ADDR {}: {err}", net.bind_addr);
            std::process::exit(1);
        }
    };
    let device_listener = match &net.device_bind_addr {
        Some(addr) => match tokio::net::TcpListener::bind(addr).await {
            Ok(listener) => Some(listener),
            Err(err) => {
                tracing::error!("can't listen on DEVICE_BIND_ADDR {addr}: {err}");
                std::process::exit(1);
            }
        },
        None => None,
    };

    let (stop_tx, stop_rx) = tokio::sync::watch::channel(false);
    let stopped = move || {
        let mut stop_rx = stop_rx.clone();
        async move {
            let _ = stop_rx.wait_for(|stop| *stop).await;
        }
    };
    let streams = state.command_streams.clone();
    let db = state.db.clone();

    let admin = axum::serve(
        admin_listener,
        build_admin_router(state.clone(), session_layer)
            .into_make_service_with_connect_info::<std::net::SocketAddr>(),
    )
    .with_graceful_shutdown(stopped());
    let device = device_listener.map(|listener| {
        axum::serve(
            listener,
            build_device_router(state)
                .into_make_service_with_connect_info::<std::net::SocketAddr>(),
        )
        .with_graceful_shutdown(stopped())
    });
    let both = async {
        tokio::try_join!(admin.into_future(), async {
            match device {
                Some(device) => device.await,
                None => Ok(()),
            }
        })
    };
    tokio::pin!(both);

    tokio::select! {
        result = &mut both => {
            if let Err(err) = result {
                tracing::error!("a listener failed: {err}");
                std::process::exit(1);
            }
        }
        () = shutdown_signal() => {
            tracing::info!("shutting down");
            let _ = stop_tx.send(true);
            streams.close_all();
            match tokio::time::timeout(SHUTDOWN_GRACE, &mut both).await {
                Ok(Ok(_)) => {}
                Ok(Err(err)) => tracing::error!("a listener failed while stopping: {err}"),
                Err(_) => tracing::warn!(
                    "requests still running after {} s - stopping anyway",
                    SHUTDOWN_GRACE.as_secs()
                ),
            }
        }
    }
    // Idle connections close at once; a background task's query gets a moment to finish.
    let _ = tokio::time::timeout(std::time::Duration::from_secs(2), db.close()).await;
}

/// How long requests in flight (an APK download, say) get after SIGTERM. Docker kills a
/// container 10 s after its stop signal.
const SHUTDOWN_GRACE: std::time::Duration = std::time::Duration::from_secs(8);

async fn shutdown_signal() {
    let ctrl_c = async {
        let _ = tokio::signal::ctrl_c().await;
    };
    #[cfg(unix)]
    let terminate = async {
        match tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate()) {
            Ok(mut signal) => {
                signal.recv().await;
            }
            Err(err) => {
                tracing::error!("can't watch for SIGTERM: {err}");
                std::future::pending::<()>().await;
            }
        }
    };
    #[cfg(not(unix))]
    let terminate = std::future::pending::<()>();
    tokio::select! {
        () = ctrl_c => {}
        () = terminate => {}
    }
}

/// `GET /healthz` on both listeners: `SELECT 1`, and only "ok" (no version, nothing else). It
/// skips every guard (the admin gate lets it through by path), so a container stays healthy with
/// any `ADMIN_*` setting.
async fn healthz(axum::extract::State(state): axum::extract::State<AppState>) -> Response {
    match sqlx::query_scalar::<_, i64>("SELECT 1")
        .fetch_one(&state.db)
        .await
    {
        Ok(_) => ([(axum::http::header::CACHE_CONTROL, "no-store")], "ok").into_response(),
        Err(err) => {
            tracing::error!(%err, "healthz: the database didn't answer");
            StatusCode::SERVICE_UNAVAILABLE.into_response()
        }
    }
}

/// Unknown paths: an empty 404 (both listeners).
async fn not_found() -> StatusCode {
    StatusCode::NOT_FOUND
}

/// The phone listener (`DEVICE_BIND_ADDR`, design 22 §2): the device API and `/healthz`, nothing
/// else - no session layer, no `/static`, and an empty 404 for every other path, so a proxy rule
/// meant for the phones can't expose the admin.
pub fn build_device_router(state: AppState) -> Router {
    device_routes::device_routes(state.clone())
        .route("/healthz", get(healthz))
        .fallback(not_found)
        .with_state(state)
}

/// The body limit of the forms reachable without a session (`/login`, `/auth/verify-2fa`).
const SIGNED_OUT_FORM_LIMIT: usize = 4096;

/// The admin listener (`BIND_ADDR`): every admin route, middleware and the session layer, given
/// ready-made state, plus the device API unless `ADMIN_DEVICE_API=off`, behind the admin gate
/// (`gate::admin_gate`, design 22 §2). Split out of `main()` so tests can drive the exact same
/// router in-process (see `tests`) - nothing here may spawn background tasks or touch the
/// filesystem beyond what serving a request does.
pub fn build_admin_router(
    state: AppState,
    session_layer: SessionManagerLayer<SqliteStore>,
) -> Router {
    // Reachable without any session at all. /sw.js lives here too - a
    // service-worker fetch has no session cookie context the way a page
    // load does, so it can't sit behind require_full_auth.
    let public_routes = Router::new()
        // Signed-out forms take a few fields: 4 KiB, not axum's 2 MB (QA 22 Low 1).
        .route(
            "/login",
            get(handlers::auth::login_form)
                .post(handlers::auth::login)
                .layer(DefaultBodyLimit::max(SIGNED_OUT_FORM_LIMIT)),
        )
        .route(
            "/auth/verify-2fa",
            get(handlers::auth::verify_2fa_form)
                .post(handlers::auth::verify_2fa)
                .layer(DefaultBodyLimit::max(SIGNED_OUT_FORM_LIMIT)),
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
            get(handlers::provisioning::provision_form).post(handlers::provisioning::provision_qr),
        )
        .route("/devices/{id}", get(handlers::devices::view_device))
        // Design 31 step 3: About This Phone and Advanced (their forms keep their POST routes).
        .route(
            "/devices/{id}/about",
            get(handlers::device_pages::view_about),
        )
        .route(
            "/devices/{id}/advanced",
            get(handlers::device_pages::view_advanced),
        )
        // Step 4: Lock Screen & PINs.
        .route("/devices/{id}/lock", get(handlers::device_pages::view_lock))
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
            "/devices/{id}/parent-pin",
            post(handlers::parent_pin::update_parent_pin),
        )
        .route(
            "/devices/{id}/end-windows",
            post(handlers::window_relock::end_windows),
        )
        .route(
            "/devices/{id}/allow-blocked",
            post(handlers::window_relock::allow_blocked),
        )
        .route(
            "/devices/{id}/start-pause",
            post(handlers::window_relock::start_pause),
        )
        .route(
            "/devices/{id}/regenerate-code",
            post(handlers::devices::regenerate_code),
        )
        .route(
            "/devices/{id}/revoke",
            post(handlers::devices::revoke_access),
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
            "/devices/{id}/lock-wallpaper",
            post(handlers::wallpapers::save_lock_wallpaper),
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
        .route(
            "/devices/{id}/commands/{command_id}/cancel",
            post(handlers::locate::cancel_command),
        )
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
            "/apps/tracked/{id}/confirm-package",
            post(handlers::tracked_apps::confirm_package),
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
        // The lock's glance page (design 25): the text switch and the apps it shows.
        .route(
            "/devices/{id}/lock-screen",
            post(handlers::lock_glance::save_text),
        )
        .route(
            "/devices/{id}/lock-screen/apps",
            post(handlers::lock_glance::save_app),
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
        .route("/music/sweep", post(handlers::music::save_sweep))
        .route("/music/cards", get(handlers::music::cards))
        .route(
            "/music/entries/{id}/check",
            post(handlers::music::check_now),
        )
        .route(
            "/music/entries/{id}/offline",
            post(handlers::music::save_offline),
        )
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
        .route(
            "/music/storytel",
            get(handlers::music::storytel_shelf).post(handlers::music::save_storytel),
        )
        .route("/music/storytel/add", post(handlers::music::add_storytel))
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
        .route(
            "/devices/{id}/music/sonos-rooms",
            post(handlers::music::save_device_sonos_rooms),
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
        .route("/settings", get(handlers::settings_hub::settings_hub))
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
        .route("/updates", get(handlers::updates::show_updates_page))
        .route("/security", get(handlers::admin::security_log))
        .route("/security/unban", post(handlers::admin::unban_ip))
        .route("/account", get(handlers::auth::account_page))
        .route(
            "/account/password",
            post(handlers::auth::update_account_password),
        )
        .route("/account/reset-2fa", post(handlers::auth::reset_totp))
        .route(
            "/account/sign-out-everywhere",
            post(handlers::auth::sign_out_everywhere),
        )
        .route(
            "/account/dismiss-warning",
            post(handlers::auth::dismiss_login_warning),
        )
        .layer(from_fn_with_state(
            state.clone(),
            security::require_full_auth,
        ));

    // Requests for the Pi's root helper (design 22 §6): the app update, restart and their
    // schedule, OS and Tailscale updates, reboot, formatting a drive. The container has no helper
    // and writes none of these files, so there the routes don't exist.
    let host_routes = Router::new()
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
        .route(
            "/backups/format-drive",
            post(handlers::backups::format_drive),
        )
        .layer(from_fn_with_state(
            state.clone(),
            security::require_full_auth,
        ));

    let mut app = Router::new()
        .merge(public_routes)
        .merge(onboarding_routes)
        .merge(admin_routes);
    if state.net.deploy_kind == net::DeployKind::Systemd {
        app = app.merge(host_routes);
    }
    if state.net.admin_device_api {
        app = app.merge(device_routes::device_routes(state.clone()));
    }
    app.route("/healthz", get(healthz))
        .nest_service("/static", ServeDir::new("static"))
        .fallback(not_found)
        .with_state(state.clone())
        .layer(session_layer)
        // Before the session is read: a cross-site form never acts (design 22 §3.2.5).
        .layer(from_fn_with_state(state.clone(), admin_auth::csrf_guard))
        .layer(axum::middleware::from_fn(admin_auth::admin_headers))
        // Outermost: a refused request never reaches the session store.
        .layer(from_fn_with_state(state, gate::admin_gate))
}

/// Every migration in `migrations/`, compiled in.
pub(crate) static MIGRATOR: sqlx::migrate::Migrator = sqlx::migrate!("./migrations");

/// Opens the SQLite pool with this app's connection settings and runs all migrations; panics on
/// failure (tests, subcommands). `main` uses [try_connect_db].
pub async fn connect_db(database_url: &str) -> SqlitePool {
    try_connect_db(database_url)
        .await
        .unwrap_or_else(|err| panic!("{err}"))
}

/// [connect_db] without the panic. With migrations pending on an existing database, it first
/// takes `data/backups/pre-migrate-<from>-<to>.db` (`startup::pre_migration_backup`, design 22
/// §6) - for every deploy kind - and refuses to migrate without it.
pub async fn try_connect_db(database_url: &str) -> Result<SqlitePool, String> {
    let connect_options = SqliteConnectOptions::from_str(database_url)
        .map_err(|err| format!("invalid DATABASE_URL: {err}"))?
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
        .map_err(|err| format!("failed to connect to database: {err:?}"))?;

    if let Some(path) = startup::sqlite_path(database_url) {
        let latest = MIGRATOR
            .iter()
            .map(|migration| migration.version)
            .max()
            .unwrap_or(0);
        if let Some(backup) = startup::pre_migration_backup(&db, &path, latest).await? {
            tracing::info!(
                "migrations pending - the database before them is in {}",
                backup.display()
            );
        }
    }

    MIGRATOR
        .run(&db)
        .await
        .map_err(|err| format!("failed to run migrations: {err}"))?;
    // The old weekday/weekend/bedtime schedule becomes time rules, once per row (handy step 6).
    time_rules::migrate_legacy(&db)
        .await
        .map_err(|err| format!("failed to convert the old schedules into time rules: {err}"))?;
    // A ring, pause or location update still waiting when the server stopped is never sent after
    // it starts again: the Pi has no RTC, so its clock may run behind until NTP fixes it, and the
    // hour couldn't be trusted (QA of cmd-expiry, Low 4).
    handlers::device_api::end_expiring_at_start(&db).await;

    Ok(db)
}
