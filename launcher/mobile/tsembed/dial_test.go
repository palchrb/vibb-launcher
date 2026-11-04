package tsembed

import (
	"net/netip"
	"testing"
)

func TestRouteFor(t *testing.T) {
	peer := netip.MustParseAddr("100.101.102.103")
	peer6 := netip.MustParseAddr("fd7a:115c:a1e0::5")
	isPeer := func(ip netip.Addr) bool { return ip == peer || ip == peer6 }
	for _, tc := range []struct {
		ip   string
		want route
	}{
		{"100.101.102.103", routeNetstack},
		{"::ffff:100.101.102.103", routeNetstack},
		{"fd7a:115c:a1e0::5", routeNetstack},
		// A tailnet address that isn't a peer (a re-created node, an ACL, a typo): never the OS network.
		{"100.64.0.1", routeRefused},
		{"100.127.255.254", routeRefused},
		{"100.115.92.5", routeRefused}, // ChromeOS's VM range, inside 100.64.0.0/10
		{"::ffff:100.64.0.1", routeRefused},
		{"fd7a:115c:a1e0::6", routeRefused},
		// Anything else: as before (https only, TLS covers it).
		{"100.63.255.255", routeUser},
		{"100.128.0.1", routeUser},
		{"203.0.113.5", routeUser},
		{"192.168.1.5", routeUser},
		{"fd7a:115c:a1e1::1", routeUser},
	} {
		if got := routeFor(netip.MustParseAddr(tc.ip), isPeer); got != tc.want {
			t.Errorf("routeFor(%s) = %v, want %v", tc.ip, got, tc.want)
		}
	}
	if got := routeFor(netip.MustParseAddr("100.101.102.103"), nil); got != routeRefused {
		t.Errorf("no peer check: %v, want refused", got)
	}
}
