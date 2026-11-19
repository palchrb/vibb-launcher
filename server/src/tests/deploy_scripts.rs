//! The root-run scripts in `deploy/`, which the owner runs by hand: the server never acts on its
//! host (the user, 2026-11-04) - no root helper, no update button, no system schedule.

use axum::http::{Method, StatusCode};

use super::TestApp;

const INSTALL: &str = include_str!("../../deploy/install.sh");
const UPDATE: &str = include_str!("../../deploy/update.sh");

/// The text from `start` up to the first line that is exactly `end` (inclusive).
fn block<'a>(script: &'a str, start: &str, end: &str) -> &'a str {
    let at = script.find(start).unwrap_or_else(|| panic!("no {start}"));
    let len = script[at..]
        .find(&format!("\n{end}\n"))
        .unwrap_or_else(|| panic!("no end of {start}"));
    &script[at..at + len + end.len() + 1]
}

/// Neither script installs anything that acts for the server - no root watcher, scheduler, backup
/// copy, request file or version stamp in data/.
#[test]
fn no_host_helper_is_installed() {
    for script in [INSTALL, UPDATE] {
        for gone in [
            "ACTIONS_EOF",
            "WATCHER_EOF",
            "SCHEDULER_EOF",
            "BACKUP_SYNC_EOF",
            "cat >/etc/systemd/system/kid-phone-server-updater",
            "cat >/etc/systemd/system/kid-phone-server-scheduler",
            "cat >/etc/systemd/system/kid-phone-server-backup-sync",
            "enable --now kid-phone-server-updater",
            "update_requested",
            "WATCHER_SCHEMA",
            "watcher_schema_version",
            "schedule.conf",
            "mkdir -p /mnt/kid-phone-server-backup",
        ] {
            assert!(!script.contains(gone), "{gone}");
        }
        let line = "VERSION_FILE=\"/var/lib/kid-phone-server/installed_version\"";
        assert_eq!(script.matches(&format!("\n{line}\n")).count(), 1, "{line}");
        assert_eq!(
            block(script, "installed_version() {", "}"),
            block(INSTALL, "installed_version() {", "}")
        );
    }
    // The version the next update's downgrade check reads, root's - never in data/.
    assert!(UPDATE.contains("\n    record_version \"$NEW_VERSION\"\n"));
    assert!(INSTALL.contains("\nrecord_version \"$INSTALLED_VERSION\"\n"));
}

/// Security review M5: root installs only what the pinned key signed - the tarball in install.sh
/// and update.sh - with the trusted comment naming the file and the tag; the release signs both
/// tarballs and update.sh (an older install's watcher still takes update.sh from the release, the
/// way in to this one) and checks them against the same key.
#[test]
fn root_takes_only_signed_release_files() {
    let places = [INSTALL, UPDATE];
    let key_lines: Vec<&str> = places
        .iter()
        .flat_map(|script| {
            script
                .lines()
                .filter(|line| line.starts_with("SERVER_SIGNING_PUBKEY="))
        })
        .collect();
    assert_eq!(key_lines.len(), 2, "{key_lines:?}");
    assert_eq!(key_lines[0], key_lines[1]);
    let verify = block(INSTALL, "verify_signed() {", "}");
    assert_eq!(block(UPDATE, "verify_signed() {", "}"), verify);
    assert!(verify.contains("[ \"$comment\" != \"$name $tag\" ]"));
    assert!(verify.contains("= \"not-set\" ]"), "no key pinned refuses");

    for script in places {
        let download = block(script, "download_release() {", "}");
        let signed = download
            .find("verify_signed \"$dir/$asset\" \"$asset\" \"$TAG\" || exit 1")
            .expect("the tarball's signature is checked");
        assert!(signed < download.find("tar -xzf").unwrap());
    }
    assert!(INSTALL.contains(" minisign >/dev/null"));

    let release = include_str!("../../../.github/workflows/server-release.yml");
    assert!(release.contains("secrets.SERVER_MINISIGN_KEY"));
    assert!(release.contains(r#"minisign -S -s "$key" -m "$file" -t "$file $TAG""#));
    assert!(release.contains(
        "for file in kid-phone-server-aarch64-unknown-linux-musl.tar.gz \
         kid-phone-server-x86_64-unknown-linux-musl.tar.gz update.sh; do"
    ));
    assert!(release.contains(
        "kid-phone-server-x86_64-unknown-linux-musl.tar.gz.minisig,update.sh,update.sh.minisig\""
    ));
}

/// The scripts' `case "$(uname -m)"`, the same text in install.sh and update.sh.
fn target_case(script: &str) -> &str {
    block(script, "# The release's tarball for this machine", "esac")
}

/// Runs install.sh's `case "$(uname -m)"` with `uname -m` answering `machine`: the TARGET it
/// picks, or None when it refuses the machine (with the refusal on stderr).
fn picked_target(machine: &str) -> Option<String> {
    let sandbox = tempfile::tempdir().unwrap();
    let uname = sandbox.path().join("uname");
    std::fs::write(&uname, format!("#!/bin/sh\necho {machine}\n")).unwrap();
    let mut permissions = std::fs::metadata(&uname).unwrap().permissions();
    std::os::unix::fs::PermissionsExt::set_mode(&mut permissions, 0o755);
    std::fs::set_permissions(&uname, permissions).unwrap();
    let output = std::process::Command::new("bash")
        .arg("-c")
        .arg(format!(
            "set -euo pipefail\n{}\necho \"$TARGET\"\n",
            target_case(INSTALL)
        ))
        .env(
            "PATH",
            format!(
                "{}:{}",
                sandbox.path().display(),
                std::env::var("PATH").unwrap()
            ),
        )
        .output()
        .expect("bash runs");
    if output.status.success() {
        Some(String::from_utf8(output.stdout).unwrap().trim().to_string())
    } else {
        let stderr = String::from_utf8(output.stderr).unwrap();
        assert!(
            stderr.contains(&format!("Unsupported architecture: {machine}")),
            "{stderr}"
        );
        assert_eq!(output.status.code(), Some(1));
        None
    }
}

/// The release publishes a tarball for aarch64 and one for x86_64 (the user, 2026-10-25): both
/// scripts pick the one for the machine they run on, refuse any other machine, and the release
/// builds and publishes, signed, every tarball they can pick.
#[test]
fn the_scripts_pick_the_tarball_for_the_machine() {
    assert_eq!(target_case(INSTALL), target_case(UPDATE));
    for (machine, target) in [
        ("aarch64", Some("aarch64-unknown-linux-musl")),
        ("arm64", Some("aarch64-unknown-linux-musl")),
        ("x86_64", Some("x86_64-unknown-linux-musl")),
        ("amd64", Some("x86_64-unknown-linux-musl")),
        ("armv7l", None),
        ("i686", None),
        ("riscv64", None),
    ] {
        assert_eq!(picked_target(machine).as_deref(), target, "{machine}");
    }
    for script in [INSTALL, UPDATE] {
        let download = block(script, "download_release() {", "}");
        assert!(download.contains(r#"asset="kid-phone-server-$TARGET.tar.gz""#));
    }

    let release = include_str!("../../../.github/workflows/server-release.yml");
    let published: Vec<&str> = release
        .lines()
        .find_map(|line| line.trim().strip_prefix("artifacts: "))
        .expect("the release's artifacts")
        .trim_matches('"')
        .split(',')
        .collect();
    let targets: Vec<&str> = target_case(INSTALL)
        .split("TARGET=\"")
        .skip(1)
        .map(|rest| &rest[..rest.find('"').unwrap()])
        .collect();
    assert_eq!(
        targets,
        ["aarch64-unknown-linux-musl", "x86_64-unknown-linux-musl"]
    );
    for target in targets {
        assert!(
            release.contains(&format!("- target: {target}\n")),
            "{target} isn't built"
        );
        for suffix in ["", ".sha256", ".minisig"] {
            let asset = format!("kid-phone-server-{target}.tar.gz{suffix}");
            assert!(
                published.contains(&asset.as_str()),
                "{asset} isn't published"
            );
        }
    }
    assert_eq!(published.len(), 8, "{published:?}");
}

/// Security review H1: the backup schedule's time is exactly HH:MM or nothing is written.
#[tokio::test]
async fn backup_times_that_arent_hh_mm_are_refused() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let schedule = app.state.data_dir.join("backup_schedule.conf");
    for bad in [
        "03:00$(touch /tmp/x)",
        "03:00\nRETENTION_COUNT=0",
        "3am",
        "25:00",
    ] {
        let res = app
            .request_form(
                Method::POST,
                "/backups/schedule",
                Some(&cookie),
                &[
                    ("frequency", "daily"),
                    ("day_of_week", "0"),
                    ("day_of_month", "1"),
                    ("retention_count", "14"),
                    ("backup_time", bad),
                ],
            )
            .await;
        assert_eq!(res.status, StatusCode::BAD_REQUEST, "{bad:?}");
        assert!(res.text().contains("nothing was saved"));
    }
    assert!(!schedule.exists(), "a refused form wrote the schedule");
}

/// Security review L9: the server's CI and release build with a pinned Rust; since 2026-10-25 each
/// musl binary natively, on a runner of its own architecture (no cross, no QEMU).
#[test]
fn server_workflows_pin_rust_and_build_natively() {
    for workflow in [
        include_str!("../../../.github/workflows/server-ci.yml"),
        include_str!("../../../.github/workflows/server-release.yml"),
    ] {
        assert!(!workflow.contains("toolchain: stable"));
        assert!(workflow.contains("toolchain: 1."));
        assert!(!workflow.contains("tool: cross"));
        assert!(!workflow.contains("cross build"));
        assert!(!workflow.to_lowercase().contains("qemu-action"));
        let lines: Vec<&str> = workflow.lines().map(str::trim).collect();
        let mut legs = 0;
        for (at, line) in lines.iter().enumerate() {
            let runner = match line.trim_start_matches("- ") {
                "target: aarch64-unknown-linux-musl" => "runner: ubuntu-24.04-arm",
                "target: x86_64-unknown-linux-musl" => "runner: ubuntu-24.04",
                _ => continue,
            };
            let next = lines[at + 1..]
                .iter()
                .find(|line| line.starts_with("runner: "))
                .expect("a runner for the target");
            assert_eq!(*next, runner, "{line}");
            legs += 1;
        }
        assert!(legs >= 2, "no per-architecture matrix");
        assert!(workflow.contains(r#"cargo build --release --locked --target "$TARGET""#));
    }
}

/// Security review M2: data/ and what the server writes are the service user's alone, on a new
/// install and after an update.
#[test]
fn data_stays_private() {
    assert!(INSTALL.contains("\nUMask=0077\n"));
    assert!(UPDATE.contains("printf '[Service]\\nUMask=0077\\n' >/etc/systemd/system/kid-phone-server.service.d/umask.conf"));
    for script in [INSTALL, UPDATE] {
        assert!(script.contains("chmod 700 \"$INSTALL_DIR/data\""));
        // chmod in data/ runs as the service user: a planted symlink reaches nothing of root's.
        assert!(
            script
                .contains("runuser -u \"$SERVICE_USER\" -- find \"$INSTALL_DIR/data\" -xdev \\\n")
        );
    }
}

/// QA of the security wave, Low 2: root writes nothing into data/ - not even a version stamp -
/// and update.sh copies only plain database files out.
#[test]
fn root_never_creates_files_in_data() {
    for script in [INSTALL, UPDATE] {
        assert!(!script.contains("mktemp \"$INSTALL_DIR/data"));
        assert!(!script.contains(">\"$INSTALL_DIR/data/"));
        assert!(!script.contains("write_data_file"));
    }
    assert!(UPDATE.contains(
        "if [ -f \"$INSTALL_DIR/data/$f\" ] && [ ! -L \"$INSTALL_DIR/data/$f\" ]; then\n        cp -P -p"
    ));
}

/// QA of the security wave, Low 6: the release is signed - with the key only the protected
/// `release` environment holds - before any image is pushed or tagged, and published from the
/// signed files.
#[test]
fn the_release_signs_first_and_in_the_release_environment() {
    let release = include_str!("../../../.github/workflows/server-release.yml");
    let job = |name: &str| {
        let start = release
            .find(&format!("\n  {name}:\n"))
            .unwrap_or_else(|| panic!("no job {name}"));
        let rest = &release[start + 1..];
        let end = rest[2..]
            .find("\n  server-release-")
            .map_or(rest.len(), |at| at + 2);
        &rest[..end]
    };
    let sign = job("server-release-sign");
    assert!(sign.contains("    environment: release\n"), "{sign}");
    assert!(sign.contains("secrets.SERVER_MINISIGN_KEY"));
    assert_eq!(release.matches("secrets.SERVER_MINISIGN_KEY").count(), 1);
    assert!(sign.contains("needs: [ server-release-test, server-release-build ]"));
    assert!(sign.contains("pattern: server-tarball-*"));
    let image = job("server-release-image");
    assert!(
        image.contains("needs: [ server-release-test, server-release-build, server-release-sign ]")
    );
    assert!(
        image.contains(
            "needs.server-release-sign.result == 'success' || github.event_name != 'push'"
        )
    );
    let publish = job("server-release-publish");
    assert!(publish.contains("name: server-release-signed"));
    assert!(publish.contains("server-release-sign, server-release-image"));
}

/// QA of the security wave, Low 7: without a pinned key the scripts say so before doing
/// anything, and a release without a signature is named as such - never installed.
#[test]
fn an_unsigned_install_fails_early_and_clearly() {
    for script in [INSTALL, UPDATE] {
        let early = script
            .find("if [ \"$SERVER_SIGNING_PUBKEY\" = \"not-set\" ]; then\n    echo \"No release signing key is pinned in this script")
            .expect("the early check");
        let first_change = ["apt-get", "useradd", "mkdir -p", "systemctl stop"]
            .iter()
            .filter_map(|command| script.find(&format!("\n{command}")))
            .min()
            .unwrap();
        assert!(early < first_change);
        let download = block(script, "download_release() {", "}");
        assert!(download.contains("aren't signed, and"), "{download}");
    }
}

/// Design 116 §5: DEPLOY.md's yaml block mounts what `compose.yaml` mounts - the data volume, the backups folder on the
/// host and the keys' own volume.
#[test]
fn deploy_md_mounts_what_compose_yaml_mounts() {
    let compose = include_str!("../../deploy/compose.yaml");
    let deploy = include_str!("../../DEPLOY.md");
    let mut in_compose: Vec<&str> = compose
        .lines()
        .filter_map(|line| line.trim().strip_prefix("- "))
        .filter(|mount| mount.contains(":/app/data"))
        .collect();
    let block = deploy
        .lines()
        .find_map(|line| line.trim().strip_prefix("volumes: ["))
        .and_then(|rest| rest.strip_suffix(']'))
        .expect("DEPLOY.md's volumes line");
    let mut in_deploy: Vec<&str> = block.split(", ").collect();
    in_compose.sort_unstable();
    in_deploy.sort_unstable();
    assert_eq!(in_compose, in_deploy);
    assert_eq!(
        in_deploy,
        [
            "./backups:/app/data/backups",
            "handy-data:/app/data",
            "handy-keys:/app/data/keys"
        ]
    );
}
