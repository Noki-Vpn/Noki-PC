package route

import (
	"github.com/sagernet/sing-box/log"
	"net"
	"net/netip"
	"testing"
	"unsafe"
)

func TestPCDestinationRouteABIAndLoopback(t *testing.T) {
	var row destinationRouteRow
	if unsafe.Sizeof(row) != 104 || unsafe.Offsetof(row.Metric) != 84 || unsafe.Offsetof(row.PrefixLength) != 40 {
		t.Fatalf("NETIO ABI mismatch: size=%d metric=%d prefix=%d", unsafe.Sizeof(row), unsafe.Offsetof(row.Metric), unsafe.Offsetof(row.PrefixLength))
	}
	manager := &NetworkManager{logger: log.NewNOPFactory().Logger()}
	_, index, err := manager.destinationInterface(netip.MustParseAddr("127.0.0.1"))
	if err != nil {
		t.Fatal(err)
	}
	adapter, err := net.InterfaceByIndex(index)
	if err != nil || adapter.Flags&net.FlagLoopback == 0 {
		t.Fatalf("loopback selected wrong route: %v %v", adapter, err)
	}
}
