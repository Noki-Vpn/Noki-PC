//go:build windows

package dialer

import (
	"context"
	"net"
	"net/netip"
	"syscall"
	"testing"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/common/windivert"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common/control"
	M "github.com/sagernet/sing/common/metadata"
	"github.com/sagernet/sing/service"
)

func TestPCUDPListenerPreservesDNSReservation(t *testing.T) {
	address := netip.MustParseAddr("127.0.0.1")
	ports, err := windivert.NewDNSPorts([]netip.Addr{address})
	if err != nil {
		t.Fatal(err)
	}
	defer ports.Close()
	instance, err := NewDefault(context.Background(), option.DialerOptions{})
	if err != nil {
		t.Fatal(err)
	}
	socket, err := instance.ListenPacket(context.Background(), M.SocksaddrFrom(address, 53))
	if err != nil {
		t.Fatal(err)
	}
	defer socket.Close()
	actual := socket.LocalAddr().(*net.UDPAddr).Port
	block := ports.Block(false, false)
	if actual < int(block.Start) || actual >= int(block.Start)+int(block.Count) {
		t.Fatalf("core DNS socket uses unreserved port %d outside [%d,%d); capture would intercept its own request", actual, block.Start, int(block.Start)+int(block.Count))
	}
}

type pcRouteOwner struct {
	adapter.NetworkManager
	selected string
}

func (m *pcRouteOwner) InterfaceFinder() control.InterfaceFinder {
	return control.NewDefaultInterfaceFinder()
}
func (m *pcRouteOwner) DefaultOptions() adapter.NetworkOptions   { return adapter.NetworkOptions{} }
func (m *pcRouteOwner) AutoDetectInterface() bool                { return true }
func (m *pcRouteOwner) AutoRedirectOutputMarkFunc() control.Func { return nil }
func (m *pcRouteOwner) AutoDetectInterfaceFunc() control.Func {
	return func(string, string, syscall.RawConn) error { m.selected = "default"; return nil }
}
func (m *pcRouteOwner) DestinationInterfaceFunc() control.Func {
	return func(string, string, syscall.RawConn) error { m.selected = "destination"; return nil }
}
func TestPCProxyTransportKeepsDefaultInterfaceWhileDirectUsesDestinationRoute(t *testing.T) {
	owner := &pcRouteOwner{}
	ctx := service.ContextWith[adapter.NetworkManager](context.Background(), owner)
	for _, direct := range []bool{false, true} {
		instance, err := NewWithOptions(Options{Context: ctx, DirectOutbound: direct})
		if err != nil {
			t.Fatal(err)
		}
		socket, err := instance.DialContext(ctx, "udp", M.SocksaddrFrom(netip.MustParseAddr("127.0.0.1"), 9))
		if err != nil {
			t.Fatal(err)
		}
		socket.Close()
		expected := "default"
		if direct {
			expected = "destination"
		}
		if owner.selected != expected {
			t.Fatalf("direct=%v: selected %s route owner instead of %s", direct, owner.selected, expected)
		}
	}
}
