//! Design 77 D10: the carriers' MMS servers, by the SIM's MCC/MNC. The phone fetches an MMS only from a host on this
//! list for its SIM (`X-Mms-Content-Location`), and an unknown MCC/MNC fails closed ("Picture can't be fetched").
//! Compiled in, not parent-editable in v1: a change is a server release. Sent as `call_policy.mmsc_hosts`.

use serde::Serialize;

/// One SIM operator's MMS servers: `mccmnc` as `TelephonyManager.getSimOperator()` gives it, the hosts exact.
#[derive(Serialize, Clone, Debug, PartialEq, Eq)]
pub struct MmscHosts {
    pub mccmnc: &'static str,
    pub hosts: &'static [&'static str],
}

/// Empty until design 77's step 0 has read each carrier's MMSC host from a real m-notification-ind (Telia, Telenor,
/// Ice and their MVNOs): with no row every MMS fails closed, which is what step 1 (no MMS yet) wants.
const TABLE: &[MmscHosts] = &[];

/// The table as the policy sends it.
pub fn policy() -> Vec<MmscHosts> {
    TABLE.to_vec()
}
