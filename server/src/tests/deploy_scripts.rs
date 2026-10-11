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
