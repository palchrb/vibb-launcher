//! Play as an app source and FCM's network needs (handy step 7).

/// The Play Store, Play services and Google Services Framework. The launcher never hides them
/// (FCM needs Play services and the Play Store installed) and never lets them be launched outside
/// its install mode, so they are not the parent's to allow: the device page doesn't list them and
/// the allowlist never contains them.
pub const PLAY_CORE: [&str; 3] = [
    "com.android.vending",
    "com.google.android.gms",
    "com.google.android.gsf",
];

/// The Play Store's package, also the installer name of an app installed from Play.
pub const PLAY_STORE: &str = "com.android.vending";

pub fn is_play_core(package: &str) -> bool {
    PLAY_CORE.contains(&package)
}

/// Hosts FCM delivery needs (QA 07 #10). The phone's DNS filter walks label suffixes, so a list
/// that blocks one of these - or a parent like `googleapis.com` - would silently break FCM.
const FCM_HOSTS: [&str; 8] = [
    "mtalk.google.com",
    "fcm.googleapis.com",
    "fcmtoken.googleapis.com",
    "fcmregistrations.googleapis.com",
    "firebaseinstallations.googleapis.com",
    "android.apis.google.com",
    "android.googleapis.com",
    "android.clients.google.com",
];

/// True for a domain the DNS blocklist must never deliver as blocked: an FCM host (including the
/// numbered `mtalkN.google.com` / `altN-mtalk.google.com` variants) or a parent domain of one.
pub fn is_fcm_protected(domain: &str) -> bool {
    let d = domain.trim_end_matches('.').to_ascii_lowercase();
    if FCM_HOSTS
        .iter()
        .any(|host| *host == d || host.ends_with(&format!(".{d}")))
    {
        return true;
    }
    let Some(label) = d.strip_suffix(".google.com") else {
        return false;
    };
    let numbered_mtalk = |s: &str| {
        s.strip_prefix("mtalk")
            .is_some_and(|rest| rest.chars().all(|c| c.is_ascii_digit()))
    };
    numbered_mtalk(label)
        || label
            .strip_prefix("alt")
            .and_then(|rest| rest.split_once("-"))
            .is_some_and(|(n, m)| n.chars().all(|c| c.is_ascii_digit()) && numbered_mtalk(m))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fcm_hosts_and_parents_are_protected() {
        for d in [
            "mtalk.google.com",
            "mtalk4.google.com",
            "alt3-mtalk.google.com",
            "MTALK.google.com.",
            "fcm.googleapis.com",
            "firebaseinstallations.googleapis.com",
            "android.apis.google.com",
            "android.clients.google.com",
            "googleapis.com",
            "google.com",
            "apis.google.com",
            "clients.google.com",
        ] {
            assert!(is_fcm_protected(d), "{d}");
        }
        for d in [
            "ads.google.com",
            "app-measurement.com",
            "firebaselogging-pa.googleapis.com",
            "mtalkx.google.com",
            "evilgoogle.com",
            "google.com.evil.net",
        ] {
            assert!(!is_fcm_protected(d), "{d}");
        }
    }

    #[test]
    fn play_core() {
        assert!(is_play_core("com.android.vending"));
        assert!(is_play_core("com.google.android.gsf"));
        assert!(!is_play_core("com.google.android.play.games"));
    }
}
