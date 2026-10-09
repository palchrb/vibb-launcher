//! The admin and phone listeners (design 22 §2, §9 "routers and the gate").

/// Every device route lives in `device_routes.rs` (design 22 S0): a route added to `main.rs`
/// would reach the admin listener only, and the phone listener's tests (built from the same
/// `device_routes()`) would never see it.
#[test]
fn main_rs_has_no_device_route_literal() {
    let main = include_str!("../main.rs");
    assert!(
        !main.contains("\"/api/devices"),
        "a device route in main.rs - add it to device_routes.rs"
    );
}
