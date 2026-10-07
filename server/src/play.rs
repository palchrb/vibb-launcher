//! Play as an app source, and the network other apps' FCM needs (handy step 7). Our own nudges
//! no longer use FCM (design 19); Element X and other Play apps still do.

/// The Play Store, Play services and Google Services Framework. The launcher never hides them
/// (other apps' FCM - Element X - needs Play services and the Play Store installed) and never lets
/// them be launched outside its install mode, so they are not the parent's to allow: the device
/// page doesn't list them and the allowlist never contains them.
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

/// Hosts FCM delivery needs (QA 07 #10) - for other apps' FCM (Element X) since design 19. The
/// phone's DNS filter walks label suffixes, so a list that blocks one of these - or a parent like
/// `googleapis.com` - would silently break their notifications.
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

/// True for a domain the DNS blocklist must never deliver as blocked: exactly an FCM host
/// (including the numbered `mtalkN.google.com` / `altN-mtalk.google.com` variants). Parent
/// domains (`google.com`, `googleapis.com`) are delivered as the parent set them (QA step 7 #1:
/// dropping them opened all of `*.google.com`); the launcher exempts the exact FCM hosts before
/// its suffix walk, so a blocked parent never blocks FCM.
pub fn is_fcm_protected(domain: &str) -> bool {
    let d = domain.trim_end_matches('.').to_ascii_lowercase();
    if FCM_HOSTS.contains(&d.as_str()) {
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
    fn only_exact_fcm_hosts_are_protected() {
        for d in [
            "mtalk.google.com",
            "mtalk4.google.com",
            "alt3-mtalk.google.com",
            "MTALK.google.com.",
            "fcm.googleapis.com",
            "firebaseinstallations.googleapis.com",
            "android.apis.google.com",
            "android.clients.google.com",
        ] {
            assert!(is_fcm_protected(d), "{d}");
        }
        for d in [
            "googleapis.com",
            "google.com",
            "apis.google.com",
            "clients.google.com",
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
