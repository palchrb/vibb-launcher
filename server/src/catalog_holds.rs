//! Catalog update holds (design 90, `docs/design/90-catalog-update-holds.md`, §17 its revision 2):
//! a GitHub or F-Droid app's new release waits the app's own hours from when its source published
//! it (`tracked_apps.release_wait_hours`, 48 suggested), and one that asks for a sensitive
//! permission the parent hasn't let through for the app is held until the parent approves it. The
//! rules here are pure: what counts as sensitive ([SENSITIVE]), the verdict ([judge]), the wait's
//! anchor ([anchor]) and which listed release to take and which waits ([pick]). The sync and the
//! pages are `handlers::tracked_apps`'.

use chrono::{DateTime, Utc};

use crate::i18n::{Lang, t, t_params};
use crate::models::TrackedApp;

/// The oldest Android our phones run (34 = Android 14, the launcher's `minSdk`): a
/// `<uses-permission>` capped below it (`android:maxSdkVersion`) is never requested here (§3).
pub const PHONE_MIN_SDK: u32 = 34;

/// The wait the Add App form suggests for every source, f-droid.org included (§17.1 D2).
pub const DEFAULT_WAIT_HOURS: i64 = 48;
/// The longest wait the forms take (14 days; a longer wait mostly delays security fixes).
pub const MAX_WAIT_HOURS: i64 = 336;

/// The sensitive groups in the order they are named (the heaviest first, §3's table; `INTERNET`, §3
/// D3, last), each with its words as a list item ("Contacts" / "Kontakter") and inside a sentence
/// ("Contacts" / "kontakter").
pub const GROUPS: [(&str, &str, &str); 23] = [
    ("sms", "apps.holds.group.sms", "apps.holds.group_in.sms"),
    (
        "contacts",
        "apps.holds.group.contacts",
        "apps.holds.group_in.contacts",
    ),
    (
        "call_log",
        "apps.holds.group.call_log",
        "apps.holds.group_in.call_log",
    ),
    (
        "phone",
        "apps.holds.group.phone",
        "apps.holds.group_in.phone",
    ),
    (
        "location",
        "apps.holds.group.location",
        "apps.holds.group_in.location",
    ),
    (
        "location_background",
        "apps.holds.group.location_background",
        "apps.holds.group_in.location_background",
    ),
    (
        "media_location",
        "apps.holds.group.media_location",
        "apps.holds.group_in.media_location",
    ),
    (
        "camera",
        "apps.holds.group.camera",
        "apps.holds.group_in.camera",
    ),
    (
        "microphone",
        "apps.holds.group.microphone",
        "apps.holds.group_in.microphone",
    ),
    ("body", "apps.holds.group.body", "apps.holds.group_in.body"),
    (
        "calendar",
        "apps.holds.group.calendar",
        "apps.holds.group_in.calendar",
    ),
    (
        "nearby",
        "apps.holds.group.nearby",
        "apps.holds.group_in.nearby",
    ),
    (
        "media",
        "apps.holds.group.media",
        "apps.holds.group_in.media",
    ),
    (
        "all_files",
        "apps.holds.group.all_files",
        "apps.holds.group_in.all_files",
    ),
    (
        "overlay",
        "apps.holds.group.overlay",
        "apps.holds.group_in.overlay",
    ),
    (
        "install",
        "apps.holds.group.install",
        "apps.holds.group_in.install",
    ),
    (
        "usage",
        "apps.holds.group.usage",
        "apps.holds.group_in.usage",
    ),
    (
        "notifications",
        "apps.holds.group.notifications",
        "apps.holds.group_in.notifications",
    ),
    (
        "accessibility",
        "apps.holds.group.accessibility",
        "apps.holds.group_in.accessibility",
    ),
    (
        "device_admin",
        "apps.holds.group.device_admin",
        "apps.holds.group_in.device_admin",
    ),
    ("vpn", "apps.holds.group.vpn", "apps.holds.group_in.vpn"),
    (
        "keyboard",
        "apps.holds.group.keyboard",
        "apps.holds.group_in.keyboard",
    ),
    (
        "internet",
        "apps.holds.group.internet",
        "apps.holds.group_in.internet",
    ),
];

/// Every sensitive permission by its exact name (as `apk::ApkIdentity::permissions` stores it: a
/// component's bind permission as `bind:<name>`), with its group. Reviewed with each Android
/// release; an unknown permission is not sensitive. Android 16's `android.permission.health.*` is
/// `body` by its prefix ([group_of]).
pub const SENSITIVE: &[(&str, &str)] = &[
    ("android.permission.READ_SMS", "sms"),
    ("android.permission.SEND_SMS", "sms"),
    ("android.permission.RECEIVE_SMS", "sms"),
    ("android.permission.RECEIVE_MMS", "sms"),
    ("android.permission.RECEIVE_WAP_PUSH", "sms"),
    ("android.permission.READ_CONTACTS", "contacts"),
    ("android.permission.WRITE_CONTACTS", "contacts"),
    ("android.permission.GET_ACCOUNTS", "contacts"),
    ("android.permission.READ_CALL_LOG", "call_log"),
    ("android.permission.WRITE_CALL_LOG", "call_log"),
    ("android.permission.PROCESS_OUTGOING_CALLS", "call_log"),
    ("android.permission.CALL_PHONE", "phone"),
    ("android.permission.ANSWER_PHONE_CALLS", "phone"),
    ("android.permission.READ_PHONE_NUMBERS", "phone"),
    ("android.permission.READ_PHONE_STATE", "phone"),
    ("android.permission.USE_SIP", "phone"),
    ("com.android.voicemail.permission.ADD_VOICEMAIL", "phone"),
    ("android.permission.ADD_VOICEMAIL", "phone"),
    ("android.permission.ACCEPT_HANDOVER", "phone"),
    ("android.permission.ACCESS_FINE_LOCATION", "location"),
    ("android.permission.ACCESS_COARSE_LOCATION", "location"),
    (
        "android.permission.ACCESS_BACKGROUND_LOCATION",
        "location_background",
    ),
    ("android.permission.ACCESS_MEDIA_LOCATION", "media_location"),
    ("android.permission.CAMERA", "camera"),
    ("android.permission.RECORD_AUDIO", "microphone"),
    ("android.permission.BODY_SENSORS", "body"),
    ("android.permission.BODY_SENSORS_BACKGROUND", "body"),
    ("android.permission.ACTIVITY_RECOGNITION", "body"),
    ("android.permission.READ_CALENDAR", "calendar"),
    ("android.permission.WRITE_CALENDAR", "calendar"),
    ("android.permission.BLUETOOTH_SCAN", "nearby"),
    ("android.permission.BLUETOOTH_CONNECT", "nearby"),
    ("android.permission.BLUETOOTH_ADVERTISE", "nearby"),
    ("android.permission.NEARBY_WIFI_DEVICES", "nearby"),
    ("android.permission.UWB_RANGING", "nearby"),
    ("android.permission.READ_MEDIA_IMAGES", "media"),
    ("android.permission.READ_MEDIA_VIDEO", "media"),
    ("android.permission.READ_MEDIA_AUDIO", "media"),
    (
        "android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
        "media",
    ),
    ("android.permission.READ_EXTERNAL_STORAGE", "media"),
    ("android.permission.WRITE_EXTERNAL_STORAGE", "media"),
    ("android.permission.MANAGE_EXTERNAL_STORAGE", "all_files"),
    ("android.permission.SYSTEM_ALERT_WINDOW", "overlay"),
    ("android.permission.REQUEST_INSTALL_PACKAGES", "install"),
    ("android.permission.PACKAGE_USAGE_STATS", "usage"),
    (
        "bind:android.permission.BIND_NOTIFICATION_LISTENER_SERVICE",
        "notifications",
    ),
    (
        "bind:android.permission.BIND_ACCESSIBILITY_SERVICE",
        "accessibility",
    ),
    ("bind:android.permission.BIND_DEVICE_ADMIN", "device_admin"),
    ("bind:android.permission.BIND_VPN_SERVICE", "vpn"),
    ("bind:android.permission.BIND_INPUT_METHOD", "keyboard"),
    ("bind:android.permission.BIND_AUTOFILL_SERVICE", "keyboard"),
    (
        "bind:android.permission.BIND_CREDENTIAL_PROVIDER_SERVICE",
        "keyboard",
    ),
    ("android.permission.INTERNET", "internet"),
];

/// Android 16's health permissions, sensitive as `body` by this prefix (§17.3 L5).
const HEALTH_PREFIX: &str = "android.permission.health.";

/// The group of a sensitive permission, `None` for any other.
pub fn group_of(permission: &str) -> Option<&'static str> {
    if permission.starts_with(HEALTH_PREFIX) && permission.len() > HEALTH_PREFIX.len() {
        return Some("body");
    }
    SENSITIVE
        .iter()
        .find(|(name, _)| *name == permission)
        .map(|(_, group)| *group)
}

/// The sensitive names of a declared list, sorted.
pub fn sensitive(permissions: &[String]) -> Vec<String> {
    let mut names: Vec<String> = permissions
        .iter()
        .filter(|name| group_of(name).is_some())
        .cloned()
        .collect();
    names.sort();
    names.dedup();
    names
}

/// The groups of `names` (sensitive ones; others are left out), each once, in [GROUPS]' order with
/// `internet` last.
pub fn groups(names: &[String]) -> Vec<&'static str> {
    let present: Vec<&str> = names.iter().filter_map(|name| group_of(name)).collect();
    GROUPS
        .iter()
        .map(|(group, _, _)| *group)
        .filter(|group| present.contains(group))
        .collect()
}

/// A group's words as a list item ("Contacts" / "Kontakter"); an unknown group reads as its key.
pub fn group_title(lang: Lang, group: &str) -> String {
    GROUPS.iter().find(|(key, _, _)| *key == group).map_or_else(
        || group.to_string(),
        |(_, title, _)| t(lang, title).to_string(),
    )
}

/// A group's words inside a sentence ("Contacts" / "kontakter").
pub fn group_in(lang: Lang, group: &str) -> String {
    GROUPS.iter().find(|(key, _, _)| *key == group).map_or_else(
        || group.to_string(),
        |(_, _, within)| t(lang, within).to_string(),
    )
}

/// "Contacts and Location", "Contacts, Camera and Location" (the in-sentence words, `lang`'s "and").
pub fn groups_sentence(lang: Lang, groups: &[&str]) -> String {
    let words: Vec<String> = groups.iter().map(|group| group_in(lang, group)).collect();
    match words.as_slice() {
        [] => String::new(),
        [one] => one.clone(),
        [rest @ .., last] => t_params(
            lang,
            "apps.holds.and",
            &[("first", &rest.join(", ")), ("last", last)],
        ),
    }
}

/// The push's short form: the first group, then "and 2 more" past it (§8).
pub fn groups_short(lang: Lang, groups: &[&str]) -> String {
    match groups {
        [] => String::new(),
        [one] => group_in(lang, one),
        [first, rest @ ..] => t_params(
            lang,
            "apps.holds.and_more",
            &[
                ("first", &group_in(lang, first)),
                ("count", &rest.len().to_string()),
            ],
        ),
    }
}

/// Why a release is held (stored as these keys, `tracked_app_held_releases.reason`).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Reason {
    /// It asks for sensitive permissions the app's accepted set doesn't have.
    Permissions,
    /// Its permission list couldn't be read whole (fail closed).
    Unreadable,
    /// The app's "Ask me before every update" is on.
    AlwaysAsk,
}

impl Reason {
    pub fn key(self) -> &'static str {
        match self {
            Reason::Permissions => "permissions",
            Reason::Unreadable => "unreadable",
            Reason::AlwaysAsk => "always_ask",
        }
    }

    pub fn from_key(key: &str) -> Option<Reason> {
        match key {
            "permissions" => Some(Reason::Permissions),
            "unreadable" => Some(Reason::Unreadable),
            "always_ask" => Some(Reason::AlwaysAsk),
            _ => None,
        }
    }
}

/// What a sync does with a release it downloaded and checked (§5).
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Verdict {
    Serve,
    /// Held, with the sensitive names it newly asks for (empty unless `Permissions`).
    Hold(Reason, Vec<String>),
}

/// §5's table. `exempt`: ours (Vibb One, Vibb music) or a manual upload; `permissions` the
/// release's declared list (`None` unreadable); `accepted` the app's accepted set (`None` unknown,
/// counted as empty); `always_ask` the app's switch.
pub fn judge(
    exempt: bool,
    permissions: Option<&[String]>,
    accepted: Option<&[String]>,
    always_ask: bool,
) -> Verdict {
    if exempt {
        return Verdict::Serve;
    }
    let Some(permissions) = permissions else {
        return Verdict::Hold(Reason::Unreadable, Vec::new());
    };
    let accepted = accepted.unwrap_or_default();
    let new: Vec<String> = sensitive(permissions)
        .into_iter()
        .filter(|name| !accepted.contains(name))
        .collect();
    if !new.is_empty() {
        return Verdict::Hold(Reason::Permissions, new);
    }
    if always_ask {
        return Verdict::Hold(Reason::AlwaysAsk, Vec::new());
    }
    Verdict::Serve
}

/// Ours (§2, QA L4), by the row: the launcher's row, or the music app's - pinned to its package,
/// taking `music-v*` releases from one of `our_repos` (this server's configured release repos);
/// a row named after the music app's package on another repo is not ours.
pub fn ours(app: &TrackedApp, our_repos: &[String]) -> bool {
    app.is_launcher
        || (crate::music::MUSIC_PACKAGES.contains(&app.package_name.as_str())
            && app.source_type == "github"
            && app.release_tag_prefix.as_deref() == Some(crate::config::MUSIC_RELEASE_TAG_PREFIX)
            && our_repos.contains(&app.github_repo))
}

/// The app's wait in hours (§17.3 M2): its column for a GitHub or F-Droid row, `None` (no wait,
/// no hold) for a manual row and ours.
pub fn wait_hours(app: &TrackedApp, our_repos: &[String]) -> Option<i64> {
    let synced = matches!(app.source_type.as_str(), "github" | "fdroid");
    (synced && !ours(app, our_repos)).then_some(app.release_wait_hours.clamp(0, MAX_WAIT_HOURS))
}

/// Whether an app's first release waits too (the user hasn't decided, 2026-11-12: it does for
/// now). `false` sends a new app's first release at once - the one line to change.
pub const FIRST_RELEASE_WAITS: bool = true;

/// The wait the sync applies to the app's next release: [wait_hours], none for a first release
/// unless [FIRST_RELEASE_WAITS].
pub fn sync_wait(app: &TrackedApp, our_repos: &[String]) -> Option<i64> {
    wait_hours(app, our_repos).filter(|_| FIRST_RELEASE_WAITS || app.latest_release_tag.is_some())
}

/// A JSON array of names as stored (`accepted_permissions`, `latest_release_permissions`);
/// `None` for NULL or anything unreadable.
pub fn read_names(json: Option<&str>) -> Option<Vec<String>> {
    serde_json::from_str(json?).ok()
}

/// A list of names as stored.
pub fn names_json(names: &[String]) -> String {
    serde_json::to_string(names).unwrap_or_else(|_| "[]".to_string())
}

/// When a release became public at its source (§6): the later of GitHub's `published_at` and the
/// chosen asset's `updated_at` (a swapped asset on an old release restarts the clock); `None`
/// when neither reads. The wait counts from the later of this and the server's first sight
/// ([pick]).
pub fn anchor(published_at: Option<&str>, asset_updated_at: Option<&str>) -> Option<DateTime<Utc>> {
    let parse = |text: Option<&str>| {
        DateTime::parse_from_rfc3339(text?)
            .ok()
            .map(|t| t.with_timezone(&Utc))
    };
    match (parse(published_at), parse(asset_updated_at)) {
        (Some(a), Some(b)) => Some(a.max(b)),
        (a, b) => a.or(b),
    }
}

/// An F-Droid index version's `added` (ms since the epoch) as the wait's anchor.
pub fn anchor_ms(added: Option<i64>) -> Option<DateTime<Utc>> {
    DateTime::from_timestamp_millis(added?)
}

/// One matching release in the source's list, newest first.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Candidate {
    pub tag: String,
    pub asset_id: i64,
    /// The source's time ([anchor], [anchor_ms]); `None` when it gave none.
    pub published: Option<DateTime<Utc>>,
    /// When this server first saw the release (`tracked_app_seen_releases`); `None` = now.
    pub first_seen: Option<DateTime<Utc>>,
}

/// A release named by `(tag, asset id)`.
pub type Named<'a> = (&'a str, i64);

/// What [pick] decides from a source's list.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Pick {
    /// The candidate to sync (download, check, judge), `None` = nothing to do.
    pub take: Option<usize>,
    /// The release waiting out its wait, with the wait's anchor (the source's time, else the
    /// first sight), `None` = clear the waiting columns.
    pub waiting: Option<(usize, DateTime<Utc>)>,
}

/// The app's state the pick compares with.
pub struct Context<'a> {
    /// The app's wait; `None` = none (every release ripe).
    pub wait_hours: Option<i64>,
    pub now: DateTime<Utc>,
    pub served: Option<Named<'a>>,
    pub held: Option<Named<'a>>,
    pub declined: Option<Named<'a>>,
}

/// Which release of `candidates` (newest first) a sync takes, and which waits (§6, §17.3 H2):
/// the newest **ripe** one is taken - unless it isn't ahead of the served release while that is
/// still listed (no step back after an Update Now), is the held one or behind it, or is at or
/// behind the declined one; the newest unripe one ahead of the served, held and declined release
/// waits. `ahead(tag, asset, of)` is `tracked_apps::ahead` for the app's source.
pub fn pick(
    candidates: &[Candidate],
    context: &Context,
    ahead: impl Fn(&str, i64, Named) -> bool,
) -> Pick {
    let same = |c: &Candidate, named: Option<Named>| {
        named.is_some_and(|(tag, asset)| c.tag == tag && c.asset_id == asset)
    };
    // The wait's anchor (QA M1): the later of the source's time and this server's first sight -
    // a source's time can be backdated (a self-hosted repo's `added`), first sight can't.
    let since = |c: &Candidate| {
        let seen = c.first_seen.unwrap_or(context.now);
        c.published.map_or(seen, |published| published.max(seen))
    };
    let wait = context.wait_hours.map(chrono::Duration::hours);
    let ripe = |c: &Candidate| match wait {
        None => true,
        Some(wait) if wait.is_zero() => true,
        Some(wait) => since(c) + wait <= context.now,
    };
    let beyond =
        |c: &Candidate, of: Option<Named>| of.is_none_or(|of| ahead(&c.tag, c.asset_id, of));
    let newest_ripe = candidates.iter().position(ripe);
    let served_listed = candidates.iter().any(|c| same(c, context.served));
    let take = newest_ripe.filter(|&index| {
        let c = &candidates[index];
        !(served_listed && !same(c, context.served) && !beyond(c, context.served))
            && !same(c, context.held)
            && beyond(c, context.held)
            && !same(c, context.declined)
            && beyond(c, context.declined)
    });
    let waiting = candidates[..newest_ripe.unwrap_or(candidates.len())]
        .iter()
        .position(|c| {
            !same(c, context.served)
                && beyond(c, context.served)
                && !same(c, context.held)
                && beyond(c, context.held)
                && !same(c, context.declined)
                && beyond(c, context.declined)
        })
        .map(|index| (index, since(&candidates[index])));
    Pick { take, waiting }
}

/// The waiting release's ready time: its anchor plus the app's wait.
pub fn ready_at(since: DateTime<Utc>, wait_hours: i64) -> DateTime<Utc> {
    since + chrono::Duration::hours(wait_hours)
}

/// A wait as typed in a form (§17.3 M2): a whole number of hours from 0 to [MAX_WAIT_HOURS];
/// `Err` is the field's sentence.
pub fn parse_wait(lang: Lang, input: &str) -> Result<i64, String> {
    let input = input.trim();
    input
        .parse::<i64>()
        .ok()
        .filter(|hours| (0..=MAX_WAIT_HOURS).contains(hours) && !input.starts_with('+'))
        .ok_or_else(|| {
            t_params(
                lang,
                "apps.wait.refused",
                &[("max", &MAX_WAIT_HOURS.to_string())],
            )
        })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn names(list: &[&str]) -> Vec<String> {
        list.iter().map(|name| name.to_string()).collect()
    }

    #[test]
    fn every_sensitive_group_has_words_in_both_languages() {
        for (_, group) in SENSITIVE {
            assert!(
                GROUPS.iter().any(|(key, _, _)| key == group),
                "{group} has no place in the order"
            );
        }
        for (group, title, within) in GROUPS.iter() {
            assert_eq!(*title, format!("apps.holds.group.{group}"));
            assert_eq!(*within, format!("apps.holds.group_in.{group}"));
            for lang in [Lang::En, Lang::Nb] {
                for key in [title, within] {
                    assert_ne!(t(lang, key), *key, "{key} in {lang:?}");
                }
            }
        }
        assert_eq!(
            group_of("android.permission.health.READ_HEART_RATE"),
            Some("body")
        );
        assert_eq!(group_of("android.permission.health."), None);
        for not in [
            "android.permission.QUERY_ALL_PACKAGES",
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.BIND_ACCESSIBILITY_SERVICE",
            "android.permission.FOREGROUND_SERVICE_LOCATION",
            "com.google.android.c2dm.permission.RECEIVE",
        ] {
            assert_eq!(group_of(not), None, "{not}");
        }
    }

    #[test]
    fn the_verdict_follows_the_table() {
        let contacts = names(&[
            "android.permission.READ_CONTACTS",
            "android.permission.VIBRATE",
        ]);
        let accepted = names(&["android.permission.READ_CONTACTS"]);
        assert_eq!(
            judge(true, None, None, true),
            Verdict::Serve,
            "ours or an upload"
        );
        assert_eq!(
            judge(false, None, Some(&accepted), false),
            Verdict::Hold(Reason::Unreadable, vec![])
        );
        assert_eq!(
            judge(false, Some(&contacts), None, false),
            Verdict::Hold(
                Reason::Permissions,
                names(&["android.permission.READ_CONTACTS"])
            ),
            "an unknown accepted set counts as empty"
        );
        assert_eq!(
            judge(false, Some(&contacts), Some(&[]), true),
            Verdict::Hold(
                Reason::Permissions,
                names(&["android.permission.READ_CONTACTS"])
            ),
            "new names before always-ask"
        );
        assert_eq!(
            judge(false, Some(&contacts), Some(&accepted), true),
            Verdict::Hold(Reason::AlwaysAsk, vec![])
        );
        assert_eq!(
            judge(false, Some(&contacts), Some(&accepted), false),
            Verdict::Serve
        );
        assert_eq!(judge(false, Some(&[]), None, false), Verdict::Serve);
    }

    #[test]
    fn groups_are_named_in_order_and_the_push_shortens() {
        let asked = names(&[
            "android.permission.INTERNET",
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.CAMERA",
            "android.permission.READ_CONTACTS",
            "android.permission.WRITE_CONTACTS",
        ]);
        let groups = groups(&asked);
        assert_eq!(groups, ["contacts", "location", "camera", "internet"]);
        assert_eq!(
            groups_sentence(Lang::En, &groups[..2]),
            "Contacts and Location"
        );
        assert_eq!(
            groups_sentence(Lang::Nb, &groups[..3]),
            "kontakter, posisjon og kamera"
        );
        assert_eq!(groups_short(Lang::En, &groups[..1]), "Contacts");
        assert_eq!(groups_short(Lang::En, &groups[..3]), "Contacts and 2 more");
        assert_eq!(groups_short(Lang::Nb, &groups[..3]), "kontakter og 2 til");
    }

    #[test]
    fn the_wait_counts_from_the_source() {
        let published = "2026-11-10T10:00:00Z";
        let swapped = "2026-11-12T08:00:00Z";
        assert_eq!(
            anchor(Some(published), Some(swapped)),
            Some("2026-11-12T08:00:00Z".parse().unwrap()),
            "an asset swapped on an old release waits from the swap"
        );
        assert_eq!(
            anchor(Some(swapped), Some(published)),
            Some("2026-11-12T08:00:00Z".parse().unwrap())
        );
        assert_eq!(
            anchor(None, Some(published)),
            Some(published.parse().unwrap())
        );
        assert_eq!(anchor(Some("yesterday"), None), None);
        assert_eq!(
            anchor_ms(Some(1_762_768_800_000)),
            Some("2025-11-10T10:00:00Z".parse().unwrap())
        );
        assert_eq!(anchor_ms(None), None);
    }

    /// Ahead by the number in `v<n>` (the asset id breaks a tie), like a GitHub app's versions.
    fn by_version(tag: &str, asset: i64, (of_tag, of_asset): Named) -> bool {
        let n = |t: &str| t.trim_start_matches('v').parse::<i64>().unwrap();
        (n(tag), asset) > (n(of_tag), of_asset)
    }

    fn list(now: DateTime<Utc>, ages_hours: &[(i64, Option<i64>)]) -> Vec<Candidate> {
        ages_hours
            .iter()
            .map(|(n, age)| Candidate {
                tag: format!("v{n}"),
                asset_id: *n,
                published: age.map(|h| now - chrono::Duration::hours(h)),
                // Seen by this server long ago: the source's time decides.
                first_seen: Some(now - chrono::Duration::hours(1000)),
            })
            .collect()
    }

    #[test]
    fn the_ripe_release_is_taken_and_the_newer_one_waits() {
        let now: DateTime<Utc> = "2026-11-12T12:00:00Z".parse().unwrap();
        let context = |served| Context {
            wait_hours: Some(48),
            now,
            served,
            held: None,
            declined: None,
        };
        // A project releasing every day: the release two days old is served, today's waits.
        let daily = list(
            now,
            &[(5, Some(1)), (4, Some(25)), (3, Some(49)), (2, Some(73))],
        );
        let pick_of = |c: &Context| pick(&daily, c, by_version);
        assert_eq!(
            pick_of(&context(Some(("v2", 2)))),
            Pick {
                take: Some(2),
                waiting: Some((0, now - chrono::Duration::hours(1)))
            }
        );
        // H2: after an Update Now the served release is the newest - no step back, nothing waits.
        assert_eq!(
            pick_of(&context(Some(("v5", 5)))),
            Pick {
                take: None,
                waiting: None
            }
        );
        // ... while the served release is pulled from the list, today's rule takes the ripe one.
        assert_eq!(pick_of(&context(Some(("v9", 9)))).take, Some(2));
        // No wait: the newest.
        let none = Context {
            wait_hours: Some(0),
            ..context(Some(("v2", 2)))
        };
        assert_eq!(
            pick_of(&none),
            Pick {
                take: Some(0),
                waiting: None
            }
        );
        // A held v4: an older ripe v3 isn't offered; v5 waits.
        let held = Context {
            held: Some(("v4", 4)),
            ..context(Some(("v2", 2)))
        };
        assert_eq!(
            pick_of(&held),
            Pick {
                take: None,
                waiting: Some((0, now - chrono::Duration::hours(1)))
            }
        );
        // A declined v3: not taken again; v4 and v5 wait their time.
        let declined = Context {
            declined: Some(("v3", 3)),
            ..context(Some(("v2", 2)))
        };
        assert_eq!(pick_of(&declined).take, None);
        assert_eq!(pick_of(&declined).waiting.map(|(i, _)| i), Some(0));
    }

    /// QA M1: the wait counts from the later of the source's time and this server's first sight -
    /// a backdated `added` doesn't shorten it; a release without a time waits from first sight.
    #[test]
    fn the_wait_counts_from_first_sight_too() {
        let now: DateTime<Utc> = "2026-11-12T12:00:00Z".parse().unwrap();
        let hours = |h| now - chrono::Duration::hours(h);
        let candidate = |n: i64, published: Option<i64>, seen: Option<i64>| Candidate {
            tag: format!("v{n}"),
            asset_id: n,
            published: published.map(hours),
            first_seen: seen.map(hours),
        };
        let context = Context {
            wait_hours: Some(48),
            now,
            served: Some(("v1", 1)),
            held: None,
            declined: None,
        };
        // "Published" a year ago, first seen now: waits from now.
        assert_eq!(
            pick(&[candidate(3, Some(8760), None)], &context, by_version),
            Pick {
                take: None,
                waiting: Some((0, now))
            }
        );
        // Seen 49 hours ago: ripe, whether the source gave a time or not.
        for published in [Some(8760), None] {
            assert_eq!(
                pick(&[candidate(3, published, Some(49))], &context, by_version).take,
                Some(0)
            );
        }
        // Published an hour ago, seen two days ago (a clock ahead): the source's time is later.
        assert_eq!(
            pick(&[candidate(3, Some(1), Some(49))], &context, by_version).waiting,
            Some((0, hours(1)))
        );
    }

    #[test]
    fn the_wait_field_takes_whole_hours_up_to_two_weeks() {
        for good in ["0", "48", "336", " 24 "] {
            assert!(parse_wait(Lang::En, good).is_ok(), "{good}");
        }
        for bad in ["", "-1", "337", "4.5", "x", "+4"] {
            let refused = parse_wait(Lang::En, bad).unwrap_err();
            assert!(refused.contains("336"), "{bad}: {refused}");
        }
    }

    /// The launcher's `minSdk` is the phones' oldest Android, so a permission capped below it is
    /// never requested here (§3): the two can't drift.
    #[test]
    fn phone_min_sdk_is_the_launchers_min_sdk() {
        let gradle = std::fs::read_to_string("../launcher/app/build.gradle.kts").unwrap();
        let min_sdk: u32 = gradle
            .lines()
            .find_map(|line| line.trim().strip_prefix("minSdk = "))
            .and_then(|value| value.trim().parse().ok())
            .expect("the launcher's minSdk");
        assert_eq!(PHONE_MIN_SDK, min_sdk);
    }
}
