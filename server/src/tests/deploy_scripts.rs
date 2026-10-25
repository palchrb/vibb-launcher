//! The root-run scripts in `deploy/` and the forms that feed them (security review H1).

use axum::http::{Method, StatusCode};

use super::TestApp;

const INSTALL: &str = include_str!("../../deploy/install.sh");
const UPDATE: &str = include_str!("../../deploy/update.sh");

/// The text of the heredoc `<<'MARKER'` ... `MARKER` in `script` (the opening line may go on
/// after the marker, e.g. into a pipe).
fn heredoc<'a>(script: &'a str, marker: &str) -> &'a str {
    let open = format!("<<'{marker}'");
    let at = script.find(&open).unwrap_or_else(|| panic!("no {marker}"));
    let start = at + script[at..].find('\n').unwrap() + 1;
    let end = script[start..]
        .find(&format!("\n{marker}\n"))
        .unwrap_or_else(|| panic!("no end of {marker}"));
    &script[start..start + end + 1]
}

const ROOT_SCRIPTS: [&str; 4] = [
    "ACTIONS_EOF",
    "WATCHER_EOF",
    "SCHEDULER_EOF",
    "BACKUP_SYNC_EOF",
];

#[test]
fn update_sh_writes_the_same_root_scripts_as_install_sh() {
    for marker in ROOT_SCRIPTS {
        assert_eq!(
            heredoc(INSTALL, marker),
            heredoc(UPDATE, marker),
            "update.sh refreshes the root scripts with install.sh's text - keep {marker} equal"
        );
    }
}

/// No root script reads a file in data/ as shell code: the service user writes data/.
#[test]
fn no_root_script_sources_anything_from_data() {
    for marker in ROOT_SCRIPTS {
        let script = heredoc(INSTALL, marker);
        for line in script.lines() {
            let code = line.trim_start();
            if code.starts_with('#') {
                continue;
            }
            let sources = code.starts_with("source ")
                || code.starts_with(". ")
                || code.contains("eval ")
                || code.contains("bash \"$CONFIG")
                || code.contains("$(cat \"$CONFIG");
            if sources {
                assert_eq!(
                    code, "source /opt/kid-phone-server-updater/actions.sh",
                    "{marker} runs something other than the root-owned actions.sh"
                );
            }
        }
    }
}

/// The restore (security review M4): only kidphone.db is read from the zip, with a size limit
/// and the SQLite header checked, and it goes into place by rename - never `unzip` of the whole
/// archive, never `cp` onto a path in data/.
#[test]
fn the_root_restore_never_writes_through_data() {
    let actions = heredoc(INSTALL, "ACTIONS_EOF");
    let start = actions.find("action_restore_backup() {").unwrap();
    let restore = &actions[start..];
    let restore = &restore[..restore.find("\n}\n").unwrap()];
    assert!(restore.contains("unzip -p \"$backup_path\" kidphone.db"));
    assert!(restore.contains("RESTORE_MAX_BYTES"));
    assert!(restore.contains("53514c69746520666f726d6174203300"));
    assert!(restore.contains("mv -fT \"$incoming\" \"$DATA_DIR/kidphone.db\""));
    assert!(
        !restore.contains("unzip -q"),
        "the whole archive is never unpacked"
    );
    assert!(!restore.contains("-d \"$restore_tmp\""));
    for line in restore.lines().map(str::trim) {
        if line.starts_with("cp ") {
            assert!(
                !line.contains("\"$DATA_DIR/kidphone.db\"\n")
                    && !line.ends_with("\"$DATA_DIR/kidphone.db\""),
                "cp onto data/: {line}"
            );
        }
        assert!(!line.contains("chown -R"), "{line}");
    }
    assert!(heredoc(INSTALL, "WATCHER_EOF").contains("if [ -L \"$FLAG_FILE\" ]"));
}

/// Security review M2: data/ and what the server writes are the service user's alone, on a new
/// install and after an update; the restore tells the next start to forget the sessions; the
/// drive copies are root's.
#[test]
fn data_stays_private_and_a_restore_ends_the_sessions() {
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
    let actions = heredoc(INSTALL, "ACTIONS_EOF");
    let restore = &actions[actions.find("action_restore_backup() {").unwrap()..];
    let restore = &restore[..restore.find("\n}\n").unwrap()];
    let marker = restore
        .find("mv -fT \"$incoming\" \"$DATA_DIR/restore_applied\"")
        .expect("the restore leaves the marker, by rename");
    assert!(
        marker
            > restore
                .find("mv -fT \"$incoming\" \"$DATA_DIR/kidphone.db\"")
                .unwrap()
    );
    assert_eq!(
        crate::startup::RESTORE_APPLIED,
        "restore_applied",
        "the name actions.sh writes"
    );
    let sync = heredoc(INSTALL, "BACKUP_SYNC_EOF");
    assert!(sync.contains("chmod 700 \"$EXTERNAL_MOUNT\""));
    assert!(sync.contains("install -d -m 700 \"$EXTERNAL_MOUNT/snapshots\""));
    assert!(sync.contains("install -d -m 700 \"$EXTERNAL_MOUNT/live\""));
}

/// The text from `start` up to the first line that is exactly `end` (inclusive).
fn block<'a>(script: &'a str, start: &str, end: &str) -> &'a str {
    let at = script.find(start).unwrap_or_else(|| panic!("no {start}"));
    let len = script[at..]
        .find(&format!("\n{end}\n"))
        .unwrap_or_else(|| panic!("no end of {start}"));
    &script[at..at + len + end.len() + 1]
}

/// Security review M5: root installs and runs only what the pinned key signed - the tarball in
/// install.sh and update.sh, and update.sh itself in actions.sh - with the trusted comment naming
/// the file and the tag; the release signs both and checks them against the same key.
#[test]
fn root_takes_only_signed_release_files() {
    let actions = heredoc(INSTALL, "ACTIONS_EOF");
    let places = [INSTALL, UPDATE, actions, heredoc(UPDATE, "ACTIONS_EOF")];
    let key_lines: Vec<&str> = places
        .iter()
        .flat_map(|script| {
            script
                .lines()
                .filter(|line| line.starts_with("SERVER_SIGNING_PUBKEY="))
        })
        .collect();
    // install.sh and update.sh each hold it twice: their own and actions.sh's.
    assert_eq!(key_lines.len(), 6, "{key_lines:?}");
    assert!(
        key_lines.iter().all(|line| *line == key_lines[0]),
        "{key_lines:?}"
    );
    let verify = block(INSTALL, "verify_signed() {", "}");
    for script in places {
        assert_eq!(block(script, "verify_signed() {", "}"), verify);
    }
    assert!(verify.contains("[ \"$comment\" != \"$name $tag\" ]"));
    assert!(verify.contains("= \"not-set\" ]"), "no key pinned refuses");

    for script in [INSTALL, UPDATE] {
        let download = block(script, "download_release() {", "}");
        let signed = download
            .find("verify_signed \"$dir/$asset\" \"$asset\" \"$TAG\" || exit 1")
            .expect("the tarball's signature is checked");
        assert!(signed < download.find("tar -xzf").unwrap());
    }
    let update = block(actions, "action_app_update() {", "}");
    assert!(!update.contains("raw.githubusercontent.com"), "{update}");
    let checked = update
        .find("verify_signed \"$dir/update.sh\" update.sh \"$tag\"")
        .expect("update.sh's signature is checked");
    assert!(checked < update.find("bash \"$dir/update.sh\"").unwrap());
    assert!(INSTALL.contains("jq minisign"));

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

#[tokio::test]
async fn schedule_times_that_arent_hh_mm_are_refused() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let files = [
        "data/schedule.conf",
        "data/app_update_schedule.conf",
        "data/backup_schedule.conf",
    ];
    let before: Vec<Option<Vec<u8>>> = files.iter().map(|f| std::fs::read(f).ok()).collect();
    for bad in [
        "03:00$(touch /tmp/x)",
        "03:00\nAUTO_REBOOT=true",
        "3am",
        "25:00",
    ] {
        for (path, field, extra) in [
            (
                "/system/schedule",
                "check_time",
                vec![
                    ("frequency", "daily"),
                    ("day_of_week", "0"),
                    ("day_of_month", "1"),
                ],
            ),
            (
                "/update/schedule",
                "check_time",
                vec![
                    ("frequency", "daily"),
                    ("day_of_week", "0"),
                    ("day_of_month", "1"),
                ],
            ),
            (
                "/backups/schedule",
                "backup_time",
                vec![
                    ("frequency", "daily"),
                    ("day_of_week", "0"),
                    ("day_of_month", "1"),
                    ("retention_count", "14"),
                ],
            ),
        ] {
            let mut fields = extra.clone();
            fields.push((field, bad));
            let res = app
                .request_form(Method::POST, path, Some(&cookie), &fields)
                .await;
            assert_eq!(res.status, StatusCode::BAD_REQUEST, "{path} {bad:?}");
            assert!(res.text().contains("nothing was saved"), "{path}");
        }
    }
    let after: Vec<Option<Vec<u8>>> = files.iter().map(|f| std::fs::read(f).ok()).collect();
    assert_eq!(before, after, "a refused form wrote a schedule file");
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

/// Runs root script `marker` (install.sh's heredoc) under bash in a sandbox: each `(from, to)`
/// replaces a fixed path, and `stubs` are commands on a PATH of their own (each a script body).
/// Returns the exit status; the run is cut off after 20 s (a hang is a failure).
fn run_root_script(
    marker: &str,
    replace: &[(&str, &str)],
    stubs: &[(&str, &str)],
    sandbox: &std::path::Path,
) -> std::process::ExitStatus {
    let mut script = heredoc(INSTALL, marker).to_string();
    for (from, to) in replace {
        assert!(script.contains(from), "{marker} has no {from}");
        script = script.replace(from, to);
    }
    let bin = sandbox.join("stub-bin");
    std::fs::create_dir_all(&bin).unwrap();
    for (name, body) in stubs {
        let path = bin.join(name);
        std::fs::write(&path, format!("#!/bin/sh\n{body}\n")).unwrap();
        let mut permissions = std::fs::metadata(&path).unwrap().permissions();
        std::os::unix::fs::PermissionsExt::set_mode(&mut permissions, 0o755);
        std::fs::set_permissions(&path, permissions).unwrap();
    }
    let path = sandbox.join(format!("{marker}.sh"));
    std::fs::write(&path, script).unwrap();
    let status = std::process::Command::new("timeout")
        .arg("20")
        .arg("bash")
        .arg(&path)
        .env(
            "PATH",
            format!("{}:{}", bin.display(), std::env::var("PATH").unwrap()),
        )
        .status()
        .expect("bash runs");
    assert_ne!(status.code(), Some(124), "{marker} hung");
    status
}

fn mkfifo(path: &std::path::Path) {
    let made = std::process::Command::new("mkfifo")
        .arg(path)
        .status()
        .unwrap();
    assert!(made.success());
}

/// QA of the security wave, Medium 1: the USB backup sync copies only regular files out of
/// data/ - never a symlink, a FIFO or a directory - and never writes through a symlink already on
/// the drive.
#[test]
fn the_backup_sync_never_follows_or_writes_through_links() {
    let sandbox = tempfile::tempdir().unwrap();
    let root = sandbox.path();
    let data = root.join("data");
    let backups = data.join("backups");
    let drive = root.join("drive");
    let victims = root.join("root-files");
    for dir in [
        &backups,
        &drive.join("snapshots"),
        &victims,
        &backups.join("sub"),
    ] {
        std::fs::create_dir_all(dir).unwrap();
    }
    for name in ["actions.sh", "shadow", "other"] {
        std::fs::write(victims.join(name), "root's own\n").unwrap();
    }
    std::fs::write(
        data.join("backup_schedule.conf"),
        "EXTERNAL_COPY_ENABLED=true\nCONTINUOUS_MIRROR_ENABLED=true\n",
    )
    .unwrap();
    std::fs::write(backups.join("backup-20261011-020000.zip"), "a backup").unwrap();
    std::os::unix::fs::symlink(victims.join("shadow"), backups.join("link")).unwrap();
    mkfifo(&backups.join("fifo"));
    std::fs::write(backups.join("sub/inner"), "nested").unwrap();
    std::os::unix::fs::symlink(victims.join("other"), data.join("live_mirror.db")).unwrap();
    // QA's sequence: a link the drive got earlier, and now a regular file of the same name.
    std::os::unix::fs::symlink(victims.join("actions.sh"), drive.join("snapshots/planted"))
        .unwrap();
    std::fs::write(backups.join("planted"), "#!/bin/sh\necho owned\n").unwrap();
    mkfifo(&drive.join("snapshots/old-fifo"));

    let status = run_root_script(
        "BACKUP_SYNC_EOF",
        &[
            (
                "/opt/kid-phone-server/data/backup_schedule.conf",
                data.join("backup_schedule.conf").to_str().unwrap(),
            ),
            ("/opt/kid-phone-server/data", data.to_str().unwrap()),
            ("/mnt/kid-phone-server-backup", drive.to_str().unwrap()),
        ],
        &[("mountpoint", "exit 0")],
        root,
    );
    assert!(status.success(), "{status:?}");

    for name in ["actions.sh", "shadow", "other"] {
        assert_eq!(
            std::fs::read_to_string(victims.join(name)).unwrap(),
            "root's own\n",
            "{name} was written through"
        );
    }
    let snapshots = drive.join("snapshots");
    assert_eq!(
        std::fs::read(snapshots.join("backup-20261011-020000.zip")).unwrap(),
        b"a backup"
    );
    let planted = std::fs::symlink_metadata(snapshots.join("planted")).unwrap();
    assert!(
        planted.file_type().is_file(),
        "the drive's link was replaced"
    );
    for gone in ["link", "fifo", "sub", "old-fifo"] {
        assert!(
            std::fs::symlink_metadata(snapshots.join(gone)).is_err(),
            "{gone} on the drive"
        );
    }
    assert!(
        std::fs::symlink_metadata(drive.join("live/kidphone.db")).is_err(),
        "a symlinked live mirror is never copied"
    );
    let sync = heredoc(INSTALL, "BACKUP_SYNC_EOF");
    for line in sync
        .lines()
        .filter(|line| line.trim_start().starts_with("cp "))
    {
        assert!(line.contains("--remove-destination"), "{line}");
    }
}

/// Security review M2: the drive keeps as many `backup-*.zip` as data/backups/ ("Backups to keep",
/// 0 = all) and as many `pre-migrate-*.db` as the server, the newest by name; nothing else on it is
/// ever deleted - another name, a directory, a file below snapshots/ or beside it.
#[test]
fn the_backup_sync_keeps_as_many_on_the_drive_as_the_server() {
    let sandbox = tempfile::tempdir().unwrap();
    let root = sandbox.path();
    let data = root.join("data");
    let backups = data.join("backups");
    let drive = root.join("drive");
    let snapshots = drive.join("snapshots");
    for dir in [
        &backups,
        &snapshots.join("sub"),
        &snapshots.join("backup-dir.zip"),
    ] {
        std::fs::create_dir_all(dir).unwrap();
    }
    let zips = [
        "backup-20261001-020000.zip",
        "backup-20261002-020000.zip",
        "backup-20261003-020000-uploaded-1.zip",
        "backup-20261004-020000.zip",
    ];
    for name in zips {
        std::fs::write(snapshots.join(name), "old").unwrap();
    }
    std::fs::write(backups.join("backup-20261005-020000.zip"), "new").unwrap();
    let dbs = [
        "pre-migrate-0090-0091.db",
        "pre-migrate-0091-0093.db",
        "pre-migrate-0093-0094.db",
        "pre-migrate-0094-0095.db",
        "pre-migrate-0095-0096.db",
    ];
    for name in dbs {
        std::fs::write(snapshots.join(name), "db").unwrap();
    }
    let kept = [
        snapshots.join("notes.txt"),
        snapshots.join("backup-20261001.tar"),
        snapshots.join("pre-migrate-0001-0002.db.keep"),
        snapshots.join("sub/backup-20260101-020000.zip"),
        drive.join("backup-20260101-020000.zip"),
    ];
    for path in &kept {
        std::fs::write(path, "the parent's").unwrap();
    }
    let run = |retention: &str| {
        std::fs::write(
            data.join("backup_schedule.conf"),
            format!("EXTERNAL_COPY_ENABLED=true\nRETENTION_COUNT={retention}\n"),
        )
        .unwrap();
        let status = run_root_script(
            "BACKUP_SYNC_EOF",
            &[
                (
                    "/opt/kid-phone-server/data/backup_schedule.conf",
                    data.join("backup_schedule.conf").to_str().unwrap(),
                ),
                ("/opt/kid-phone-server/data", data.to_str().unwrap()),
                ("/mnt/kid-phone-server-backup", drive.to_str().unwrap()),
            ],
            &[("mountpoint", "exit 0")],
            root,
        );
        assert!(status.success(), "{status:?}");
    };
    let present = |names: &[&str]| -> Vec<String> {
        names
            .iter()
            .filter(|name| snapshots.join(name).exists())
            .map(|name| name.to_string())
            .collect()
    };

    // 0 keeps them all, as on the server.
    run("0");
    assert_eq!(present(&zips), zips);
    assert!(snapshots.join("backup-20261005-020000.zip").is_file());

    run("2");
    assert_eq!(present(&zips), ["backup-20261004-020000.zip"]);
    assert!(snapshots.join("backup-20261005-020000.zip").is_file());
    assert_eq!(present(&dbs), &dbs[2..], "the newest PRE_MIGRATE_KEEP");
    for path in &kept {
        assert_eq!(
            std::fs::read_to_string(path).unwrap(),
            "the parent's",
            "{path:?}"
        );
    }
    assert!(snapshots.join("backup-dir.zip").is_dir());
}

/// qa-pwa-fixes-a-code Q-6: the drive reads "Backups to keep" as the server does
/// (`backups::retention_of`): 1-4 digits with spaces around them, 0 keeps all, anything else the
/// default 14 - so a hand-edited value keeps as many there as here.
#[test]
fn the_backup_sync_reads_backups_to_keep_as_the_server() {
    const ZIPS: usize = 20;
    for value in ["abc", " 3 ", "0", "014", "99999", "", "-1", "2 2"] {
        let sandbox = tempfile::tempdir().unwrap();
        let root = sandbox.path();
        let data = root.join("data");
        let snapshots = root.join("drive/snapshots");
        std::fs::create_dir_all(data.join("backups")).unwrap();
        std::fs::create_dir_all(&snapshots).unwrap();
        for n in 0..ZIPS {
            std::fs::write(
                snapshots.join(format!("backup-202610{n:02}-020000.zip")),
                "z",
            )
            .unwrap();
        }
        std::fs::write(
            data.join("backup_schedule.conf"),
            format!("EXTERNAL_COPY_ENABLED=true\nRETENTION_COUNT={value}\n"),
        )
        .unwrap();
        let status = run_root_script(
            "BACKUP_SYNC_EOF",
            &[
                (
                    "/opt/kid-phone-server/data/backup_schedule.conf",
                    data.join("backup_schedule.conf").to_str().unwrap(),
                ),
                ("/opt/kid-phone-server/data", data.to_str().unwrap()),
                (
                    "/mnt/kid-phone-server-backup",
                    root.join("drive").to_str().unwrap(),
                ),
            ],
            &[("mountpoint", "exit 0")],
            root,
        );
        assert!(status.success(), "{value:?}: {status:?}");
        let mut left: Vec<String> = std::fs::read_dir(&snapshots)
            .unwrap()
            .map(|e| e.unwrap().file_name().to_string_lossy().to_string())
            .collect();
        left.sort();
        let keep = crate::handlers::backups::retention_of(value).map_or(ZIPS, |n| n.min(ZIPS));
        assert_eq!(left.len(), keep, "{value:?}");
        // The newest are the ones kept.
        assert_eq!(
            left.last().map(String::as_str),
            Some(format!("backup-202610{:02}-020000.zip", ZIPS - 1).as_str()),
            "{value:?}"
        );
    }
    assert_eq!(crate::handlers::backups::retention_of("abc"), Some(14));
}

/// The drive's counts are the server's: `backup_sync.sh`'s default "Backups to keep" and its
/// pre-migration copies to keep.
#[test]
fn the_backup_sync_counts_are_the_servers() {
    let sync = heredoc(INSTALL, "BACKUP_SYNC_EOF");
    for line in [
        format!(
            "RETENTION_COUNT_DEFAULT={}\n",
            crate::handlers::backups::DEFAULT_RETENTION_COUNT
        ),
        format!("PRE_MIGRATE_KEEP={}\n", crate::startup::PRE_MIGRATE_KEEP),
    ] {
        assert!(sync.contains(&line), "backup_sync.sh has no {line}");
    }
}

/// QA of the security wave, Low 1: a FIFO (or a symlink) at the flag never blocks the root
/// watcher, and runs nothing; a regular flag still runs its action.
#[test]
fn the_watcher_reads_only_a_regular_flag() {
    let sandbox = tempfile::tempdir().unwrap();
    let root = sandbox.path();
    let flag = root.join("update_requested");
    let ran = root.join("ran");
    std::fs::write(
        root.join("actions.sh"),
        format!(
            "action_app_restart() {{ echo restart >> {ran}; }}\n\
             action_app_update() {{ echo update >> {ran}; }}\n",
            ran = ran.display()
        ),
    )
    .unwrap();
    let run = || {
        run_root_script(
            "WATCHER_EOF",
            &[
                (
                    "source /opt/kid-phone-server-updater/actions.sh",
                    &format!("source {}", root.join("actions.sh").display()),
                ),
                (
                    "/opt/kid-phone-server/data/update_requested",
                    flag.to_str().unwrap(),
                ),
            ],
            &[],
            root,
        )
    };

    mkfifo(&flag);
    assert!(!run().success());
    assert!(
        std::fs::symlink_metadata(&flag).is_err(),
        "the FIFO is removed"
    );
    std::fs::write(root.join("target"), "restart\n").unwrap();
    std::os::unix::fs::symlink(root.join("target"), &flag).unwrap();
    assert!(!run().success());
    assert!(!ran.exists(), "nothing ran");

    std::fs::write(&flag, "restart\n").unwrap();
    assert!(run().success());
    assert_eq!(std::fs::read_to_string(&ran).unwrap(), "restart\n");
    assert!(!flag.exists());
    // No flag at all (the path unit raced a removal): nothing to do.
    assert!(run().success());
}

/// QA of the security wave, Low 2: root writes nothing into data/ itself - install.sh's data
/// files are written as the service user - and update.sh copies only plain database files out.
#[test]
fn root_never_creates_files_in_data() {
    let write = block(INSTALL, "write_data_file() {", "}");
    assert!(write.contains("runuser -u \"$SERVICE_USER\" --"), "{write}");
    assert!(!write.contains("mktemp"), "{write}");
    assert!(!INSTALL.contains("mktemp \"$INSTALL_DIR/data"));
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

/// Security review H1, run for real (QA of the security wave, missing tests): values injected
/// into schedule.conf run nothing as root, and an invalid one falls back to its default.
#[test]
fn the_scheduler_never_runs_what_schedule_conf_says() {
    let sandbox = tempfile::tempdir().unwrap();
    let root = sandbox.path();
    let log = root.join("actions.log");
    std::fs::write(
        root.join("actions.sh"),
        format!(
            "action_os_check() {{ echo os_check >> {log}; }}\n\
             action_os_upgrade() {{ echo os_upgrade >> {log}; }}\n\
             action_tailscale_update() {{ echo tailscale_update >> {log}; }}\n\
             action_reboot() {{ echo reboot >> {log}; }}\n",
            log = log.display()
        ),
    )
    .unwrap();
    let now = std::process::Command::new("date")
        .arg("+%H:%M")
        .output()
        .unwrap();
    let now = String::from_utf8(now.stdout).unwrap().trim().to_string();
    let pwned = root.join("pwned");
    std::fs::write(
        root.join("schedule.conf"),
        format!(
            "FREQUENCY=daily\nCHECK_TIME={now}\n\
             AUTO_APPLY_OS=true$(touch {p}1)\nAUTO_APPLY_TAILSCALE=true\n\
             AUTO_REBOOT=`touch {p}2`\nDAY_OF_WEEK=0; touch {p}3\n",
            p = pwned.display()
        ),
    )
    .unwrap();
    let status = run_root_script(
        "SCHEDULER_EOF",
        &[
            (
                "source /opt/kid-phone-server-updater/actions.sh",
                &format!("source {}", root.join("actions.sh").display()),
            ),
            (
                "/opt/kid-phone-server/data/schedule.conf",
                root.join("schedule.conf").to_str().unwrap(),
            ),
            (
                "/opt/kid-phone-server-updater/last_run_date",
                root.join("last_run_date").to_str().unwrap(),
            ),
        ],
        &[],
        root,
    );
    assert!(status.success(), "{status:?}");
    for n in 1..=3 {
        assert!(
            !root.join(format!("pwned{n}")).exists(),
            "injection {n} ran"
        );
    }
    // The time matched (it was valid); the injected AUTO_APPLY_OS fell back to false.
    assert_eq!(
        std::fs::read_to_string(&log).unwrap(),
        "os_check\ntailscale_update\n"
    );
}
