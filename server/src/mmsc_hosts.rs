//! Design 77 D10: the carriers' MMS servers, by the SIM's MCC/MNC. The phone fetches an MMS only from a host on this
//! list for its SIM (`X-Mms-Content-Location`), and an unknown MCC/MNC fails closed ("Picture can't be fetched").
//! Compiled in, not parent-editable in v1: a change is a server release. Sent as `call_policy.mmsc_hosts`.
//!
//! **Filling it (design 77 step 0).** For each carrier, on the phone with that SIM:
//! - `mccmnc`: `TelephonyManager.getSimOperator()` - the MCC and MNC run together, 5 or 6 digits (`"242..."` in
//!   Norway); an MVNO on another network has its own row with its own MCC/MNC;
//! - `hosts`: the host of `X-Mms-Content-Location` in a real m-notification-ind from that carrier (step 0 reads it),
//!   exactly as it appears there - lower case, no scheme, no port, no path; an IP literal if the carrier uses one.
//!   The phone compares it without case and accepts only http/https on ports 80, 443 or 8080 and no userinfo
//!   (`MmsGate.location` in the launcher).
//!
//! A row's shape is checked by `tests::the_table_is_well_formed`, so a typo (a scheme, a port, a duplicate operator)
//! fails the build instead of failing every MMS on that SIM closed. Never put a test or fake host here: tests use
//! `.invalid` names in their own tables.

use serde::Serialize;

/// One SIM operator's MMS servers: `mccmnc` as `TelephonyManager.getSimOperator()` gives it, the hosts exact.
#[derive(Serialize, Clone, Debug, PartialEq, Eq)]
pub struct MmscHosts {
    pub mccmnc: &'static str,
    pub hosts: &'static [&'static str],
}

/// Empty until design 77's step 0 has read each carrier's MMSC host from a real m-notification-ind (Telia, Telenor,
/// Ice and their MVNOs): with no row every MMS fails closed. A row looks like
/// `MmscHosts { mccmnc: "24201", hosts: &["mms.example-carrier.no"] }` (the host here is a placeholder, not a carrier's).
const TABLE: &[MmscHosts] = &[];

/// The table as the policy sends it.
pub fn policy() -> Vec<MmscHosts> {
    TABLE.to_vec()
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Why a row can't be used, or `None`: the shape `MmsGate.location` on the phone can match.
    fn row_problem(row: &MmscHosts) -> Option<String> {
        let mccmnc = row.mccmnc;
        if !(5..=6).contains(&mccmnc.len()) || !mccmnc.bytes().all(|b| b.is_ascii_digit()) {
            return Some(format!("mccmnc {mccmnc:?} is not 5-6 digits"));
        }
        if row.hosts.is_empty() {
            return Some(format!("{mccmnc}: no hosts"));
        }
        for host in row.hosts {
            let shape = !host.is_empty()
                && host.len() <= 253
                && !host.starts_with('.')
                && !host.ends_with('.')
                && !host.contains("..")
                && host.bytes().all(|b| {
                    b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'.' || b == b'-'
                });
            if !shape {
                return Some(format!(
                    "{mccmnc}: host {host:?} is not a lower-case host name (no scheme, port or path)"
                ));
            }
        }
        let mut sorted: Vec<_> = row.hosts.to_vec();
        sorted.sort_unstable();
        sorted.dedup();
        if sorted.len() != row.hosts.len() {
            return Some(format!("{mccmnc}: a host twice"));
        }
        None
    }

    fn problems(table: &[MmscHosts]) -> Vec<String> {
        let mut out: Vec<String> = table.iter().filter_map(row_problem).collect();
        let mut operators: Vec<_> = table.iter().map(|r| r.mccmnc).collect();
        operators.sort_unstable();
        let before = operators.len();
        operators.dedup();
        if operators.len() != before {
            out.push("an MCC/MNC twice".into());
        }
        out
    }

    #[test]
    fn the_table_is_well_formed() {
        assert_eq!(problems(TABLE), Vec::<String>::new());
        assert_eq!(policy().len(), TABLE.len());
    }

    #[test]
    fn the_shape_check_catches_what_would_fail_closed() {
        let good = MmscHosts {
            mccmnc: "24299",
            hosts: &["mmsc.carrier.invalid", "10.0.0.9"],
        };
        assert_eq!(problems(std::slice::from_ref(&good)), Vec::<String>::new());
        for bad in [
            MmscHosts {
                mccmnc: "2429",
                hosts: &["mmsc.carrier.invalid"],
            },
            MmscHosts {
                mccmnc: "24299x",
                hosts: &["mmsc.carrier.invalid"],
            },
            MmscHosts {
                mccmnc: "24299",
                hosts: &[],
            },
            MmscHosts {
                mccmnc: "24299",
                hosts: &["http://mmsc.carrier.invalid"],
            },
            MmscHosts {
                mccmnc: "24299",
                hosts: &["mmsc.carrier.invalid:8080"],
            },
            MmscHosts {
                mccmnc: "24299",
                hosts: &["MMSC.carrier.invalid"],
            },
            MmscHosts {
                mccmnc: "24299",
                hosts: &["mmsc.carrier.invalid/mms"],
            },
            MmscHosts {
                mccmnc: "24299",
                hosts: &["mmsc.carrier.invalid", "mmsc.carrier.invalid"],
            },
        ] {
            assert_eq!(problems(std::slice::from_ref(&bad)).len(), 1, "{bad:?}");
        }
        assert_eq!(
            problems(&[good.clone(), good]),
            vec!["an MCC/MNC twice".to_string()]
        );
    }
}
