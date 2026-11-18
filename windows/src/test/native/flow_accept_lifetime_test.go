package tun

import (
	"encoding/binary"
	"net/netip"
	"testing"
	"time"

	"github.com/sagernet/sing-tun/gtcpip/header"
)

func pcAcceptKey(port uint16) flowKey {
	return flowKey{protocol: uint8(header.TCPProtocolNumber),
		source:      netip.AddrPortFrom(netip.MustParseAddr("192.0.2.1"), port),
		destination: netip.MustParseAddrPort("198.51.100.1:443")}
}

func pcAcceptHit(d *ForwardDispatcher, key flowKey, entry *flowEntry, flags header.TCPFlags, now time.Duration) bool {
	return d.handleHit(key, entry, &forwardPacket{protocol: uint8(header.TCPProtocolNumber), tcpFlags: flags}, nil, int64(now))
}

func TestPCAcceptFINLifetimeAndTupleReuse(t *testing.T) {
	d := NewForwardDispatcher(nil, nil, nil, 0, 0)
	key := pcAcceptKey(50000)
	entry := &flowEntry{action: ActionAccept, idle: tcpEstablishedTimeout}
	d.table[key] = entry
	for _, flags := range []header.TCPFlags{header.TCPFlagFin | header.TCPFlagAck, header.TCPFlagAck} {
		if pcAcceptHit(d, key, entry, flags, time.Second) {
			t.Fatal("accepted TCP must still reach the existing gVisor connection")
		}
		if entry.idle != tcpClosingTimeout || entry.deadline != int64(time.Second+tcpClosingTimeout) {
			t.Fatalf("FIN/ACK retained completed metadata: idle=%v deadline=%v", entry.idle, entry.deadline)
		}
	}
	pcAcceptHit(d, key, entry, header.TCPFlagSyn, 2*time.Second)
	if entry.idle != tcpTransitoryTimeout {
		t.Fatalf("tuple reuse kept the old closing state: %v", entry.idle)
	}
	pcAcceptHit(d, key, entry, header.TCPFlagAck, 3*time.Second)
	if entry.idle != tcpEstablishedTimeout {
		t.Fatal("new connection must retain its established classification")
	}
	pcAcceptHit(d, key, entry, header.TCPFlagRst, 4*time.Second)
	if _, exists := d.table[key]; exists {
		t.Fatal("RST must remove accepted metadata")
	}
}

func TestPCAcceptExpiryKeepsNonSYNOnTheStackPath(t *testing.T) {
	d := NewForwardDispatcher(nil, nil, nil, 0, 0)
	key := pcAcceptKey(50000)
	entry := &flowEntry{action: ActionAccept, idle: tcpEstablishedTimeout}
	d.table[key] = entry
	pcAcceptHit(d, key, entry, header.TCPFlagFin, 0)
	d.epoch = time.Now().Add(-11 * time.Second)
	packet := make([]byte, 41)
	packet[0], packet[8], packet[9] = 0x45, 64, 6
	binary.BigEndian.PutUint16(packet[2:4], uint16(len(packet)))
	source, destination := key.source.Addr().As4(), key.destination.Addr().As4()
	copy(packet[12:16], source[:])
	copy(packet[16:20], destination[:])
	binary.BigEndian.PutUint16(packet[20:22], key.source.Port())
	binary.BigEndian.PutUint16(packet[22:24], key.destination.Port())
	packet[32], packet[33], packet[40] = 5<<4, byte(header.TCPFlagAck), 7
	parsed, ok := parseForwardPacket(packet)
	if !ok || !parsed.hasFlow || parsed.flowKey() != key {
		t.Fatal("invalid TCP data fixture")
	}
	// A nil handler would panic if expiry reclassified this packet as a new flow.
	if d.Dispatch(packet) {
		t.Fatal("metadata expiry must not consume half-closed TCP data")
	}
	if _, exists := d.table[key]; exists {
		t.Fatal("closed metadata survived its grace period")
	}
}

func TestPCAcceptClosedEntriesAreReclaimedAtCapacity(t *testing.T) {
	d := NewForwardDispatcher(nil, nil, nil, 0, 0)
	for port := 0; port < flowTableCapacity; port++ {
		key := pcAcceptKey(uint16(port + 1))
		entry := &flowEntry{action: ActionAccept, idle: tcpEstablishedTimeout}
		d.table[key] = entry
		pcAcceptHit(d, key, entry, header.TCPFlagFin, 0)
		pcAcceptHit(d, key, entry, header.TCPFlagAck, time.Second)
	}
	d.maybeSweep(int64(flowSweepInterval + time.Second))
	if got := len(d.table); got != flowTableCapacity-flowSweepLimit {
		t.Fatalf("completed classifications still create capacity pressure: %d", got)
	}
}
