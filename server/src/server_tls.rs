//! Design 76: the launcher's certificate check, switched on per phone by this server
//! (`docs/design/76-server-issuer-pin.md` D2; migration 0129).
//!
//! Every phone with [CAPABILITY] checks the chain Android validated for this server against its built-in set (Let's
//! Encrypt's roots, ZeroSSL's operator Sectigo's - the launcher's alone: the server never judges a chain) and reports
//! what it sees in every status report: `server_tls: {enforcing, seen?: {in_set, name, pin, network, at_ms},
//! outside: [{kind, host, name, pin, network, at_ms}]}` (`testdata/server_tls_report.json`, [sanitize]). Per phone the
//! server is in one [State]:
//! - `watching` (no row, the start): the policy carries `server_tls: {}` - no `enforce`, so a phone keeps its flag
//!   (R-1). In-set sightings spanning [WINDOW] of receive time (never the phone's `at_ms`), on [WINDOW_DAYS] days, on
//!   Wi-Fi and mobile data when the phone has reported mobile at all (R-3), switch it `on` ([next_state]); an outside
//!   sighting stops that (R-2: it doesn't restart the window) - the device page shows it and offers "Turn the check on
//!   now". A phone reporting `enforcing: true` stored that only from a pinned `true`: the server adopts `on`.
//! - `on`: `{"enforce": true}`. Sticky: an outside sighting never switches it off (an attacker could cause one).
//! - `off`: `{"enforce": false}`, only by the parent ([by_parent]), with its own security event, a push to every
//!   parent and an SSE nudge (R-4) - sticky until "Turn it on again" (`watching`) or "Turn the check on now" (`on`).
//!
//! A new outside sighting of a phone that has shown in-set chains is pushed at most once a day per phone (`watching`
//! and `on`; R-2(c)): a split horizon or an attack, the parent hears of both. A launcher without [CAPABILITY] gets no
//! `server_tls`. Every state also carries `recovery_url` when this server knows the plain-http tailnet address a phone
//! refusing its certificate can be pointed at ([recovery_url], code QA H-1).

use std::net::IpAddr;

use chrono::{DateTime, Duration, SecondsFormat, Utc};
use serde::{Deserialize, Serialize};

use crate::AppState;
use crate::display_time::Zone;
use crate::i18n::{t, t_params};

/// The launcher's capability (`SERVER_PIN_CAPABILITY`): it reads the policy's `server_tls` and reports its own.
pub const CAPABILITY: &str = "server_pin_v1";
/// In-set sightings spanning this much receive time switch a watched phone on.
pub const WINDOW: Duration = Duration::hours(72);
/// ... on at least this many distinct days (server time, UTC dates).
pub const WINDOW_DAYS: usize = 3;
/// The outside rows kept per phone, as the phone keeps them.
pub const OUTSIDE_MAX: usize = 8;
/// A reported name is cut to this many characters, a pin is at most this long (44: SHA-256 in base64), a host this.
pub const NAME_MAX: usize = 64;
pub const PIN_MAX: usize = 44;
pub const HOST_MAX: usize = 253;
/// The days kept of a window (only [WINDOW_DAYS] count).
const DAYS_KEPT: usize = 7;
/// A refused row stays an alarm this long after it arrived, also once the phone has dropped it (code QA M-1).
const ALARM_KEPT: Duration = Duration::days(7);
/// A row's own time (the phone's clock) is shown when it lies within this before its receive time...
const PHONE_TIME_BACK: Duration = Duration::days(7);
/// ... or this after it; otherwise the receive time, worded "reported at" (code QA L-5).
const PHONE_TIME_AHEAD: Duration = Duration::minutes(5);

/// Where a phone's check is.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum State {
    Watching,
    On,
    Off,
}

impl State {
    pub fn wire(self) -> &'static str {
        match self {
            State::Watching => "watching",
            State::On => "on",
            State::Off => "off",
        }
    }

    pub fn of(wire: &str) -> State {
        match wire {
            "on" => State::On,
            "off" => State::Off,
            _ => State::Watching,
        }
    }
}

/// An outside sighting the phone reported (`refused` with its check on, `seen` with it off), with the receive time.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Outside {
    pub kind: String,
    pub host: String,
    pub name: String,
    pub pin: String,
    pub network: String,
    pub at_ms: i64,
    /// When this server first received it (RFC 3339); empty in a report.
    #[serde(default)]
    pub received_at: String,
    /// Whether the phone's last report still carried it (code QA M-1): the phone drops a row only after a 2xx over a
    /// connection that passed its pins or over the tailnet, so `false` means it has reached this server through a
    /// working connection since.
    #[serde(default)]
    pub open: bool,
}

impl Outside {
    /// The same row as the phone sent it (the receive time aside).
    fn same(&self, other: &Outside) -> bool {
        self.kind == other.kind
            && self.host == other.host
            && self.name == other.name
            && self.pin == other.pin
            && self.network == other.network
            && self.at_ms == other.at_ms
    }
}

/// The report's `seen`: this sync's policy connection as the phone saw it.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Seen {
    pub in_set: bool,
    pub name: String,
    pub pin: String,
    pub network: String,
    pub at_ms: i64,
}

/// A report's `server_tls`, within limits.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Report {
    pub enforcing: Option<bool>,
    pub seen: Option<Seen>,
    pub outside: Vec<Outside>,
}

fn network(value: Option<&serde_json::Value>) -> String {
    match value.and_then(|v| v.as_str()) {
        Some(n @ ("wifi" | "mobile")) => n.to_string(),
        _ => "other".to_string(),
    }
}

fn pin(value: Option<&serde_json::Value>) -> Option<String> {
    let pin = value?.as_str()?;
    (!pin.is_empty()
        && pin.len() <= PIN_MAX
        && pin
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'+' || b == b'/' || b == b'='))
    .then(|| pin.to_string())
}

/// A phone-supplied text within `max` characters, without control or bidi-control characters (code QA L-4: an
/// issuer's name goes into push titles and the inbox).
fn text(value: Option<&serde_json::Value>, max: usize) -> String {
    let clean: String = value
        .and_then(|v| v.as_str())
        .unwrap_or("")
        .chars()
        .filter(|&c| !hidden_char(c))
        .collect();
    crate::audit::clip(clean.trim(), max).to_string()
}

/// A control character, one that turns or marks the text's direction or hides in it, or a line or paragraph
/// separator (U+2028/9: not `is_control`, but some notifications break lines on them; verification n4).
fn hidden_char(c: char) -> bool {
    c.is_control()
        || matches!(
            c,
            '\u{061C}'
                | '\u{200B}'..='\u{200F}'
                | '\u{2028}'..='\u{2029}'
                | '\u{202A}'..='\u{202E}'
                | '\u{2060}'..='\u{2069}'
                | '\u{FEFF}'
        )
}

/// The report's `server_tls` within limits (8 rows, names 64 characters, pins 44 characters of base64): `None` for
/// anything but an object. A row without a valid pin or kind is dropped, never the report.
pub fn sanitize(value: &serde_json::Value) -> Option<Report> {
    let object = value.as_object()?;
    let seen = object.get("seen").and_then(|s| {
        Some(Seen {
            in_set: s.get("in_set")?.as_bool()?,
            name: text(s.get("name"), NAME_MAX),
            pin: pin(s.get("pin"))?,
            network: network(s.get("network")),
            at_ms: s.get("at_ms")?.as_i64()?,
        })
    });
    let rows: Vec<Outside> = object
        .get("outside")
        .and_then(|o| o.as_array())
        .map(|rows| {
            rows.iter()
                .filter_map(|r| {
                    let kind = r.get("kind")?.as_str()?;
                    if kind != "seen" && kind != "refused" {
                        return None;
                    }
                    Some(Outside {
                        kind: kind.to_string(),
                        host: text(r.get("host"), HOST_MAX),
                        name: text(r.get("name"), NAME_MAX),
                        pin: pin(r.get("pin"))?,
                        network: network(r.get("network")),
                        at_ms: r.get("at_ms")?.as_i64()?,
                        received_at: String::new(),
                        open: false,
                    })
                })
                .collect()
        })
        .unwrap_or_default();
    let skip = rows.len().saturating_sub(OUTSIDE_MAX);
    Some(Report {
        enforcing: object.get("enforcing").and_then(|e| e.as_bool()),
        seen,
        outside: rows.into_iter().skip(skip).collect(),
    })
}

/// A phone's stored state ([load], [store]); [Default] is `watching` from the start.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Row {
    pub state: State,
    pub window_from: Option<DateTime<Utc>>,
    pub window_days: Vec<String>,
    pub window_networks: Vec<String>,
    pub mobile_seen: bool,
    pub stopped_at: Option<DateTime<Utc>>,
    pub outside: Vec<Outside>,
    pub outside_pushed_on: Option<String>,
    pub phone_enforcing: Option<bool>,
    pub changed_at: Option<DateTime<Utc>>,
    pub changed_by: Option<String>,
    /// The phone has shown an in-set chain at all (sticky): only then is an outside sighting pushed (R-2(c)).
    pub in_set_seen: bool,
    /// The last report's `seen`: in the set or not, and its anchor's name (code QA M-1: "on, but the phone can't").
    /// `None` also when the last report had none - its policy came without a certificate, over the tailnet
    /// (verification n5); the name stays the last one seen.
    pub seen_in_set: Option<bool>,
    pub seen_name: String,
}

impl Default for Row {
    fn default() -> Row {
        Row {
            state: State::Watching,
            window_from: None,
            window_days: Vec::new(),
            window_networks: Vec::new(),
            mobile_seen: false,
            stopped_at: None,
            outside: Vec::new(),
            outside_pushed_on: None,
            phone_enforcing: None,
            changed_at: None,
            changed_by: None,
            in_set_seen: false,
            seen_in_set: None,
            seen_name: String::new(),
        }
    }
}

/// What a report changed: the new row, the outside row to push about (one a day), whether the phone newly says its
/// check is on while the automatic switch-on is stopped (code QA L-3: not adopted, a security event instead), and
/// whether its `enforcing: true` was adopted as `on` (verification L-b: a security event too - a held token could
/// send it).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Next {
    pub row: Row,
    pub push: Option<Outside>,
    pub phone_on_stopped: bool,
    pub adopted: bool,
}

/// Whether a watched phone's window is complete at `now` (R-3).
pub fn window_complete(row: &Row, now: DateTime<Utc>) -> bool {
    let Some(from) = row.window_from else {
        return false;
    };
    let networks = |n: &str| row.window_networks.iter().any(|w| w == n);
    now - from >= WINDOW
        && row.window_days.len() >= WINDOW_DAYS
        && (!row.mobile_seen || (networks("wifi") && networks("mobile")))
}

/// `rows` cut to [OUTSIDE_MAX], oldest first within each kind (verification L-a): `seen` rows go first, then
/// `refused` rows the phone no longer sends that arrived more than [ALARM_KEPT] ago, and only then the oldest
/// `refused` row - so `seen` rows (forged ones, or a ring that is never dropped) can't push a refusal off the page
/// within the week the alarm promises.
fn trim_outside(rows: &mut Vec<Outside>, now: DateTime<Utc>) {
    while rows.len() > OUTSIDE_MAX {
        let old_refusal = |o: &Outside| {
            !o.open
                && crate::display_time::parse_db_utc(&o.received_at)
                    .is_none_or(|at| now - at >= ALARM_KEPT)
        };
        let drop = rows
            .iter()
            .position(|o| o.kind != "refused")
            .or_else(|| rows.iter().position(old_refusal))
            .unwrap_or(0);
        rows.remove(drop);
    }
}

/// The state after a report received at `now` (receive time only - never the phone's `at_ms`).
pub fn next_state(row: &Row, report: &Report, now: DateTime<Utc>) -> Next {
    let mut next = row.clone();
    let stamp = instant(now);
    if report.enforcing.is_some() {
        next.phone_enforcing = report.enforcing;
    }
    next.seen_in_set = report.seen.as_ref().map(|s| s.in_set);
    if let Some(seen) = &report.seen {
        next.seen_name = seen.name.clone();
        next.in_set_seen |= seen.in_set;
    }
    if report.seen.as_ref().is_some_and(|s| s.network == "mobile")
        || report.outside.iter().any(|o| o.network == "mobile")
    {
        next.mobile_seen = true;
    }
    // M-1: a kept row is open while the phone still sends it.
    for kept in next.outside.iter_mut() {
        kept.open = report.outside.iter().any(|o| kept.same(o));
    }
    let new: Vec<Outside> = report
        .outside
        .iter()
        .filter(|o| !row.outside.iter().any(|kept| kept.same(o)))
        .map(|o| Outside {
            received_at: stamp.clone(),
            open: true,
            ..o.clone()
        })
        .collect();
    next.outside.extend(new.iter().cloned());
    trim_outside(&mut next.outside, now);
    let outside_now = !new.is_empty() || report.seen.as_ref().is_some_and(|s| !s.in_set);
    let in_set_now = report.seen.as_ref().is_some_and(|s| s.in_set);
    let mut phone_on_stopped = false;
    let mut adopted = false;

    if row.state == State::Watching {
        if outside_now && next.stopped_at.is_none() {
            next.stopped_at = Some(now);
        }
        if report.enforcing == Some(true) && next.stopped_at.is_none() && in_set_now {
            // The phone stored `true` only from a policy over a connection that passed its pins, and this report's
            // own chain is in the set: adopted (L-3: never while an outside sighting stopped the switch-on), and
            // written as a security line (verification L-b).
            next.state = State::On;
            next.changed_at = Some(now);
            next.changed_by = None;
            adopted = true;
        } else {
            if report.enforcing == Some(true)
                && row.phone_enforcing != Some(true)
                && next.stopped_at.is_some()
            {
                phone_on_stopped = true;
            }
            if in_set_now {
                next.window_from.get_or_insert(now);
                let day = now.date_naive().to_string();
                if !next.window_days.contains(&day) && next.window_days.len() < DAYS_KEPT {
                    next.window_days.push(day);
                }
                let network = report
                    .seen
                    .as_ref()
                    .map(|s| s.network.as_str())
                    .unwrap_or("other");
                if network != "other" && !next.window_networks.iter().any(|n| n == network) {
                    next.window_networks.push(network.to_string());
                }
            }
            if next.stopped_at.is_none() && window_complete(&next, now) {
                next.state = State::On;
                next.changed_at = Some(now);
                next.changed_by = None;
            }
        }
    }

    // A new outside row of a phone that has shown in-set chains (R-2(c)): pushed once a day per phone, never while
    // the parent has the check off. A phone whose server is outside the set altogether is told on its page only.
    let today = now.date_naive().to_string();
    let push = if row.state != State::Off
        && (next.in_set_seen || row.state == State::On)
        && next.outside_pushed_on.as_deref() != Some(today.as_str())
    {
        new.last().cloned()
    } else {
        None
    };
    if push.is_some() {
        next.outside_pushed_on = Some(today);
    }
    Next {
        row: next,
        push,
        phone_on_stopped,
        adopted,
    }
}

/// A parent's one-shot on the device page (R-4).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Action {
    /// "Turn off the certificate check".
    Off,
    /// "Turn it on again": back to `watching`, a new window.
    Watch,
    /// "Turn the check on now".
    OnNow,
}

impl Action {
    /// Its security event (labelled `security.event.<id>`): the way off, or back on.
    pub fn event(self) -> &'static str {
        match self {
            Action::Off => "server_tls_off",
            Action::Watch | Action::OnNow => "server_tls_on",
        }
    }

    /// Its security line's sentence, naming the phone.
    pub fn sentence(self) -> &'static str {
        match self {
            Action::Off => "security.detail.server_tls.off",
            Action::Watch => "security.detail.server_tls.watch",
            Action::OnNow => "security.detail.server_tls.on",
        }
    }

    /// The device page's flash after it ([card]'s `notice`).
    pub fn notice(self) -> &'static str {
        match self {
            Action::Off => "server_tls_off",
            Action::Watch => "server_tls_watch",
            Action::OnNow => "server_tls_on",
        }
    }

    pub fn of(wire: &str) -> Option<Action> {
        Some(match wire {
            "off" => Action::Off,
            "watch" => Action::Watch,
            "on" => Action::OnNow,
            _ => return None,
        })
    }
}

/// The row after the parent's `action` (named `parent`) at `now`.
pub fn by_parent(row: &Row, action: Action, parent: &str, now: DateTime<Utc>) -> Row {
    let mut next = row.clone();
    next.changed_at = Some(now);
    next.changed_by = Some(crate::audit::clip(parent, 100).to_string());
    match action {
        Action::Off => next.state = State::Off,
        Action::OnNow => {
            next.state = State::On;
            next.stopped_at = None;
        }
        Action::Watch => {
            next.state = State::Watching;
            next.window_from = None;
            next.window_days.clear();
            next.window_networks.clear();
            next.stopped_at = None;
        }
    }
    next
}

/// The policy's `server_tls` for `state`: `{}` while watching (no `enforce`: keep), `true` on, `false` off; with
/// `recovery_url` when this server knows it (H-1: the phone shows it in Settings).
pub fn policy_value(state: State, recovery: Option<&str>) -> serde_json::Value {
    let mut value = match state {
        State::Watching => serde_json::json!({}),
        State::On => serde_json::json!({"enforce": true}),
        State::Off => serde_json::json!({"enforce": false}),
    };
    if let (Some(url), Some(object)) = (recovery, value.as_object_mut()) {
        object.insert("recovery_url".to_string(), serde_json::json!(url));
    }
    value
}

/// This host's own tailnet address: the source the kernel picks toward Tailscale's resolver (100.100.100.100,
/// `fd7a:115c:a1e0::53`) on a connected UDP socket - nothing is sent, nothing on the host is changed - and only while
/// a Tailscale interface is up ([tailscale_present]; verification n2: a CGNAT uplink in 100.64.0.0/10 is no
/// tailnet). `None` off the tailnet, in a container without the host's network, and in the tests.
fn own_tailnet_ip() -> Option<IpAddr> {
    if cfg!(test) {
        return None;
    }
    let if_inet6 = std::fs::read_to_string("/proc/net/if_inet6").ok();
    let tailscale0 = std::path::Path::new("/sys/class/net/tailscale0").exists();
    if !tailscale_present(if_inet6.as_deref(), tailscale0) {
        return None;
    }
    [
        ("0.0.0.0:0", "100.100.100.100:53"),
        ("[::]:0", "[fd7a:115c:a1e0::53]:53"),
    ]
    .iter()
    .find_map(|(bind, to)| {
        let socket = std::net::UdpSocket::bind(bind).ok()?;
        socket.connect(to).ok()?;
        let ip = socket.local_addr().ok()?.ip();
        crate::config::is_tailnet_ip(ip).then_some(ip)
    })
}

/// Whether this host has a Tailscale interface: an address in Tailscale's own IPv6 prefix `fd7a:115c:a1e0::/48` in
/// `/proc/net/if_inet6` (Tailscale gives every node one; no ISP hands it out, unlike 100.64.0.0/10), or Tailscale's
/// default interface `tailscale0` (a host with IPv6 off). Both are read-only files.
pub fn tailscale_present(if_inet6: Option<&str>, tailscale0: bool) -> bool {
    tailscale0
        || if_inet6.is_some_and(|text| {
            text.lines().any(|line| {
                line.trim_start()
                    .to_ascii_lowercase()
                    .starts_with("fd7a115ca1e0")
            })
        })
}

/// Which listeners bound to `[::]` also answer IPv4: read from each bound socket's `IPV6_V6ONLY` at start
/// ([note_listener]; verification n3). Unread (the tests, a listener on an IPv4 address) counts as not.
static ADMIN_DUAL_STACK: std::sync::OnceLock<bool> = std::sync::OnceLock::new();
static DEVICE_DUAL_STACK: std::sync::OnceLock<bool> = std::sync::OnceLock::new();

/// Records whether `listener` (the phone listener when `device`, else the admin one) answers IPv4 on an IPv6
/// socket - `IPV6_V6ONLY` off. Called once per listener after its bind; nothing is changed on the socket.
pub fn note_listener(device: bool, listener: &tokio::net::TcpListener) {
    #[cfg(unix)]
    let dual = listener.local_addr().is_ok_and(|addr| addr.is_ipv6())
        && rustix::net::sockopt::ipv6_v6only(listener).is_ok_and(|only| !only);
    #[cfg(not(unix))]
    let dual = false;
    let _ = if device {
        DEVICE_DUAL_STACK.set(dual)
    } else {
        ADMIN_DUAL_STACK.set(dual)
    };
}

/// The listeners' dual-stack answers ([note_listener]) for [recovery_from].
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct DualStack {
    pub device: bool,
    pub admin: bool,
}

impl DualStack {
    fn now() -> DualStack {
        DualStack {
            device: DEVICE_DUAL_STACK.get().copied().unwrap_or(false),
            admin: ADMIN_DUAL_STACK.get().copied().unwrap_or(false),
        }
    }
}

/// The port of a listener bound to `bind` when it surely answers `ip`: bound to `ip` itself, to `0.0.0.0` (an IPv4
/// `ip`) or to `[::]` (an IPv6 `ip`, or an IPv4 one only when `dual_stack`: its socket has `IPV6_V6ONLY` off).
pub fn listener_port(bind: &str, ip: IpAddr, dual_stack: bool) -> Option<u16> {
    let addr: std::net::SocketAddr = bind.trim().parse().ok()?;
    let answers = addr.ip() == ip
        || (addr.ip().is_unspecified()
            && match (addr.is_ipv6(), ip.is_ipv6()) {
                (false, false) | (true, true) => true,
                (true, false) => dual_stack,
                (false, true) => false,
            });
    answers.then_some(addr.port())
}

/// The plain-http tailnet address a phone refusing this server's certificate is pointed at (code QA H-1), in this
/// order: `PHONE_RECOVERY_URL`; the setup QR's own address when it is plain http to a Tailscale IP; this host's
/// tailnet address `own` on the phone listener, or on the admin listener while it serves the phones' API, when that
/// listener surely answers it (`dual`). `None` when the server can't tell (Caddy on another host, a loopback-only
/// listener behind a proxy, no Tailscale interface): the page then says where to find it (DEPLOY.md, "Tailnet
/// recovery address"). Never anything but `config::check_server_url`'s `TailnetHttp`.
pub fn recovery_from(
    configured: Option<&str>,
    qr: &str,
    own: Option<IpAddr>,
    net: &crate::net::NetConfig,
    dual: DualStack,
) -> Option<String> {
    use crate::config::{ServerUrl, check_server_url};
    let tailnet = |url: &str| matches!(check_server_url(url), ServerUrl::TailnetHttp(_));
    if let Some(url) = configured.filter(|url| tailnet(url)) {
        return Some(url.trim().to_string());
    }
    if tailnet(qr) {
        return Some(qr.trim().to_string());
    }
    let ip = own?;
    let port = net
        .device_bind_addr
        .as_deref()
        .and_then(|bind| listener_port(bind, ip, dual.device))
        .or_else(|| {
            net.admin_device_api
                .then(|| listener_port(&net.bind_addr, ip, dual.admin))
                .flatten()
        })?;
    let url = match ip {
        IpAddr::V4(_) => format!("http://{ip}:{port}"),
        IpAddr::V6(_) => format!("http://[{ip}]:{port}"),
    };
    tailnet(&url).then_some(url)
}

/// [recovery_from] for this server now (Phone Setup's saved address read; a read error counts as none saved).
pub async fn recovery_url(state: &AppState) -> Option<String> {
    let settings = sqlx::query_as::<_, crate::models::ProvisioningSettings>(
        "SELECT * FROM provisioning_settings WHERE id = 1",
    )
    .fetch_optional(&state.db)
    .await
    .map_err(|err| tracing::warn!(%err, "can't read Phone Setup for the recovery address"))
    .ok()
    .flatten()
    .unwrap_or_default();
    let qr = crate::handlers::provisioning::server_url(&settings, &state.config);
    recovery_from(
        state.config.phone_recovery_url.as_deref(),
        qr,
        own_tailnet_ip(),
        &state.net,
        DualStack::now(),
    )
}

fn instant(t: DateTime<Utc>) -> String {
    t.to_rfc3339_opts(SecondsFormat::Millis, true)
}

fn parse(t: Option<String>) -> Option<DateTime<Utc>> {
    t.as_deref().and_then(crate::display_time::parse_db_utc)
}

type Stored = (
    String,
    Option<String>,
    String,
    String,
    bool,
    Option<String>,
    String,
    Option<String>,
    Option<bool>,
    Option<String>,
    Option<String>,
    bool,
    Option<bool>,
    String,
);

/// The phone's row ([Row::default] without one).
pub async fn load<'e, E: sqlx::SqliteExecutor<'e>>(
    db: E,
    device_id: i64,
) -> Result<Row, sqlx::Error> {
    let stored: Option<Stored> = sqlx::query_as(
        "SELECT state, window_from, window_days, window_networks, mobile_seen, stopped_at, outside_json, \
         outside_pushed_on, phone_enforcing, changed_at, changed_by, in_set_seen, seen_in_set, seen_name \
         FROM device_server_tls WHERE device_id = ?",
    )
    .bind(device_id)
    .fetch_optional(db)
    .await?;
    let Some((
        state,
        window_from,
        days,
        networks,
        mobile_seen,
        stopped_at,
        outside,
        outside_pushed_on,
        phone_enforcing,
        changed_at,
        changed_by,
        in_set_seen,
        seen_in_set,
        seen_name,
    )) = stored
    else {
        return Ok(Row::default());
    };
    Ok(Row {
        state: State::of(&state),
        window_from: parse(window_from),
        window_days: serde_json::from_str(&days).unwrap_or_default(),
        window_networks: serde_json::from_str(&networks).unwrap_or_default(),
        mobile_seen,
        stopped_at: parse(stopped_at),
        outside: serde_json::from_str(&outside).unwrap_or_default(),
        outside_pushed_on,
        phone_enforcing,
        changed_at: parse(changed_at),
        changed_by,
        in_set_seen,
        seen_in_set,
        seen_name,
    })
}

/// Writes the phone's row.
pub async fn store<'e, E: sqlx::SqliteExecutor<'e>>(
    db: E,
    device_id: i64,
    row: &Row,
) -> Result<(), sqlx::Error> {
    sqlx::query(
        "INSERT INTO device_server_tls (device_id, state, window_from, window_days, window_networks, mobile_seen, \
         stopped_at, outside_json, outside_pushed_on, phone_enforcing, changed_at, changed_by, in_set_seen, \
         seen_in_set, seen_name) \
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) \
         ON CONFLICT(device_id) DO UPDATE SET state = excluded.state, window_from = excluded.window_from, \
         window_days = excluded.window_days, window_networks = excluded.window_networks, \
         mobile_seen = excluded.mobile_seen, stopped_at = excluded.stopped_at, outside_json = excluded.outside_json, \
         outside_pushed_on = excluded.outside_pushed_on, phone_enforcing = excluded.phone_enforcing, \
         changed_at = excluded.changed_at, changed_by = excluded.changed_by, in_set_seen = excluded.in_set_seen, \
         seen_in_set = excluded.seen_in_set, seen_name = excluded.seen_name",
    )
    .bind(device_id)
    .bind(row.state.wire())
    .bind(row.window_from.map(instant))
    .bind(serde_json::to_string(&row.window_days).expect("strings serialize"))
    .bind(serde_json::to_string(&row.window_networks).expect("strings serialize"))
    .bind(row.mobile_seen)
    .bind(row.stopped_at.map(instant))
    .bind(serde_json::to_string(&row.outside).expect("strings serialize"))
    .bind(&row.outside_pushed_on)
    .bind(row.phone_enforcing)
    .bind(row.changed_at.map(instant))
    .bind(&row.changed_by)
    .bind(row.in_set_seen)
    .bind(row.seen_in_set)
    .bind(&row.seen_name)
    .execute(db)
    .await?;
    Ok(())
}

/// The policy's `server_tls` for a phone whose last report has `capabilities`: `None` (absent) without
/// [CAPABILITY]. A read error fails the policy (the phone keeps its cache), never a made-up state.
pub async fn policy(
    state: &AppState,
    device_id: i64,
    capabilities: &[String],
) -> Result<Option<serde_json::Value>, sqlx::Error> {
    if !capabilities.iter().any(|c| c == CAPABILITY) {
        return Ok(None);
    }
    let row = load(&state.db, device_id).await?;
    let recovery = recovery_url(state).await;
    Ok(Some(policy_value(row.state, recovery.as_deref())))
}

/// A status report's `server_tls` (raw), received now: the new state stored in one transaction, then the outside
/// push (once a day). A report without it (an older launcher) changes nothing; a failure is logged.
pub async fn intake(state: &AppState, device_id: i64, raw: Option<&serde_json::Value>) {
    let Some(report) = raw.and_then(sanitize) else {
        return;
    };
    let now = Utc::now();
    let result = async {
        let mut tx = state.db.begin().await?;
        let row = load(&mut *tx, device_id).await?;
        let next = next_state(&row, &report, now);
        if next.row != row {
            store(&mut *tx, device_id, &next.row).await?;
        }
        tx.commit().await?;
        Ok::<_, sqlx::Error>((row.state, next))
    };
    match result.await {
        Ok((before, next)) => {
            if before != next.row.state {
                tracing::info!(
                    device_id,
                    from = before.wire(),
                    to = next.row.state.wire(),
                    "the certificate check moved"
                );
            }
            if next.push.is_some() || next.phone_on_stopped || next.adopted {
                let phone = crate::parent_events::Phone::load(&state.db, device_id).await;
                if let (Some(outside), Some(phone)) = (&next.push, &phone) {
                    crate::parent_events::raise_logged(
                        state,
                        &crate::parent_events::server_tls_seen(phone, outside, now),
                    )
                    .await;
                }
                // L-3: the phone says its check is on while an outside sighting stopped the switch-on - not adopted;
                // verification L-b: adopted otherwise. Either way written where the parents look (a held token
                // could send it).
                let line = if next.phone_on_stopped {
                    Some(("server_tls_phone_on", "security.detail.server_tls.phone_on"))
                } else if next.adopted {
                    Some(("server_tls_on", "security.detail.server_tls.adopted"))
                } else {
                    None
                };
                if let Some((event, sentence)) = line {
                    let name = phone.map(|p| p.name).unwrap_or_default();
                    crate::security::record_security_event(
                        &state.db,
                        event,
                        None,
                        None,
                        Some(
                            crate::security::Detail::words(sentence, &[("phone", name.as_str())])
                                .with_facts(format!("device {device_id}")),
                        ),
                    )
                    .await;
                }
            }
        }
        Err(err) => {
            tracing::error!(device_id, %err, "can't store the certificate check's report")
        }
    }
}

/// The device page's group (Advanced › Certificate check): the state as one line (and what the phone itself reports,
/// code QA M-1), the last refusal as an alarm, the recovery address, the buttons that apply.
pub struct Card {
    pub line: String,
    /// A second sentence: the phone hasn't taken the state, or says its check is on while stopped.
    pub detail: Option<String>,
    pub alarm: Option<String>,
    /// The plain-http tailnet address to point a refusing phone at, or where to find it (H-1).
    pub recovery: String,
    /// "Turn off the certificate check" (watching or on).
    pub can_turn_off: bool,
    /// "Turn it on again" (off).
    pub can_watch: bool,
    /// "Turn the check on now" (watching after an outside sighting or while the phone says its check is on, or off).
    pub can_turn_on: bool,
    /// The flash after a button: the text, and whether it is a refusal.
    pub notice: Option<(String, bool)>,
}

fn network_word(zone: &Zone, network: &str) -> &'static str {
    t(
        zone.lang(),
        match network {
            "wifi" => "server_tls.network.wifi",
            "mobile" => "server_tls.network.mobile",
            _ => "server_tls.network.elsewhere",
        },
    )
}

/// When an outside row happened, as words (L-5): "at 14:02" from the phone's own time when it lies within a week
/// before its receive time (or minutes after it), else "reported at 18:30" from the receive time.
fn when_words(row: &Outside, zone: &Zone, now: DateTime<Utc>) -> String {
    let received = crate::display_time::parse_db_utc(&row.received_at).unwrap_or(now);
    let phone = DateTime::<Utc>::from_timestamp_millis(row.at_ms)
        .filter(|at| *at >= received - PHONE_TIME_BACK && *at <= received + PHONE_TIME_AHEAD);
    match phone {
        Some(at) => t_params(
            zone.lang(),
            "server_tls.when.at",
            &[("time", &zone.since(at, now))],
        ),
        None => t_params(
            zone.lang(),
            "server_tls.when.reported",
            &[("time", &zone.since(received, now))],
        ),
    }
}

/// The group for `row`, in `zone`'s language and time, with [recovery_url]'s answer.
pub fn card(
    row: &Row,
    zone: &Zone,
    now: DateTime<Utc>,
    notice: Option<&str>,
    recovery: Option<&str>,
) -> Card {
    let lang = zone.lang();
    let refused = row.outside.iter().rev().find(|o| o.kind == "refused");
    let refusing = refused.is_some_and(|o| o.open);
    // The chain the phone can't pin: its last `seen` outside the set, or a `seen` row it still sends.
    let open_seen = row
        .outside
        .iter()
        .rev()
        .find(|o| o.kind == "seen" && o.open);
    // Without a `seen` (the tailnet path) an outside row it still sends counts; a phone whose own last chain is in
    // the set takes `on` at its next sync.
    let cannot_pin =
        row.seen_in_set == Some(false) || (row.seen_in_set.is_none() && open_seen.is_some());
    let issuer_outside = if row.seen_in_set == Some(false) && !row.seen_name.is_empty() {
        row.seen_name.clone()
    } else {
        open_seen.map(|o| o.name.clone()).unwrap_or_default()
    };
    // Verification n5: the phone's last report came without a certificate - its policy over the tailnet, where
    // nothing is checked; the watch counts, and the phone takes `on`, only at a sync over https.
    let no_certificate = row.phone_enforcing.is_some() && row.seen_in_set.is_none();
    let no_certificate_detail = || t(lang, "server_tls.no_certificate").to_string();
    let (line, detail) = match row.state {
        State::On => match row.phone_enforcing {
            Some(true) => (t(lang, "server_tls.on").to_string(), None),
            _ if cannot_pin => (
                t_params(lang, "server_tls.on_cannot", &[("issuer", &issuer_outside)]),
                None,
            ),
            _ if no_certificate => (
                t(lang, "server_tls.on_phone_off").to_string(),
                Some(no_certificate_detail()),
            ),
            _ => (t(lang, "server_tls.on_pending").to_string(), None),
        },
        State::Off => {
            let line = t_params(
                lang,
                "server_tls.off_since",
                &[
                    (
                        "since",
                        &row.changed_at
                            .map(|at| zone.since(at, now))
                            .unwrap_or_default(),
                    ),
                    (
                        "parent",
                        row.changed_by
                            .as_deref()
                            .unwrap_or(t(lang, "server_tls.a_parent")),
                    ),
                ],
            );
            let detail = (row.phone_enforcing == Some(true))
                .then(|| t(lang, "server_tls.off_pending").to_string());
            (line, detail)
        }
        State::Watching => {
            let line = match (row.stopped_at, row.outside.last()) {
                (Some(_), Some(last)) => t_params(
                    lang,
                    "server_tls.stopped",
                    &[
                        ("issuer", &last.name),
                        ("network", network_word(zone, &last.network)),
                        ("when", &when_words(last, zone, now)),
                    ],
                ),
                (Some(_), None) => t(lang, "server_tls.stopped_unnamed").to_string(),
                _ => t_params(
                    lang,
                    "server_tls.watching",
                    &[(
                        "from",
                        &zone.until(row.window_from.unwrap_or(now) + WINDOW, now),
                    )],
                ),
            };
            // n5: what the phone says, stopped or not (adopted only with an in-set chain, L-3).
            let detail = if row.phone_enforcing == Some(true) {
                Some(t(lang, "server_tls.phone_says_on").to_string())
            } else if no_certificate && row.stopped_at.is_none() {
                Some(no_certificate_detail())
            } else {
                None
            };
            (line, detail)
        }
    };
    // M-1: kept while the phone still sends it (it hasn't reached this server through a working connection since),
    // and a week after it arrived - in every state, `off` included.
    let alarm = refused
        .filter(|o| {
            o.open
                || crate::display_time::parse_db_utc(&o.received_at)
                    .is_some_and(|at| now - at < ALARM_KEPT)
        })
        .map(|o| {
            let text = t_params(
                lang,
                "server_tls.refused",
                &[
                    ("issuer", &o.name),
                    ("network", network_word(zone, &o.network)),
                    ("when", &when_words(o, zone, now)),
                ],
            );
            if o.open {
                text
            } else {
                format!("{text} {}", t(lang, "server_tls.refused_closed"))
            }
        });
    let recovery = match recovery {
        Some(url) => t_params(lang, "server_tls.recovery", &[("url", url)]),
        None => t(lang, "server_tls.recovery_unknown").to_string(),
    };
    Card {
        line,
        detail,
        alarm,
        recovery,
        can_turn_off: row.state != State::Off,
        can_watch: row.state == State::Off,
        // "Turn the check on now" also for a watched phone that says its check is on ("phone_says_on" names it).
        can_turn_on: row.state == State::Off
            || (row.state == State::Watching
                && (row.stopped_at.is_some() || row.phone_enforcing == Some(true))),
        notice: notice.and_then(|code| {
            Some(match code {
                // M-1: never "the phone hears it now" for a phone that refuses this server.
                "server_tls_off" if refusing && row.phone_enforcing == Some(true) => {
                    (t(lang, "server_tls.notice.off_refusing").to_string(), false)
                }
                "server_tls_off" => (t(lang, "server_tls.notice.off").to_string(), false),
                "server_tls_watch" => (t(lang, "server_tls.notice.watch").to_string(), false),
                "server_tls_on" if cannot_pin && row.phone_enforcing != Some(true) => (
                    t_params(
                        lang,
                        "server_tls.notice.on_cannot",
                        &[("issuer", &issuer_outside)],
                    ),
                    false,
                ),
                "server_tls_on" => (t(lang, "server_tls.notice.on").to_string(), false),
                "server_tls_unchanged" => {
                    (t(lang, "server_tls.notice.unchanged").to_string(), true)
                }
                "server_tls_unconfirmed" => {
                    (t(lang, "devices.access.unconfirmed").to_string(), true)
                }
                _ => return None,
            })
        }),
    }
}

/// Whether the phone's last report has [CAPABILITY] (the group shows only then).
pub fn capable(capabilities_json: Option<&str>) -> bool {
    capabilities_json
        .and_then(|json| serde_json::from_str::<Vec<String>>(json).ok())
        .is_some_and(|caps| caps.iter().any(|c| c == CAPABILITY))
}

#[cfg(test)]
mod tests {
    use super::*;
    use chrono::TimeZone;

    fn at(day: u32, hour: u32) -> DateTime<Utc> {
        Utc.with_ymd_and_hms(2026, 11, day, hour, 0, 0).unwrap()
    }

    fn seen(in_set: bool, network: &str) -> Report {
        Report {
            enforcing: Some(false),
            seen: Some(Seen {
                in_set,
                name: if in_set {
                    "ISRG Root X1"
                } else {
                    "emSign Root CA - G1"
                }
                .into(),
                pin: "C5+lpZ7tcVwmwQIMcRtPbsQtWLABXhQzejna0wHFr8M=".into(),
                network: network.into(),
                at_ms: 1,
            }),
            outside: Vec::new(),
        }
    }

    fn outside(kind: &str, network: &str, at_ms: i64) -> Outside {
        Outside {
            kind: kind.into(),
            host: "vibb.example.org".into(),
            name: "emSign Root CA - G1".into(),
            pin: "N2oacIKlk9zMINVh0Rnpq40w8RzDIdCjf6QfDfKE4Bw=".into(),
            network: network.into(),
            at_ms,
            received_at: String::new(),
            open: false,
        }
    }

    /// Reports at each `(day, hour, network)`, from `row`.
    fn run(mut row: Row, reports: &[(u32, u32, &str)]) -> Row {
        for (day, hour, network) in reports {
            row = next_state(&row, &seen(true, network), at(*day, *hour)).row;
        }
        row
    }

    #[test]
    fn the_window_is_72_hours_of_receive_time_on_three_days() {
        // Wi-Fi only: 72 hours on 4 days switch on; 71 hours don't.
        let row = run(
            Row::default(),
            &[(1, 12, "wifi"), (2, 12, "wifi"), (3, 12, "wifi")],
        );
        assert_eq!(row.state, State::Watching);
        assert_eq!(
            next_state(&row, &seen(true, "wifi"), at(4, 11)).row.state,
            State::Watching
        );
        let on = next_state(&row, &seen(true, "wifi"), at(4, 12)).row;
        assert_eq!(on.state, State::On);
        assert_eq!(on.changed_by, None);
        // 72 hours, but on two days only (a report at 00:00 and one at 23:00 three days later on two days isn't
        // possible; two reports far apart are): not on until a third day has a sighting.
        let two = run(Row::default(), &[(1, 0, "wifi"), (5, 0, "wifi")]);
        assert_eq!(two.state, State::Watching);
        assert_eq!(two.window_days.len(), 2);
        assert_eq!(run(two, &[(5, 1, "wifi"), (6, 0, "wifi")]).state, State::On);
        // The phone's own at_ms counts for nothing: one report far in the "future" is one sighting now.
        let mut early = seen(true, "wifi");
        early.seen.as_mut().unwrap().at_ms = at(30, 0).timestamp_millis();
        assert_eq!(
            next_state(&Row::default(), &early, at(1, 12)).row.state,
            State::Watching
        );
    }

    #[test]
    fn a_phone_that_reported_mobile_needs_both_networks() {
        let wifi_days = [
            (1, 12, "wifi"),
            (2, 12, "wifi"),
            (3, 12, "wifi"),
            (4, 12, "wifi"),
        ];
        let mobile_once = Row {
            mobile_seen: true,
            ..Row::default()
        };
        assert_eq!(run(mobile_once.clone(), &wifi_days).state, State::Watching);
        assert_eq!(
            run(run(mobile_once, &wifi_days), &[(4, 13, "mobile")]).state,
            State::On
        );
        // A sighting on mobile marks it.
        let row = run(Row::default(), &[(1, 12, "mobile")]);
        assert!(row.mobile_seen);
        assert_eq!(row.window_networks, ["mobile"]);
    }

    #[test]
    fn an_outside_sighting_stops_the_switch_on_and_is_pushed_once_a_day() {
        let row = run(Row::default(), &[(1, 12, "wifi"), (2, 12, "wifi")]);
        let mut report = seen(true, "wifi");
        report.outside = vec![outside("seen", "mobile", 5)];
        let next = next_state(&row, &report, at(2, 13));
        assert_eq!(next.row.stopped_at, Some(at(2, 13)));
        assert_eq!(next.push.as_ref().map(|o| o.at_ms), Some(5));
        assert_eq!(next.row.outside.len(), 1);
        assert_eq!(next.row.outside[0].received_at, instant(at(2, 13)));
        // The same row again: not new, no push; another one the same day: kept, no push; the next day: pushed.
        let again = next_state(&next.row, &report, at(2, 14));
        assert_eq!(again.push, None);
        assert_eq!(again.row.outside.len(), 1);
        report.outside.push(outside("seen", "wifi", 6));
        let same_day = next_state(&again.row, &report, at(2, 15));
        assert_eq!(same_day.push, None);
        assert_eq!(same_day.row.outside.len(), 2);
        report.outside.push(outside("refused", "wifi", 7));
        let next_day = next_state(&same_day.row, &report, at(3, 9));
        assert_eq!(next_day.push.map(|o| o.kind), Some("refused".to_string()));
        // Stopped: never switched on by itself, the window doesn't restart either.
        let later = run(
            next_day.row.clone(),
            &[(4, 12, "wifi"), (5, 12, "mobile"), (6, 12, "wifi")],
        );
        assert_eq!(later.state, State::Watching);
        assert_eq!(later.window_from, Some(at(1, 12)));
        // A chain outside the set in `seen` alone stops it too.
        let stopped = next_state(&Row::default(), &seen(false, "wifi"), at(1, 12)).row;
        assert!(stopped.stopped_at.is_some());
        assert_eq!(stopped.window_from, None);
        // At most 8 rows kept, the newest.
        let many = Report {
            outside: (0..20).map(|i| outside("seen", "wifi", i)).collect(),
            ..Report::default()
        };
        let kept = next_state(&Row::default(), &many, at(1, 12)).row.outside;
        assert_eq!(kept.len(), OUTSIDE_MAX);
        assert_eq!(kept.last().unwrap().at_ms, 19);
    }

    #[test]
    fn on_is_sticky_and_a_phones_enforcing_is_adopted() {
        let mut row = Row::default();
        let mut enforcing = seen(true, "wifi");
        enforcing.enforcing = Some(true);
        row = next_state(&row, &enforcing, at(1, 12)).row;
        assert_eq!(row.state, State::On);
        assert_eq!(row.phone_enforcing, Some(true));
        let mut refused = seen(false, "wifi");
        refused.outside = vec![outside("refused", "wifi", 9)];
        let next = next_state(&row, &refused, at(1, 13));
        assert_eq!(next.row.state, State::On);
        assert!(next.push.is_some());
        // Off: an enforcing report doesn't move it, and nothing is pushed.
        let off = by_parent(&row, Action::Off, "Mamma", at(1, 14));
        let mut more = refused.clone();
        more.enforcing = Some(true);
        more.outside.push(outside("refused", "mobile", 10));
        let still = next_state(&off, &more, at(2, 1));
        assert_eq!(still.row.state, State::Off);
        assert_eq!(still.push, None);
    }

    #[test]
    fn the_parents_buttons() {
        let watched = run(Row::default(), &[(1, 12, "wifi"), (2, 12, "wifi")]);
        let off = by_parent(&watched, Action::Off, "Mamma", at(2, 13));
        assert_eq!(
            (off.state, off.changed_by.as_deref()),
            (State::Off, Some("Mamma"))
        );
        let again = by_parent(&off, Action::Watch, "Pappa", at(3, 9));
        assert_eq!(again.state, State::Watching);
        assert_eq!((again.window_from, again.window_days.len()), (None, 0));
        let mut stopped = again.clone();
        stopped.stopped_at = Some(at(3, 10));
        let on = by_parent(&stopped, Action::OnNow, "Pappa", at(3, 11));
        assert_eq!((on.state, on.stopped_at), (State::On, None));
        assert_eq!(Action::of("nonsense"), None);
    }

    #[test]
    fn watching_sends_no_enforce() {
        assert_eq!(policy_value(State::Watching, None), serde_json::json!({}));
        assert_eq!(
            policy_value(State::On, None),
            serde_json::json!({"enforce": true})
        );
        assert_eq!(
            policy_value(State::Off, None),
            serde_json::json!({"enforce": false})
        );
        assert_eq!(
            policy_value(State::Watching, Some("http://100.101.102.103:3101")),
            serde_json::json!({"recovery_url": "http://100.101.102.103:3101"})
        );
    }

    fn net(vars: &[(&str, &str)]) -> crate::net::NetConfig {
        crate::net::NetConfig::from_vars(
            &vars
                .iter()
                .map(|(k, v)| (k.to_string(), v.to_string()))
                .collect(),
        )
    }

    /// Code QA H-1: the recovery address from config, the QR, or this host's tailnet address on a listener that
    /// answers it - never anything but plain http to a Tailscale IP.
    #[test]
    fn the_recovery_address() {
        let own: Option<IpAddr> = Some("100.101.102.103".parse().unwrap());
        let none = DualStack::default();
        let open = net(&[
            ("BIND_ADDR", "0.0.0.0:3100"),
            ("DEVICE_BIND_ADDR", "0.0.0.0:3101"),
        ]);
        // The user's install: both listeners on 0.0.0.0, the box on the tailnet.
        assert_eq!(
            recovery_from(None, "https://phones.example.com", own, &open, none).as_deref(),
            Some("http://100.101.102.103:3101")
        );
        // Configured wins; a configured https one is never taken (config refuses it too).
        assert_eq!(
            recovery_from(Some("http://100.64.0.9:8080"), "", own, &open, none).as_deref(),
            Some("http://100.64.0.9:8080")
        );
        assert_eq!(
            recovery_from(Some("https://x.ts.net"), "", None, &open, none),
            None
        );
        // The QR's own address when it is the tailnet one.
        assert_eq!(
            recovery_from(None, "http://100.70.1.2:3101", None, &open, none).as_deref(),
            Some("http://100.70.1.2:3101")
        );
        // Loopback listeners (behind a proxy on the box): the server can't tell.
        let loopback = net(&[("DEVICE_BIND_ADDR", "127.0.0.1:3101")]);
        assert_eq!(
            recovery_from(None, "https://phones.example.com", own, &loopback, none),
            None
        );
        // No phone listener: the admin listener while it serves the phones' API, bound to the tailnet IP itself.
        let admin = net(&[("BIND_ADDR", "100.101.102.103:3100")]);
        assert_eq!(
            recovery_from(None, "", own, &admin, none).as_deref(),
            Some("http://100.101.102.103:3100")
        );
        let admin_off = net(&[
            ("BIND_ADDR", "0.0.0.0:3100"),
            ("ADMIN_DEVICE_API", "off"),
            ("DEVICE_BIND_ADDR", "127.0.0.1:3101"),
        ]);
        assert_eq!(recovery_from(None, "", own, &admin_off, none), None);
        // IPv6: bracketed, and only `[::]` answers it.
        let own6: Option<IpAddr> = Some("fd7a:115c:a1e0::1".parse().unwrap());
        assert_eq!(recovery_from(None, "", own6, &open, none), None);
        let open6 = net(&[("DEVICE_BIND_ADDR", "[::]:3101")]);
        assert_eq!(
            recovery_from(None, "", own6, &open6, none).as_deref(),
            Some("http://[fd7a:115c:a1e0::1]:3101")
        );
        // Off the tailnet: nothing.
        assert_eq!(
            recovery_from(None, "https://phones.example.com", None, &open, none),
            None
        );
        // Verification n3: `[::]` answers an IPv4 address only when its socket said `IPV6_V6ONLY` off.
        let v6_only = net(&[
            ("DEVICE_BIND_ADDR", "[::]:3101"),
            ("ADMIN_DEVICE_API", "off"),
        ]);
        assert_eq!(recovery_from(None, "", own, &v6_only, none), None);
        let dual = DualStack {
            device: true,
            admin: false,
        };
        assert_eq!(
            recovery_from(None, "", own, &v6_only, dual).as_deref(),
            Some("http://100.101.102.103:3101")
        );
        assert_eq!(
            listener_port("0.0.0.0:3101", "fd7a:115c:a1e0::1".parse().unwrap(), true),
            None
        );
        assert_eq!(own_tailnet_ip(), None, "never read in the tests");
    }

    /// Verification n2: the self-read address counts only with a Tailscale interface - Tailscale's own IPv6 prefix
    /// on an interface, or `tailscale0` - never for a 100.64.0.0/10 CGNAT uplink alone.
    #[test]
    fn a_tailscale_interface_is_needed() {
        let tailnet = "fd7a115ca1e000000000000000000001 05 80 00 80 tailscale0\n\
                       00000000000000000000000000000001 01 80 10 80       lo\n";
        let plain = "fe80000000000000021122fffe334455 02 40 20 80     eth0\n\
                     00000000000000000000000000000001 01 80 10 80       lo\n";
        assert!(tailscale_present(Some(tailnet), false));
        assert!(!tailscale_present(Some(plain), false));
        assert!(!tailscale_present(None, false));
        assert!(tailscale_present(None, true));
    }

    /// Code QA L-2, L-3: no push for a phone that never showed an in-set chain; a phone's `enforcing: true` while
    /// the switch-on is stopped is not adopted, and told once.
    #[test]
    fn pushes_and_adoption_follow_the_sightings() {
        let mut report = seen(false, "wifi");
        report.outside = vec![outside("seen", "wifi", 5)];
        let never_in_set = next_state(&Row::default(), &report, at(1, 12));
        assert_eq!(never_in_set.push, None);
        assert!(never_in_set.row.stopped_at.is_some());
        assert!(!never_in_set.row.in_set_seen);
        // The phone says its check is on while stopped: watched still, told once.
        let mut enforcing = seen(true, "wifi");
        enforcing.enforcing = Some(true);
        enforcing.outside = report.outside.clone();
        let told = next_state(&never_in_set.row, &enforcing, at(1, 13));
        assert_eq!(told.row.state, State::Watching);
        assert!(told.phone_on_stopped);
        assert!(!next_state(&told.row, &enforcing, at(1, 14)).phone_on_stopped);
        // An enforcing phone without an in-set chain in its report (the tailnet path) isn't adopted either.
        let mut tailnet = Report {
            enforcing: Some(true),
            ..Report::default()
        };
        assert_eq!(
            next_state(&Row::default(), &tailnet, at(1, 12)).row.state,
            State::Watching
        );
        tailnet.seen = seen(true, "wifi").seen;
        assert_eq!(
            next_state(&Row::default(), &tailnet, at(1, 12)).row.state,
            State::On
        );
    }

    /// Code QA L-4: no control or bidi characters reach a title.
    #[test]
    fn hidden_characters_are_dropped() {
        let value = serde_json::json!({
            "outside": [{"kind": "refused", "host": "a\u{202E}b\nc\u{2028}", "name": " ISRG\u{2066} Root\u{0007} X1\u{200B}\u{2029} ",
                         "pin": "C5+lpZ7tcVwmwQIMcRtPbsQtWLABXhQzejna0wHFr8M=", "network": "wifi", "at_ms": 1}]
        });
        let row = &sanitize(&value).unwrap().outside[0];
        assert_eq!(
            (row.host.as_str(), row.name.as_str()),
            ("abc", "ISRG Root X1")
        );
    }

    /// Code QA M-1, L-5: the card says what the phone reports, keeps the refusal while the phone still sends it, and
    /// words a time the phone's clock can't vouch for as "reported at".
    #[test]
    fn the_card_follows_the_phone() {
        let zone = Zone::from_setting("Etc/UTC");
        let now = at(3, 12);
        let mut refused = outside("refused", "wifi", at(3, 10).timestamp_millis());
        refused.received_at = instant(at(3, 11));
        refused.open = true;
        let enforcing_off = Row {
            state: State::Off,
            phone_enforcing: Some(true),
            changed_at: Some(at(3, 11)),
            changed_by: Some("Mamma".into()),
            outside: vec![refused.clone()],
            ..Row::default()
        };
        let card = card(&enforcing_off, &zone, now, Some("server_tls_off"), None);
        assert!(card.detail.is_some(), "the phone still checks");
        let alarm = card.alarm.unwrap();
        assert!(alarm.contains("at 10:00"), "{alarm}");
        assert!(!card.notice.unwrap().0.contains("hears it now"));
        assert!(card.recovery.contains("DEPLOY.md"), "{}", card.recovery);
        // Dropped by the phone since: still shown a week, said so; then gone.
        let mut closed = enforcing_off.clone();
        closed.phone_enforcing = Some(false);
        closed.outside[0].open = false;
        let shown = super::card(
            &closed,
            &zone,
            now,
            None,
            Some("http://100.101.102.103:3101"),
        );
        assert!(
            shown
                .alarm
                .unwrap()
                .contains(t(crate::i18n::Lang::En, "server_tls.refused_closed"))
        );
        assert!(shown.detail.is_none());
        assert!(shown.recovery.contains("http://100.101.102.103:3101"));
        assert!(
            super::card(&closed, &zone, at(11, 12), None, None)
                .alarm
                .is_none()
        );
        // A phone time far from its receive time: the receive time, "reported at".
        let mut skewed = refused.clone();
        skewed.at_ms = at(20, 0).timestamp_millis();
        assert!(when_words(&skewed, &zone, now).starts_with("reported at"));
        // On, and the phone's chain is outside the set: said, never "only Let's Encrypt or ZeroSSL".
        let cannot = Row {
            state: State::On,
            phone_enforcing: Some(false),
            seen_in_set: Some(false),
            seen_name: "GTS Root R4".into(),
            ..Row::default()
        };
        let line = super::card(&cannot, &zone, now, None, None).line;
        assert!(line.contains("GTS Root R4"), "{line}");
        assert_ne!(line, t(crate::i18n::Lang::En, "server_tls.on"));
        // Its own chain in the set, an old outside row still sent (a hostile network once): it will take it.
        let mut pending = cannot.clone();
        pending.seen_in_set = Some(true);
        pending.outside = vec![Outside {
            open: true,
            ..outside("seen", "wifi", 1)
        }];
        assert_eq!(
            super::card(&pending, &zone, now, None, None).line,
            t(crate::i18n::Lang::En, "server_tls.on_pending")
        );
        // A stopped phone without a row of its own names no issuer.
        let stopped = Row {
            stopped_at: Some(now),
            ..Row::default()
        };
        assert_eq!(
            super::card(&stopped, &zone, now, None, None).line,
            t(crate::i18n::Lang::En, "server_tls.stopped_unnamed")
        );
    }

    /// Verification L-a: 8 `seen` rows after a refusal (forged, or a ring that is never dropped) never push it off
    /// the page within its week; past the week, and no longer sent, it is the first to go.
    #[test]
    fn seen_rows_never_push_a_refusal_off() {
        let zone = Zone::from_setting("Etc/UTC");
        let mut report = seen(true, "wifi");
        report.outside = vec![outside("refused", "wifi", at(1, 11).timestamp_millis())];
        let refused = next_state(&Row::default(), &report, at(1, 12)).row;
        let mut flood = seen(true, "wifi");
        flood.outside = (0..8).map(|i| outside("seen", "mobile", 100 + i)).collect();
        let flooded = next_state(&refused, &flood, at(1, 13)).row;
        assert_eq!(flooded.outside.len(), OUTSIDE_MAX);
        assert_eq!(flooded.outside[0].kind, "refused");
        assert_eq!(
            flooded.outside.iter().filter(|o| o.kind == "seen").count(),
            OUTSIDE_MAX - 1
        );
        assert!(card(&flooded, &zone, at(7, 12), None, None).alarm.is_some());
        // Again a day later: still there, the oldest `seen` rows go.
        flood.outside = (0..8).map(|i| outside("seen", "wifi", 200 + i)).collect();
        let again = next_state(&flooded, &flood, at(2, 13)).row;
        assert_eq!(again.outside[0].kind, "refused");
        assert!(again.outside.iter().all(|o| o.at_ms != 100));
        // Refusals only: the one past its week and no longer sent goes first.
        let mut rows: Vec<Outside> = (0..OUTSIDE_MAX as i64)
            .map(|i| Outside {
                received_at: instant(at(10, 12)),
                open: true,
                ..outside("refused", "wifi", 300 + i)
            })
            .collect();
        rows.insert(
            3,
            Outside {
                received_at: instant(at(1, 12)),
                ..outside("refused", "wifi", 1)
            },
        );
        trim_outside(&mut rows, at(10, 13));
        assert_eq!(rows.len(), OUTSIDE_MAX);
        assert!(rows.iter().all(|o| o.at_ms != 1));
    }

    /// Verification L-b and n5: adopting a phone's `enforcing: true` is told, and the card says what the phone reports
    /// - also a phone whose reports come without a certificate (the tailnet).
    #[test]
    fn adoption_is_told_and_the_card_says_what_the_phone_reports() {
        let mut enforcing = seen(true, "wifi");
        enforcing.enforcing = Some(true);
        let adopted = next_state(&Row::default(), &enforcing, at(1, 12));
        assert_eq!(adopted.row.state, State::On);
        assert!(adopted.adopted && !adopted.phone_on_stopped);
        assert!(!next_state(&adopted.row, &enforcing, at(1, 13)).adopted);

        let zone = Zone::from_setting("Etc/UTC");
        let lang = crate::i18n::Lang::En;
        let now = at(3, 12);
        // A phone over the tailnet: its report has no `seen`.
        let tailnet = Report {
            enforcing: Some(false),
            ..Report::default()
        };
        let watched = next_state(&Row::default(), &tailnet, now).row;
        assert_eq!(watched.seen_in_set, None);
        assert_eq!(
            card(&watched, &zone, now, None, None).detail.as_deref(),
            Some(t(lang, "server_tls.no_certificate"))
        );
        let on = Row {
            state: State::On,
            ..watched.clone()
        };
        let shown = card(&on, &zone, now, None, None);
        assert_eq!(shown.line, t(lang, "server_tls.on_phone_off"));
        assert_eq!(
            shown.detail.as_deref(),
            Some(t(lang, "server_tls.no_certificate"))
        );
        // The phone says its check is on over the tailnet: not adopted (L-3), said, and "on now" offered.
        let says_on = next_state(
            &watched,
            &Report {
                enforcing: Some(true),
                ..Report::default()
            },
            now,
        )
        .row;
        assert_eq!(says_on.state, State::Watching);
        let shown = card(&says_on, &zone, now, None, None);
        assert_eq!(
            shown.detail.as_deref(),
            Some(t(lang, "server_tls.phone_says_on"))
        );
        assert!(shown.can_turn_on);
        // Over https again: the certificate counts once more.
        let https = next_state(&says_on, &seen(true, "wifi"), now).row;
        assert_eq!(https.seen_in_set, Some(true));
    }

    #[test]
    fn a_kept_row_is_open_while_the_phone_sends_it() {
        let mut report = seen(true, "wifi");
        report.outside = vec![outside("refused", "wifi", 7)];
        let first = next_state(&Row::default(), &report, at(1, 12)).row;
        assert!(first.outside[0].open);
        let again = next_state(&first, &report, at(1, 13)).row;
        assert!(again.outside[0].open);
        let dropped = next_state(&again, &seen(true, "wifi"), at(1, 14)).row;
        assert!(!dropped.outside[0].open);
    }

    #[test]
    fn a_report_is_kept_within_limits() {
        let long = "x".repeat(300);
        let value = serde_json::json!({
            "enforcing": "yes",
            "seen": {"in_set": true, "name": long, "pin": "C5+lpZ7tcVwmwQIMcRtPbsQtWLABXhQzejna0wHFr8M=", "network": "ethernet", "at_ms": 1},
            "outside": [
                {"kind": "seen", "host": long, "name": "a", "pin": "C5+lpZ7tcVwmwQIMcRtPbsQtWLABXhQzejna0wHFr8M=", "network": "wifi", "at_ms": 1},
                {"kind": "other", "host": "h", "name": "a", "pin": "AAAA", "network": "wifi", "at_ms": 2},
                {"kind": "seen", "host": "h", "name": "a", "pin": "<script>", "network": "wifi", "at_ms": 3},
                {"kind": "seen", "host": "h", "name": "a", "pin": long, "network": "wifi", "at_ms": 4},
                "junk"
            ]
        });
        let report = sanitize(&value).unwrap();
        assert_eq!(report.enforcing, None);
        let seen = report.seen.unwrap();
        assert_eq!(
            (seen.name.chars().count(), seen.network.as_str()),
            (NAME_MAX, "other")
        );
        assert_eq!(report.outside.len(), 1);
        assert_eq!(report.outside[0].host.chars().count(), HOST_MAX);
        assert_eq!(sanitize(&serde_json::json!([1])), None);
        assert_eq!(sanitize(&serde_json::json!({})), Some(Report::default()));
    }
}
