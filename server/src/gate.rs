//! The admin gate (design 22 §2, QA High 1): the admin listener answers only loopback and
//! tailnet clients, so a proxy rule meant for the phones (or a Caddyfile typo) can't put the PWA
//! on the internet. `ADMIN_PUBLIC=on` turns it off for exposure (b), where the PWA then rests on
//! password + TOTP alone.
//!
//! - The client is resolved by `net::NetConfig::client` (`X-Forwarded-For` only from
//!   `TRUSTED_PROXIES`). It passes when it is loopback or tailnet, or when a trusted peer sent no
//!   `X-Forwarded-For` (a local tool on the box, the healthcheck).
//! - A request carrying `Tailscale-Funnel-Request` is refused: whether Funnel's
//!   `X-Forwarded-For` names the real internet client is not verified, so this is a second check.
//! - `ADMIN_TAILSCALE_USERS`: the admin pages also need a `Tailscale-User-Login` from that list,
//!   and the header counts only from a tailnet client (`tailscale serve` sets it and strips a
//!   client's copy; every Caddy site in DEPLOY.md strips it too, so behind Caddy this fails
//!   closed). The device API on the admin listener skips this check: phones are tagged nodes
//!   without a login and authenticate by token.
//! - `/healthz` skips every guard.
//!
//! Refused requests get an empty 403 and a throttled `admin_refused` event.

use std::net::SocketAddr;

use axum::extract::{ConnectInfo, Request, State};
use axum::http::StatusCode;
use axum::middleware::Next;
use axum::response::{IntoResponse, Response};

use crate::AppState;
use crate::net::{self, Client};

/// Why the gate refused a request (the event's detail).
#[derive(Debug, PartialEq, Eq)]
pub enum Refusal {
    /// Not loopback or tailnet, and `ADMIN_PUBLIC` is off.
    NotTailnet,
    /// `Tailscale-Funnel-Request` - a request from the internet through Funnel.
    Funnel,
    /// `ADMIN_TAILSCALE_USERS` is set and the request has no listed login from a tailnet client.
    TailscaleUser,
}

impl Refusal {
    fn as_str(&self) -> &'static str {
        match self {
            Refusal::NotTailnet => "not a tailnet or loopback client",
            Refusal::Funnel => "through Tailscale Funnel",
            Refusal::TailscaleUser => "no allowed Tailscale-User-Login",
        }
    }
}

/// The gate's decision for one request (pure, so the matrix is unit-tested).
pub fn decide(
    net: &net::NetConfig,
    client: &Client,
    headers: &axum::http::HeaderMap,
    path: &str,
) -> Result<(), Refusal> {
    if !net.admin_public {
        if headers.contains_key("tailscale-funnel-request") {
            return Err(Refusal::Funnel);
        }
        let allowed = net::is_loopback(client.ip)
            || net::is_tailnet(client.ip)
            || (net.is_trusted(client.peer) && !client.forwarded);
        if !allowed {
            return Err(Refusal::NotTailnet);
        }
    }
    if let Some(users) = &net.admin_tailscale_users
        && !path.starts_with("/api/devices/")
    {
        let login = net::is_tailnet(client.ip)
            .then(|| headers.get("tailscale-user-login"))
            .flatten()
            .and_then(|v| v.to_str().ok())
            .map(str::trim);
        if !login.is_some_and(|login| users.iter().any(|user| user == login)) {
            return Err(Refusal::TailscaleUser);
        }
    }
    Ok(())
}

/// The peer address of a request; `None` only when the router was served without
/// `into_make_service_with_connect_info` (a bug - the request is refused).
pub fn peer_of(request: &Request) -> Option<SocketAddr> {
    request
        .extensions()
        .get::<ConnectInfo<SocketAddr>>()
        .map(|ConnectInfo(addr)| *addr)
}

pub async fn admin_gate(State(state): State<AppState>, request: Request, next: Next) -> Response {
    if request.uri().path() == "/healthz" {
        return next.run(request).await;
    }
    let Some(peer) = peer_of(&request) else {
        tracing::error!("a request without its peer address - refused");
        return StatusCode::FORBIDDEN.into_response();
    };
    let client = state.net.client(request.headers(), peer.ip());
    match decide(&state.net, &client, request.headers(), request.uri().path()) {
        Ok(()) => next.run(request).await,
        Err(refusal) => {
            let ip = client.ip.to_string();
            let detail = format!(
                "{} (peer {}, {} {})",
                refusal.as_str(),
                client.peer,
                request.method(),
                truncate(request.uri().path(), 100)
            );
            state
                .audit
                .record(
                    &state.db,
                    "admin_refused",
                    &net::limit_key(client.ip),
                    Some(&ip),
                    &detail,
                )
                .await;
            StatusCode::FORBIDDEN.into_response()
        }
    }
}

fn truncate(text: &str, max: usize) -> &str {
    match text.char_indices().nth(max) {
        Some((index, _)) => &text[..index],
        None => text,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use axum::http::{HeaderMap, HeaderValue};
    use std::collections::HashMap;
    use std::net::IpAddr;

    fn ip(text: &str) -> IpAddr {
        text.parse().unwrap()
    }

    fn net(pairs: &[(&str, &str)]) -> net::NetConfig {
        net::NetConfig::from_vars(
            &pairs
                .iter()
                .map(|(k, v)| (k.to_string(), v.to_string()))
                .collect::<HashMap<_, _>>(),
        )
    }

    fn headers(pairs: &[(&'static str, &str)]) -> HeaderMap {
        let mut headers = HeaderMap::new();
        for (name, value) in pairs {
            headers.append(*name, HeaderValue::from_str(value).unwrap());
        }
        headers
    }

    fn check(
        net: &net::NetConfig,
        peer: &str,
        pairs: &[(&'static str, &str)],
        path: &str,
    ) -> Result<(), Refusal> {
        let headers = headers(pairs);
        let client = net.client(&headers, ip(peer));
        decide(net, &client, &headers, path)
    }

    #[test]
    fn only_loopback_and_tailnet_clients_pass_by_default() {
        let net = net(&[]);
        // Loopback without a proxy (a local tool), and the tailnet behind tailscale serve/Caddy.
        assert_eq!(check(&net, "127.0.0.1", &[], "/devices"), Ok(()));
        assert_eq!(check(&net, "::1", &[], "/devices"), Ok(()));
        assert_eq!(
            check(
                &net,
                "127.0.0.1",
                &[("x-forwarded-for", "100.101.102.103")],
                "/devices"
            ),
            Ok(())
        );
        assert_eq!(check(&net, "100.101.102.103", &[], "/devices"), Ok(()));
        // A public client through the local proxy, a LAN client on a non-loopback bind.
        assert_eq!(
            check(
                &net,
                "127.0.0.1",
                &[("x-forwarded-for", "203.0.113.7")],
                "/login"
            ),
            Err(Refusal::NotTailnet)
        );
        assert_eq!(
            check(&net, "192.168.1.20", &[], "/devices"),
            Err(Refusal::NotTailnet)
        );
        // A forged header from an untrusted peer counts for nothing.
        assert_eq!(
            check(
                &net,
                "192.168.1.20",
                &[("x-forwarded-for", "100.101.102.103")],
                "/devices"
            ),
            Err(Refusal::NotTailnet)
        );
        // The device API on the admin listener is gated the same way.
        assert_eq!(
            check(
                &net,
                "127.0.0.1",
                &[("x-forwarded-for", "203.0.113.7")],
                "/api/devices/policy"
            ),
            Err(Refusal::NotTailnet)
        );
    }

    #[test]
    fn a_trusted_bridge_gateway_without_xff_is_a_local_tool() {
        let net = net(&[("TRUSTED_PROXIES", "172.30.0.1")]);
        assert_eq!(check(&net, "172.30.0.1", &[], "/devices"), Ok(()));
        // ... but not when it forwards a public client.
        assert_eq!(
            check(
                &net,
                "172.30.0.1",
                &[("x-forwarded-for", "203.0.113.7")],
                "/devices"
            ),
            Err(Refusal::NotTailnet)
        );
        // Loopback is no longer trusted here, but still a loopback client.
        assert_eq!(check(&net, "127.0.0.1", &[], "/devices"), Ok(()));
    }

    #[test]
    fn funnel_is_refused_and_admin_public_passes_everyone() {
        let gated = net(&[]);
        assert_eq!(
            check(
                &gated,
                "127.0.0.1",
                &[
                    ("x-forwarded-for", "100.101.102.103"),
                    ("tailscale-funnel-request", "?1")
                ],
                "/devices"
            ),
            Err(Refusal::Funnel)
        );
        let public = net(&[("ADMIN_PUBLIC", "on")]);
        assert_eq!(
            check(
                &public,
                "127.0.0.1",
                &[("x-forwarded-for", "203.0.113.7")],
                "/login"
            ),
            Ok(())
        );
        assert_eq!(check(&public, "192.168.1.20", &[], "/devices"), Ok(()));
    }

    #[test]
    fn tailscale_user_login_counts_only_from_tailnet_clients() {
        let net = net(&[
            ("ADMIN_TAILSCALE_USERS", "parent@example.com"),
            ("ADMIN_PUBLIC", "on"),
        ]);
        let tailnet = [
            ("x-forwarded-for", "100.101.102.103"),
            ("tailscale-user-login", "parent@example.com"),
        ];
        assert_eq!(check(&net, "127.0.0.1", &tailnet, "/devices"), Ok(()));
        // The same header from a public client (Caddy passing it through) is ignored.
        assert_eq!(
            check(
                &net,
                "127.0.0.1",
                &[
                    ("x-forwarded-for", "203.0.113.7"),
                    ("tailscale-user-login", "parent@example.com")
                ],
                "/devices"
            ),
            Err(Refusal::TailscaleUser)
        );
        // Another login, no login (a tagged kid node), loopback without a login.
        assert_eq!(
            check(
                &net,
                "127.0.0.1",
                &[
                    ("x-forwarded-for", "100.101.102.103"),
                    ("tailscale-user-login", "kid@example.com")
                ],
                "/devices"
            ),
            Err(Refusal::TailscaleUser)
        );
        assert_eq!(
            check(
                &net,
                "127.0.0.1",
                &[("x-forwarded-for", "100.101.102.103")],
                "/devices"
            ),
            Err(Refusal::TailscaleUser)
        );
        assert_eq!(
            check(&net, "127.0.0.1", &[], "/devices"),
            Err(Refusal::TailscaleUser)
        );
        // Phones on the old URL authenticate by token.
        assert_eq!(
            check(
                &net,
                "127.0.0.1",
                &[("x-forwarded-for", "100.101.102.103")],
                "/api/devices/policy"
            ),
            Ok(())
        );
    }
}
