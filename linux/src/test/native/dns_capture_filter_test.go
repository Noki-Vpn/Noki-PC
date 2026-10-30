package windivert

import (
	"encoding/binary"
	"net/netip"
	"testing"
)

func TestPCDNSCaptureOnlyExemptsReservedSourcePorts(t *testing.T) {
	for _, v6 := range []bool{false, true} {
		for _, tcp := range []bool{false, true} {
			ip, field := netip.MustParseAddr("192.0.2.53"), uint16(fieldUDPSrcPort)
			if v6 {
				ip = netip.MustParseAddr("2001:db8::53")
			}
			protocol := uint8(17)
			if tcp {
				protocol = 6
				field = fieldTCPSrcPort
			}
			filters, err := OutboundDNSOutsideReservation([]netip.Addr{ip}, protocol, DNSPortBlock{IPv6: v6, TCP: tcp, Start: 50000, Count: 256})
			if err != nil {
				t.Fatal(err)
			}
			for _, port := range []uint32{0, 49999, 50000, 50255, 50256, 65535} {
				matched := false
				for _, filter := range filters {
					code, _, err := filter.encode()
					if err != nil {
						t.Fatal(err)
					}
					pc := uint16(0)
					for steps := 0; pc < resultAccept && steps < 256; steps++ {
						inst := code[int(pc)*24:]
						head := binary.LittleEndian.Uint32(inst)
						key, test := uint16(head&2047), (head>>11)&31
						want := binary.LittleEndian.Uint32(inst[8:12])
						got := want
						if key == field {
							got = port
						}
						pass := got == want
						if test == testLEQ {
							pass = got <= want
						}
						if test == testGEQ {
							pass = got >= want
						}
						if pass {
							pc = uint16(head >> 16)
						} else {
							pc = uint16(binary.LittleEndian.Uint32(inst[4:8]))
						}
					}
					matched = matched || pc == resultAccept
				}
				expected := port < 50000 || port >= 50256
				if matched != expected {
					t.Fatalf("v6=%t tcp=%t port=%d capture=%t", v6, tcp, port, matched)
				}
			}
		}
	}
}
