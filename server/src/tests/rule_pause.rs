//! Design 98 §4.4 (Foreldrekode): the shared contract `testdata/rule_pause.json` - the phone's parent-code pause of the
//! rule in force, posted to `POST /api/devices/rule-pause`.

const CONTRACT: &str = include_str!("../../testdata/rule_pause.json");

#[test]
fn the_contract_is_identical_to_the_launchers_copy() {
    let contract: serde_json::Value = serde_json::from_str(CONTRACT).unwrap();
    let lengths: Vec<i64> = contract["lengths"]
        .as_array()
        .unwrap()
        .iter()
        .map(|m| m.as_i64().unwrap())
        .collect();
    assert_eq!(lengths, crate::time_rules::RULE_LIFT_MINUTES);
    let launcher = std::fs::read_to_string(concat!(
        env!("CARGO_MANIFEST_DIR"),
        "/../launcher/app/src/test/resources/rule_pause.json"
    ))
    .unwrap();
    assert_eq!(launcher, CONTRACT, "the launcher's copy differs");
}
