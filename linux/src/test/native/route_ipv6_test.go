package route

import (
	"context"
	"net/netip"
	"testing"

	"github.com/sagernet/sing-box/adapter"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/log"
	R "github.com/sagernet/sing-box/route/rule"
	"github.com/sagernet/sing-tun"
	"github.com/sagernet/sing-tun/gtcpip/header"
)

type pcIPv6RouteManager struct {
	adapter.NetworkManager
	reachable bool
	calls     []netip.Addr
}

func (m *pcIPv6RouteManager) DestinationInterface(destination netip.Addr) (string, int, error) {
	m.calls = append(m.calls, destination)
	if m.reachable {
		return "Corporate IPv6 route", 7, nil
	}
	return "", 0, tun.ErrNoRoute
}

type pcPreMatchDNS struct{ adapter.DNSRouter }

func (*pcPreMatchDNS) LookupReverseMapping(netip.Addr) (string, bool) { return "corp.test", true }

type pcPreMatchTransports struct{ adapter.DNSTransportManager }

func (*pcPreMatchTransports) FakeIP() adapter.FakeIPTransport { return nil }

type pcPreMatchRule struct {
	adapter.Rule
	action adapter.RuleAction
	calls  int
}

func (r *pcPreMatchRule) Match(*adapter.InboundContext) bool { r.calls++; return true }
func (r *pcPreMatchRule) String() string                     { return "PC policy fixture" }
func (r *pcPreMatchRule) Action() adapter.RuleAction         { return r.action }

func TestPCUnavailableIPv6RejectsBeforeTUNSniff(t *testing.T) {
	for _, test := range []struct {
		name        string
		destination string
		inboundType string
		guard       bool
		reachable   bool
		wantReject  bool
		wantLookup  bool
	}{
		{"unavailable direct IPv6", "[2001:db8::10]:443", C.TypeTun, true, false, true, true},
		{"working public IPv6", "[2001:db8::10]:443", C.TypeTun, true, true, false, true},
		{"specific corporate IPv6 route", "[fd00:1234::10]:3389", C.TypeTun, true, true, false, true},
		{"IPv4 fallback", "192.0.2.10:443", C.TypeTun, true, false, false, false},
		{"hijacked IPv6 DNS", "[2001:db8::53]:53", C.TypeTun, true, false, false, false},
		{"other configurations", "[2001:db8::10]:443", C.TypeTun, false, false, false, false},
		{"local probe", "[2001:db8::10]:443", C.TypeMixed, true, false, false, false},
	} {
		t.Run(test.name, func(t *testing.T) {
			manager := &pcIPv6RouteManager{reachable: test.reachable}
			sniff := &pcPreMatchRule{action: &R.RuleActionSniff{SnifferNames: []string{C.ProtocolTLS}}}
			router := &Router{
				ctx: context.Background(), logger: log.NewNOPFactory().Logger(),
				network: manager, dns: &pcPreMatchDNS{}, dnsTransport: &pcPreMatchTransports{},
				windowsIPv6DirectOnly: test.guard, rules: []adapter.Rule{sniff},
			}
			destination := netip.MustParseAddrPort(test.destination)
			verdict := adapter.JudgeFlow(router, "tun", test.inboundType, uint8(header.TCPProtocolNumber),
				netip.MustParseAddrPort("[fdfe:dcba:9876::1]:50000"), destination, nil)
			if (verdict.Action == tun.ActionReject) != test.wantReject {
				t.Fatalf("verdict=%v, want reject=%v", verdict.Action, test.wantReject)
			}
			if (len(manager.calls) == 1) != test.wantLookup || len(manager.calls) > 1 {
				t.Fatalf("destination lookup calls: %v", manager.calls)
			}
			if len(manager.calls) != 0 && manager.calls[0] != destination.Addr() {
				t.Fatal("checked a default route instead of the actual destination")
			}
			if test.wantReject && sniff.calls != 0 {
				t.Fatal("unavailable IPv6 reached sniff and could complete the local handshake")
			}
			if !test.wantReject && sniff.calls != 1 {
				t.Fatal("available direct IPv6 or unaffected traffic skipped normal policy")
			}
		})
	}
}

func TestPCWorkingIPv6StillHonorsProtectedReject(t *testing.T) {
	reject := &pcPreMatchRule{action: &R.RuleActionReject{Method: C.RuleActionRejectMethodDefault, NoDrop: true}}
	router := &Router{
		ctx: context.Background(), logger: log.NewNOPFactory().Logger(),
		network: &pcIPv6RouteManager{reachable: true}, dns: &pcPreMatchDNS{}, dnsTransport: &pcPreMatchTransports{},
		windowsIPv6DirectOnly: true, rules: []adapter.Rule{reject},
	}
	verdict := adapter.JudgeFlow(router, "tun", C.TypeTun, uint8(header.TCPProtocolNumber),
		netip.MustParseAddrPort("[fdfe:dcba:9876::1]:50000"), netip.MustParseAddrPort("[2001:db8::10]:443"), nil)
	if verdict.Action != tun.ActionReject || reject.calls != 1 {
		t.Fatal("a working physical IPv6 route bypassed the protected route policy")
	}
}
