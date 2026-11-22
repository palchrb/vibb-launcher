//! Why a catalog install failed on a phone (upstream review 2026-11-20, `docs/review/2026-11-20-upstream-review.md`):
//! the launcher's `POST /api/devices/apps/progress` failure carries a short code - Android's own `INSTALL_FAILED_*`
//! (or `INSTALL_PARSE_FAILED_*`) name read from the front of its status message, else the `STATUS_FAILURE_*` name of
//! the session's status, else one of the launcher's own [KINDS] - never Android's free text (paths, package names).
//! Kept only in that form ([clean]); Device › Apps words it ([words]). The contract is
//! `testdata/install_failure.json`, byte-identical in the launcher's test resources (`InstallFailureTest`).

use crate::i18n::{Lang, t};

/// The launcher's own reasons, before Android was asked: the download failed its hash again, the APK
/// failed the launcher's own package-and-key check, the install session couldn't start.
pub const KINDS: &[&str] = &["apk_refused", "download_corrupt", "not_started"];

/// The longest code kept (the launcher sends none longer).
pub const MAX_CODE: usize = 80;

/// Android's code families the launcher may send.
const PREFIXES: &[&str] = &[
    "INSTALL_FAILED_",
    "INSTALL_PARSE_FAILED_",
    "STATUS_FAILURE_",
];

/// The report's `reason` as stored: one of [KINDS], or an Android code name (a known prefix, then upper-case
/// letters, digits and `_`, at most [MAX_CODE] characters); anything else - a sentence, a path, a number - is no
/// reason, never a refused report.
pub fn clean(value: Option<&serde_json::Value>) -> Option<String> {
    let text = value?.as_str()?;
    if KINDS.contains(&text) {
        return Some(text.to_string());
    }
    let code = text.len() <= MAX_CODE
        && PREFIXES.iter().any(|prefix| {
            text.strip_prefix(prefix).is_some_and(|rest| {
                !rest.is_empty()
                    && rest
                        .bytes()
                        .all(|b| b.is_ascii_uppercase() || b.is_ascii_digit() || b == b'_')
            })
        });
    code.then(|| text.to_string())
}

/// A stored reason in `lang`, short (Words in the UI): a known one worded, any other as Android named it.
pub fn words(lang: Lang, reason: &str) -> String {
    let key = match reason {
        "INSTALL_FAILED_OLDER_SDK" => "device.apps.failure.newer_android",
        "INSTALL_FAILED_DEPRECATED_SDK_VERSION" => "device.apps.failure.too_old",
        "INSTALL_FAILED_INSUFFICIENT_STORAGE" | "STATUS_FAILURE_STORAGE" => {
            "device.apps.failure.storage"
        }
        "INSTALL_FAILED_UPDATE_INCOMPATIBLE" | "INSTALL_FAILED_SHARED_USER_INCOMPATIBLE" => {
            "device.apps.failure.other_key"
        }
        "INSTALL_FAILED_VERSION_DOWNGRADE" => "device.apps.failure.downgrade",
        "INSTALL_FAILED_NO_MATCHING_ABIS" | "INSTALL_FAILED_CPU_ABI_INCOMPATIBLE" => {
            "device.apps.failure.abi"
        }
        "STATUS_FAILURE_INCOMPATIBLE" => "device.apps.failure.incompatible",
        "INSTALL_FAILED_USER_RESTRICTED" | "STATUS_FAILURE_BLOCKED" => {
            "device.apps.failure.blocked"
        }
        "INSTALL_FAILED_ABORTED" | "STATUS_FAILURE_ABORTED" => "device.apps.failure.aborted",
        "INSTALL_FAILED_CONFLICTING_PROVIDER"
        | "INSTALL_FAILED_DUPLICATE_PERMISSION"
        | "STATUS_FAILURE_CONFLICT" => "device.apps.failure.conflict",
        "INSTALL_FAILED_INVALID_APK" | "STATUS_FAILURE_INVALID" => "device.apps.failure.unreadable",
        parse if parse.starts_with("INSTALL_PARSE_FAILED_") => "device.apps.failure.unreadable",
        "STATUS_FAILURE_TIMEOUT" => "device.apps.failure.timeout",
        "download_corrupt" => "device.apps.failure.download_corrupt",
        "apk_refused" => "device.apps.failure.apk_refused",
        "not_started" => "device.apps.failure.not_started",
        other => return other.to_string(),
    };
    t(lang, key).to_string()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn contract() -> serde_json::Value {
        serde_json::from_str(&std::fs::read_to_string("testdata/install_failure.json").unwrap())
            .unwrap()
    }

    #[test]
    fn the_contract_cleans_and_words_every_row() {
        let contract = contract();
        let kinds: Vec<&str> = contract["kinds"]
            .as_array()
            .unwrap()
            .iter()
            .map(|k| k.as_str().unwrap())
            .collect();
        assert_eq!(kinds, KINDS);
        for row in contract["clean"].as_array().unwrap() {
            assert_eq!(
                clean(Some(&row["sent"])).as_deref(),
                row["kept"].as_str(),
                "{row}"
            );
        }
        // Every reason the launcher makes is kept whole, and worded.
        let made = contract["parse"]
            .as_array()
            .unwrap()
            .iter()
            .filter_map(|row| row["reason"].as_str())
            .chain(KINDS.iter().copied())
            .chain(contract["report"]["reason"].as_str());
        for reason in made {
            assert_eq!(
                clean(Some(&serde_json::json!(reason))).as_deref(),
                Some(reason)
            );
            for lang in [Lang::Nb, Lang::En] {
                let worded = words(lang, reason);
                assert!(
                    !worded.is_empty() && worded != reason && !worded.contains('.'),
                    "{reason}: {worded}"
                );
            }
        }
        assert_eq!(clean(None), None);
        // A code without words is shown as Android named it.
        assert_eq!(
            words(Lang::Nb, "INSTALL_FAILED_MISSING_SHARED_LIBRARY"),
            "INSTALL_FAILED_MISSING_SHARED_LIBRARY"
        );
        assert_eq!(
            words(Lang::En, "INSTALL_FAILED_OLDER_SDK"),
            "Needs a newer Android"
        );
    }

    #[test]
    fn the_contract_is_identical_to_the_launchers_copy() {
        assert_eq!(
            std::fs::read_to_string("testdata/install_failure.json").unwrap(),
            std::fs::read_to_string("../launcher/app/src/test/resources/install_failure.json")
                .unwrap()
        );
    }
}
