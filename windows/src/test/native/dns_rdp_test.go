package dns

import (
	"context"
	"net"
	"net/netip"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	mDNS "github.com/miekg/dns"
	"github.com/sagernet/sing-box/adapter"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common"
	"github.com/sagernet/sing/contrab/freelru"
)

func TestPCReverseMappingKeepsAliasAndBoundsLifetime(t *testing.T) {
	r := &Router{dnsReverseMapping: common.Must1(freelru.New[netip.Addr, string](32, func(netip.Addr) uint32 { return 1 }, true))}
	query := new(mDNS.Msg).SetQuestion("RDP.Corp.Test.", mDNS.TypeA)
	response := new(mDNS.Msg).SetReply(query)
	response.Answer = []mDNS.RR{
		&mDNS.CNAME{Hdr: mDNS.RR_Header{Name: "rdp.corp.test.", Rrtype: mDNS.TypeCNAME, Class: mDNS.ClassINET, Ttl: 5}, Target: "backend.other.test."},
		&mDNS.A{Hdr: mDNS.RR_Header{Name: "backend.other.test.", Rrtype: mDNS.TypeA, Class: mDNS.ClassINET, Ttl: 60}, A: net.ParseIP("192.0.2.72").To4()},
		&mDNS.A{Hdr: mDNS.RR_Header{Name: "unrelated.test.", Rrtype: mDNS.TypeA, Class: mDNS.ClassINET, Ttl: 60}, A: net.ParseIP("192.0.2.73").To4()},
	}
	r.recordReverseMapping(query, response, nil)
	ip := netip.MustParseAddr("192.0.2.72")
	if name, ok := r.LookupReverseMapping(ip); !ok || name != "rdp.corp.test" {
		t.Fatalf("alias lost: %q %v", name, ok)
	}
	if _, ok := r.LookupReverseMapping(netip.MustParseAddr("192.0.2.73")); ok {
		t.Fatal("unrelated answer inherited alias")
	}
	_, expiry, _ := r.dnsReverseMapping.GetWithLifetime(ip)
	if remaining := time.Until(expiry); remaining <= 0 || remaining > 5*time.Second {
		t.Fatalf("CNAME lifetime escaped: %v", remaining)
	}
	// A second name sharing an IP must never inherit a possibly direct policy.
	other := new(mDNS.Msg).SetQuestion("protected.test.", mDNS.TypeA)
	otherResponse := new(mDNS.Msg).SetReply(other)
	otherResponse.Answer = []mDNS.RR{&mDNS.A{Hdr: mDNS.RR_Header{Name: "protected.test.", Rrtype: mDNS.TypeA, Class: mDNS.ClassINET, Ttl: 2}, A: net.ParseIP("192.0.2.72").To4()}}
	r.recordReverseMapping(other, otherResponse, nil)
	if _, ok := r.LookupReverseMapping(ip); ok {
		t.Fatal("ambiguous IP still has a domain policy")
	}
	r.dnsReverseMapping.AddWithLifetime(ip, "", time.Millisecond)
	time.Sleep(10 * time.Millisecond)
	r.recordReverseMapping(query, response, nil)
	if name, ok := r.LookupReverseMapping(ip); !ok || name != "rdp.corp.test" {
		t.Fatal("expired conflict persisted")
	}
	response.Answer[0].Header().Ttl = 0
	r.dnsReverseMapping.Purge()
	r.recordReverseMapping(query, response, nil)
	if _, ok := r.LookupReverseMapping(ip); ok {
		t.Fatal("zero TTL became a permanent mapping")
	}
}

type pcIPv6DNSTransport struct {
	adapter.DNSTransport
	tag       string
	answer    *mDNS.Msg
	exchanges atomic.Int32
}

func (t *pcIPv6DNSTransport) Tag() string { return t.tag }
func (*pcIPv6DNSTransport) Type() string  { return C.DNSTypeTCP }
func (t *pcIPv6DNSTransport) Exchange(_ context.Context, query *mDNS.Msg) (*mDNS.Msg, error) {
	t.exchanges.Add(1)
	response := t.answer.Copy()
	response.Id, response.Question = query.Id, query.Question
	return response, nil
}
func (t *pcIPv6DNSTransport) ExchangeAsync(ctx context.Context, query *mDNS.Msg, callback func(*mDNS.Msg, error)) {
	callback(t.Exchange(ctx, query))
}

func TestPCProtectedDNSFiltersIPv6HintsAcrossFreshAndCachedExchanges(t *testing.T) {
	query := new(mDNS.Msg).SetQuestion("service.test.", mDNS.TypeHTTPS)
	answer := new(mDNS.Msg).SetReply(query)
	answer.AuthenticatedData = true
	record := func(text string) mDNS.RR {
		t.Helper()
		rr, err := mDNS.NewRR(text)
		if err != nil {
			t.Fatal(err)
		}
		return rr
	}
	answer.Answer = []mDNS.RR{
		record("service.test. 60 IN CNAME edge.test."),
		record("edge.test. 60 IN HTTPS 1 . alpn=h2,h3 port=8443 ipv4hint=192.0.2.10 ech=AAECAw== ipv6hint=2001:db8::10"),
		record("edge.test. 60 IN HTTPS 2 ipv6-only.test. mandatory=ipv6hint ipv6hint=2001:db8::11"),
		record("alias.test. 60 IN HTTPS 0 alias-target.test."),
	}
	answer.Extra = []mDNS.RR{
		record("edge.test. 60 IN A 192.0.2.10"),
		record("edge.test. 60 IN AAAA 2001:db8::10"),
		record("svc.test. 60 IN SVCB 1 . mandatory=alpn alpn=h2 ipv4hint=192.0.2.12 ipv6hint=2001:db8::12"),
		&mDNS.RRSIG{Hdr: mDNS.RR_Header{Name: "EDGE.test.", Rrtype: mDNS.TypeRRSIG, Class: mDNS.ClassINET, Ttl: 60}, TypeCovered: mDNS.TypeHTTPS},
		&mDNS.RRSIG{Hdr: mDNS.RR_Header{Name: "edge.test.", Rrtype: mDNS.TypeRRSIG, Class: mDNS.ClassINET, Ttl: 60}, TypeCovered: mDNS.TypeAAAA},
		&mDNS.RRSIG{Hdr: mDNS.RR_Header{Name: "edge.test.", Rrtype: mDNS.TypeRRSIG, Class: mDNS.ClassINET, Ttl: 60}, TypeCovered: mDNS.TypeA},
	}
	original := answer.String()
	protected := &pcIPv6DNSTransport{tag: "protected", answer: answer}
	client := NewClient(ClientOptions{Context: context.Background(), IPv4OnlyServers: []string{"protected"}})
	router := &Router{ctx: context.Background(), logger: log.NewNOPFactory().Logger(), client: client}
	assertProtected := func(response *mDNS.Msg, err error) {
		t.Helper()
		if err != nil {
			t.Fatal(err)
		}
		text := response.String()
		if strings.Contains(text, "ipv6hint") || strings.Contains(text, "ipv6-only.test.") || response.AuthenticatedData {
			t.Fatalf("protected response retained unusable IPv6 or authentication: %s", text)
		}
		for _, retained := range []string{"alpn=", "h3", "ech=", "ipv4hint=", `port="8443"`, "alias-target.test.", "CNAME", `mandatory="alpn"`} {
			if !strings.Contains(text, retained) {
				t.Fatalf("lost %s in %s", retained, text)
			}
		}
		keptAddressSignature := false
		for _, rr := range response.Extra {
			if rr.Header().Rrtype == mDNS.TypeAAAA {
				t.Fatal("Additional AAAA bypassed policy")
			}
			if signature, ok := rr.(*mDNS.RRSIG); ok {
				if signature.TypeCovered != mDNS.TypeA {
					t.Fatal("rewritten RRset retained its invalid signature")
				}
				keptAddressSignature = true
			}
		}
		if !keptAddressSignature {
			t.Fatal("unmodified IPv4 RRset lost its signature")
		}
	}
	// Both delivery APIs must apply the same policy to new and previously cached data.
	for _, async := range []bool{false, true} {
		client.ClearCache()
		before := protected.exchanges.Load()
		for attempt := 0; attempt < 2; attempt++ {
			if async {
				completed := make(chan struct{})
				router.ExchangeAsync(context.Background(), query, adapter.DNSQueryOptions{Transport: protected}, func(response *mDNS.Msg, err error) {
					defer close(completed)
					assertProtected(response, err)
				})
				select {
				case <-completed:
				case <-time.After(time.Second):
					t.Fatal("async exchange stalled")
				}
			} else {
				assertProtected(router.Exchange(context.Background(), query, adapter.DNSQueryOptions{Transport: protected}))
			}
		}
		if protected.exchanges.Load()-before != 1 {
			t.Fatal("cached response unexpectedly queried upstream again")
		}
	}
	key := client.newCacheKey(protected, query.Question[0], query, adapter.DNSQueryOptions{})
	cached, _, _ := client.loadResponse(key)
	if cached == nil || !strings.Contains(cached.String(), "ipv6hint") || !cached.AuthenticatedData {
		t.Fatal("protected filtering mutated the underlying transport cache")
	}
	for _, tag := range []string{"system-dns-0", "nrpt-corp", "bootstrap"} {
		direct := &pcIPv6DNSTransport{tag: tag, answer: answer}
		response, err := router.Exchange(context.Background(), query, adapter.DNSQueryOptions{Transport: direct})
		if err != nil || response.String() != original {
			t.Fatalf("%s direct DNS response changed: %v", tag, err)
		}
	}
	if answer.String() != original {
		t.Fatal("filter changed the upstream response")
	}
}

func TestPCProtectedAAAAIsFilteredBeforeLookupAndReverseMapping(t *testing.T) {
	query := new(mDNS.Msg).SetQuestion("rdp.corp.test.", mDNS.TypeAAAA)
	answer := new(mDNS.Msg).SetReply(query)
	answer.Answer = []mDNS.RR{&mDNS.AAAA{Hdr: mDNS.RR_Header{Name: "rdp.corp.test.", Rrtype: mDNS.TypeAAAA, Class: mDNS.ClassINET, Ttl: 60}, AAAA: net.ParseIP("fd00:1234::10")}}
	protected := &pcIPv6DNSTransport{tag: "protected", answer: answer}
	client := NewClient(ClientOptions{Context: context.Background(), IPv4OnlyServers: []string{"protected"}})
	router := &Router{ctx: context.Background(), logger: log.NewNOPFactory().Logger(), client: client,
		dnsReverseMapping: common.Must1(freelru.New[netip.Addr, string](32, func(netip.Addr) uint32 { return 1 }, true))}
	response, err := router.Exchange(context.Background(), query, adapter.DNSQueryOptions{Transport: protected})
	if err != nil || len(response.Answer) != 0 || response.Rcode != mDNS.RcodeSuccess || protected.exchanges.Load() != 0 {
		t.Fatal("protected AAAA was not rejected before querying upstream")
	}
	key := client.newCacheKey(protected, query.Question[0], query, adapter.DNSQueryOptions{})
	client.storeCache(key, answer, 60) // An old persisted response must not bypass the new policy.
	addresses, err := client.Lookup(context.Background(), protected, "rdp.corp.test", adapter.DNSQueryOptions{Strategy: C.DomainStrategyIPv6Only}, nil)
	if err != nil || len(addresses) != 0 {
		t.Fatalf("cached lookup returned protected IPv6: %v, %v", addresses, err)
	}
	address := netip.MustParseAddr("fd00:1234::10")
	if _, ok := router.LookupReverseMapping(address); ok {
		t.Fatal("filtered AAAA entered reverse mapping")
	}
	direct := &pcIPv6DNSTransport{tag: "nrpt-corp", answer: answer}
	completed := make(chan struct{})
	router.ExchangeAsync(context.Background(), query, adapter.DNSQueryOptions{Transport: direct}, func(response *mDNS.Msg, err error) {
		defer close(completed)
		if err != nil || len(response.Answer) != 1 {
			t.Error("working corporate AAAA was filtered")
		}
	})
	select {
	case <-completed:
	case <-time.After(time.Second):
		t.Fatal("async exchange stalled")
	}
	if name, ok := router.LookupReverseMapping(address); !ok || name != "rdp.corp.test" {
		t.Fatal("direct IPv6 lost reverse mapping")
	}
}

func TestPCProtectedDNSServerTagsAreValidated(t *testing.T) {
	for _, tag := range []string{"", "missing"} {
		_, err := NewRouter(context.Background(), log.NewNOPFactory(), option.DNSOptions{RawDNSOptions: option.RawDNSOptions{
			DNSClientOptions: option.DNSClientOptions{IPv4OnlyServers: []string{tag}},
		}})
		if err == nil {
			t.Fatalf("unknown protected DNS server %q was silently accepted", tag)
		}
	}
	_, err := NewRouter(context.Background(), log.NewNOPFactory(), option.DNSOptions{RawDNSOptions: option.RawDNSOptions{
		Servers:          []option.DNSServerOptions{{Tag: "protected", Type: C.DNSTypeTCP}},
		DNSClientOptions: option.DNSClientOptions{IPv4OnlyServers: []string{"protected"}},
	}})
	if err != nil {
		t.Fatal(err)
	}
}

func TestPCReverseMappingIPv6AsyncAndCycles(t *testing.T) {
	r := &Router{dnsReverseMapping: common.Must1(freelru.New[netip.Addr, string](32, func(netip.Addr) uint32 { return 1 }, true))}
	query := new(mDNS.Msg).SetQuestion("rdp.corp.test.", mDNS.TypeAAAA)
	response := new(mDNS.Msg).SetReply(query)
	response.Answer = []mDNS.RR{
		&mDNS.CNAME{Hdr: mDNS.RR_Header{Name: "rdp.corp.test.", Rrtype: mDNS.TypeCNAME, Class: mDNS.ClassINET, Ttl: 4}, Target: "backend.other.test."},
		&mDNS.AAAA{Hdr: mDNS.RR_Header{Name: "backend.other.test.", Rrtype: mDNS.TypeAAAA, Class: mDNS.ClassINET, Ttl: 60}, AAAA: net.ParseIP("2001:db8::72")},
	}
	called := false
	r.finishExchangeAsync(query, nil, response, nil, func(answer *mDNS.Msg, err error) { called = answer == response && err == nil })
	if !called {
		t.Fatal("async callback lost")
	}
	ip := netip.MustParseAddr("2001:db8::72")
	if name, ok := r.LookupReverseMapping(ip); !ok || name != "rdp.corp.test" {
		t.Fatalf("IPv6 alias lost: %q %v", name, ok)
	}
	response.Answer = append(response.Answer, &mDNS.CNAME{Hdr: mDNS.RR_Header{Name: "backend.other.test.", Rrtype: mDNS.TypeCNAME, Class: mDNS.ClassINET, Ttl: 4}, Target: "rdp.corp.test."})
	r.dnsReverseMapping.Purge()
	r.recordReverseMapping(query, response, nil)
	if _, ok := r.LookupReverseMapping(ip); ok {
		t.Fatal("cyclic chain created a mapping")
	}
}
