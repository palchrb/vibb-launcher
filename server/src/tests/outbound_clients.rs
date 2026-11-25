//! Every outbound HTTP client the server builds is in a file named here, with why its own client is right there. A
//! fetch of an address a parent typed goes through `music_net` (its [Reach](crate::music_net::Reach) and redirect
//! rules); a new client elsewhere fails this scan until it is either moved there or added with its reason.

/// The files that build a reqwest client, and why.
const ALLOWED: &[(&str, &str)] = &[
    (
        "music_net.rs",
        "the guarded client itself: public or LAN reach, no proxy from the environment, checked redirects",
    ),
    (
        "push.rs",
        "Web Push to the parents' browsers' push services: no redirects, a short timeout",
    ),
    (
        "fdroid.rs",
        "F-Droid's repository API and index, the catalog's trust path",
    ),
    (
        "launcher_check.rs",
        "the setup QR's launcher release from GitHub, https-only redirects",
    ),
    (
        "clock_skew.rs",
        "a HEAD to GitHub's API for the server's own clock",
    ),
    ("startup.rs", "the TLS self-test against api.github.com"),
    (
        "attestation/status_list.rs",
        "Google's attestation revocation list, a fixed address",
    ),
    (
        "handlers/tracked_apps.rs",
        "the catalog's GitHub releases and their assets, https-only redirects",
    ),
    (
        "handlers/system_update.rs",
        "the server's own newest release from GitHub",
    ),
];

#[test]
fn every_outbound_client_is_in_a_known_file() {
    // Spelled in two halves, so this test's own text isn't a client.
    let needles = [
        concat!("Client::", "builder("),
        concat!("Client::", "new("),
        concat!("Client", "Builder::new("),
    ];
    let src = std::path::PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("src");
    let mut dirs = vec![src.clone()];
    let mut found = std::collections::BTreeSet::new();
    while let Some(dir) = dirs.pop() {
        for entry in std::fs::read_dir(&dir).unwrap() {
            let path = entry.unwrap().path();
            if path.is_dir() {
                // The tests' own clients talk to their own listeners.
                if path != src.join("tests") {
                    dirs.push(path);
                }
                continue;
            }
            if path.extension().is_none_or(|e| e != "rs") {
                continue;
            }
            let text = std::fs::read_to_string(&path).unwrap();
            // A file's own `#[cfg(test)] mod tests` is the tests' too.
            let code = text.split("#[cfg(test)]\nmod tests").next().unwrap_or("");
            let builds = code.lines().any(|line| {
                let line = line.trim_start();
                !line.starts_with("//") && needles.iter().any(|n| line.contains(n))
            });
            if builds {
                let name = path
                    .strip_prefix(&src)
                    .unwrap()
                    .to_string_lossy()
                    .replace('\\', "/");
                found.insert(name);
            }
        }
    }
    for name in &found {
        assert!(
            ALLOWED.iter().any(|(file, _)| file == name),
            "src/{name} builds an HTTP client: fetch through `music_net`, or add it to ALLOWED with why"
        );
    }
    for (file, _) in ALLOWED {
        assert!(
            found.contains(*file),
            "src/{file} no longer builds a client: take it off ALLOWED"
        );
    }
    assert!(!found.contains("dns_engine.rs"));
}
