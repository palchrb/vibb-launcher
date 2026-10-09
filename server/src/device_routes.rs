//! The device-facing API (`/api/devices/*`), in one place (design 22 S0): the admin listener
//! serves it next to the admin UI, and the phone listener (`DEVICE_BIND_ADDR`) serves only this.
//! Every new device route goes here, never into `main.rs` (a test checks that `main.rs` holds no
//! `"/api/devices` literal).
//!
//! Enrollment is unauthenticated (the enrollment code itself is the one-shot credential); every
//! other route requires the bearer token issued at enrollment (`security::require_device_token`,
//! with the per-device limits of `limits`). Every route sits behind `limits::device_edge` (the
//! in-flight guard, the timeout, the response headers) and a 512 KiB body limit (4 KiB on
//! enroll).

use axum::Router;
use axum::extract::DefaultBodyLimit;
use axum::middleware::from_fn_with_state;
use axum::routing::{get, post};

use crate::{AppState, handlers, limits, security};

/// An enrollment request is one short code.
pub const ENROLL_BODY_LIMIT: usize = 4 * 1024;

/// Every device route with its auth layer. Takes the state for the middleware; the caller adds it
/// to the router with `with_state` as usual.
pub fn device_routes(state: AppState) -> Router<AppState> {
    let public_routes = Router::new().route(
        "/api/devices/enroll",
        post(handlers::device_api::enroll).layer(DefaultBodyLimit::max(ENROLL_BODY_LIMIT)),
    );

    let authed_routes = Router::new()
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
            "/api/devices/music/entries/{id}/items",
            get(handlers::music_api::listing),
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
        .merge(authed_routes)
        .layer(DefaultBodyLimit::max(limits::DEVICE_BODY_LIMIT))
        .layer(from_fn_with_state(state, limits::device_edge))
}
