// Package tsembed is a thin wrapper around tailscale.com/tsnet, existing
// solely because gomobile's gobind tool cannot generate Java/Kotlin bindings
// directly against tsnet.Server: gobind only supports methods that return
// zero or one values plus an optional error, and several of tsnet.Server's
// own methods (Loopback in particular) don't fit that shape. Every exported
// method here is deliberately gobind-compatible; anything with a richer Go
// signature (Loopback's three-value return, raw net.Conn, etc.) is called
// from plain Go inside this package and never crosses the binding boundary
// directly.
//
// The embedding pattern used here - tsnet's own Loopback() SOCKS5 proxy,
// rather than trying to bridge Dial()/net.Conn into Java - is tsnet's
// intended mechanism for exactly this "give a non-Go program tailnet
// access" use case (see Loopback's doc comment in tsnet.Server). Kotlin
// talks to the resulting loopback address as a plain SOCKS5 proxy via
// java.net.Proxy, so no Go network types ever need a Java binding at all.
package tsembed

import (
	"context"
	"errors"
	"net"
	"os"
	"path/filepath"
	"time"

	"github.com/wlynxg/anet"
	"tailscale.com/net/netmon"
	"tailscale.com/tsnet"
)

func init() {
	// TS_NO_LOGS_NO_SUPPORT is Tailscale's own documented opt-out of client
	// log uploads - desirable on its own merits regardless of the bug below
	// (an embedded tailnet client on a kid's device shouldn't be uploading
	// operational logs to Tailscale's servers). Confirmed live this alone
	// does NOT fix the netlink crash below (same panic either way) - kept
	// for the privacy reason, not as the actual fix.
	os.Setenv("TS_NO_LOGS_NO_SUPPORT", "true")

	// The actual fix, confirmed against the real root cause: tsnet.Server.Start
	// (and, separately, logpolicy's log-upload transport setup) both call
	// Go's standard net.Interfaces() internally, which fails outright on
	// Android with "route ip+net: netlinkrib: permission denied" - Android's
	// SELinux policy blocks unprivileged apps from netlink route-table
	// queries entirely (this is a real, currently-unresolved upstream gap -
	// see tailscale/tailscale#17311 - distinct from a similarly-named but
	// already-fixed issue, #9836, whose fix only covered a different call
	// path via Android build tags in wgengine/router).
	//
	// netmon.RegisterInterfaceGetter lets a caller substitute the interface-
	// enumeration implementation tsnet uses internally - this registers
	// github.com/wlynxg/anet's Android-compatible implementation (a small
	// library purpose-built for exactly this problem, referencing upstream
	// Go issues golang/go#68082 and golang/go#40569), the same mechanism
	// Tailscale's own official Android app uses. Must run before any
	// tsnet.Server method is called.
	netmon.RegisterInterfaceGetter(androidInterfaceGetter)
}

func androidInterfaceGetter() ([]netmon.Interface, error) {
	ifs, err := anet.Interfaces()
	if err != nil {
		return nil, err
	}
	result := make([]netmon.Interface, 0, len(ifs))
	for i := range ifs {
		ifi := ifs[i]
		addrs, err := anet.InterfaceAddrsByInterface(&ifi)
		if err != nil {
			// Best effort - an interface we can't get addresses for just
			// gets reported with none, rather than dropping it (and
			// whatever info it does have) entirely.
			addrs = nil
		}
		result = append(result, netmon.Interface{
			Interface: &ifi,
			AltAddrs:  addrs,
		})
	}
	return result, nil
}

var _ = net.Interface{} // keep "net" import even if only used via anet's return types above

// Client owns one embedded tailnet node. Not safe for concurrent use across
// goroutines/threads without external synchronization - callers should treat
// it as owned by a single Kotlin-side service instance.
type Client struct {
	srv          *tsnet.Server
	proxyAddr    string
	proxyCred    string
	localAPICred string
}

// New creates (but does not yet connect) a client. stateDir must be a
// directory the app can write to (e.g. context.filesDir) - tsnet persists
// its node identity/keys there so the device doesn't need to re-authenticate
// with authKey on every restart.
func New(hostname, authKey, stateDir string) *Client {
	// logpolicy.LogsDir (called internally by tsnet.Server.Start, separately
	// from Server.Dir above) searches TS_LOGS_DIR, then a handful of
	// platform-specific defaults, then os.UserCacheDir(), then the working
	// directory, then a temp dir - and panics with "no safe place found to
	// store log state" if every candidate fails. On Android none of those
	// work: no platform default exists, os.UserCacheDir() errors outright
	// since $HOME/$XDG_CACHE_HOME are never set in an app process, and the
	// working directory isn't writable. Pointing TS_LOGS_DIR at a subdir of
	// our own already-writable stateDir short-circuits the whole search.
	// TS_NO_LOGS_NO_SUPPORT (set below) only skips uploading this state
	// somewhere, not the local directory search itself, so both are needed.
	logsDir := filepath.Join(stateDir, "logs")
	os.MkdirAll(logsDir, 0700)
	os.Setenv("TS_LOGS_DIR", logsDir)

	return &Client{
		srv: &tsnet.Server{
			Hostname:  hostname,
			AuthKey:   authKey,
			Dir:       stateDir,
			Ephemeral: false,
		},
	}
}

// Up connects to the tailnet and blocks until either connected or
// timeoutSeconds elapses. Returns this node's own Tailscale IPv4 address on
// success. Safe to call again after a failure (tsnet.Server.Start(), which
// this calls internally via Up, is idempotent).
func (c *Client) Up(timeoutSeconds int) (string, error) {
	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(timeoutSeconds)*time.Second)
	defer cancel()

	if _, err := c.srv.Up(ctx); err != nil {
		return "", err
	}
	ip4, _ := c.srv.TailscaleIPs()
	if !ip4.IsValid() {
		return "", errors.New("connected but no IPv4 address assigned yet")
	}
	return ip4.String(), nil
}

// StartProxy starts (or returns the already-running) local SOCKS5 proxy onto
// the tailnet, returning the loopback address to point a SOCKS5 client at
// (e.g. "127.0.0.1:1080"). Call ProxyUsername/ProxyPassword afterward for the
// credentials the proxy requires - tsnet.Server.Loopback returns three
// values plus an error, which gobind can't bind directly (see package doc),
// so this wraps it and caches the credentials as separate zero-argument
// getters instead.
func (c *Client) StartProxy() (string, error) {
	if c.proxyAddr != "" {
		return c.proxyAddr, nil
	}
	addr, proxyCred, localAPICred, err := c.srv.Loopback()
	if err != nil {
		return "", err
	}
	c.proxyAddr = addr
	c.proxyCred = proxyCred
	c.localAPICred = localAPICred
	return addr, nil
}

// ProxyCredential returns the credential tsnet's local SOCKS5 proxy requires
// for authentication (opaque from this package's perspective - passed
// through exactly as tsnet.Server.Loopback returned it). Empty until
// StartProxy has succeeded at least once. Exact format/usage against
// java.net.Authenticator to be confirmed once this is wired up client-side.
func (c *Client) ProxyCredential() string {
	return c.proxyCred
}

// Close shuts down the tailnet connection and proxy.
func (c *Client) Close() error {
	return c.srv.Close()
}
