package tsembed

import (
	"context"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"strings"

	"tailscale.com/net/tsaddr"
)

// The proxy's dialer (security QA of S-M1, H1). tsdial's UserDial - what tsnet's own Loopback()
// proxy and Server.Dial use - takes netstack only for a current peer (UseNetstackForIP =
// LocalBackend.PeerForIP) and dials everything else on an OS socket, a Tailscale address that
// isn't a peer included. So here:
//   - a Tailscale address (all of 100.64.0.0/10, fd7a:115c:a1e0::/48; IPv4-mapped unmapped) is
//     dialed through netstack (NetstackDialTCP, which never touches the OS network) and only
//     while it is a current peer; otherwise refused;
//   - any other address (an https server's public name) goes through UserDial as before: plain
//     internet, an exit node or a subnet route. The launcher sends nothing but https there, and
//     TLS with the system CAs covers it (ServerTransport.kt never gives this proxy plain http to
//     anything but a tailnet IP);
//   - TCP only: the launcher has no UDP through the proxy.
// A name is resolved once (UserDialPlan: MagicDNS, else the system resolver) and the IP it gave
// is what is judged and dialed, never the name again.

type route int

const (
	routeRefused route = iota
	routeNetstack
	routeUser
)

// isTailscaleAddr is the whole CGNAT range (tsaddr.IsTailscaleIPv4 leaves out ChromeOS's VM
// range, which UserDial would then dial on the OS network) and Tailscale's ULA range.
func isTailscaleAddr(ip netip.Addr) bool {
	ip = ip.Unmap()
	if ip.Is4() {
		return tsaddr.CGNATRange().Contains(ip)
	}
	return tsaddr.TailscaleULARange().Contains(ip)
}

// routeFor says how ip is dialed; isPeer is whether it is a current peer's address.
func routeFor(ip netip.Addr, isPeer func(netip.Addr) bool) route {
	if !isTailscaleAddr(ip) {
		return routeUser
	}
	if isPeer != nil && isPeer(ip.Unmap()) {
		return routeNetstack
	}
	return routeRefused
}

var errNotTCP = errors.New("tsembed proxy: TCP only")

func (c *Client) dial(ctx context.Context, network, addr string) (net.Conn, error) {
	if !strings.HasPrefix(network, "tcp") {
		return nil, errNotTCP
	}
	// A Go panic here would end the whole app process (no recover in socks5's goroutines): every
	// lookup is checked.
	sys := c.srv.Sys()
	if sys == nil {
		return nil, errors.New("tsembed proxy: not started")
	}
	d, ok := sys.Dialer.GetOK()
	if !ok || d == nil {
		return nil, errors.New("tsembed proxy: no dialer")
	}
	ipp, _, err := d.UserDialPlan(ctx, network, addr)
	if err != nil {
		return nil, err
	}
	ipp = netip.AddrPortFrom(ipp.Addr().Unmap(), ipp.Port())
	switch routeFor(ipp.Addr(), d.UseNetstackForIP) {
	case routeNetstack:
		if d.NetstackDialTCP == nil {
			return nil, errors.New("tsembed proxy: netstack not ready")
		}
		return d.NetstackDialTCP(ctx, ipp)
	case routeUser:
		return d.UserDial(ctx, network, ipp.String())
	default:
		return nil, fmt.Errorf("tsembed proxy: %v is a tailnet address but not a peer of this node", ipp.Addr())
	}
}
