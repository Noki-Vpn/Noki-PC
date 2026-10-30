//go:build windows

package windivert

import (
	"bufio"
	"encoding/json"
	"fmt"
	"net"
	"net/netip"
	"os"
	"os/exec"
	"strconv"
	"syscall"
	"testing"

	"golang.org/x/sys/windows"
)

func TestPCDNSPortOwnerHelper(t *testing.T) {
	if os.Getenv("PC_DNS_PORT_OWNER") != "1" {
		t.Skip("subprocess helper")
	}
	owner, err := NewDNSPorts([]netip.Addr{netip.MustParseAddr("127.0.0.1"), netip.MustParseAddr("::1")})
	if err != nil {
		t.Fatal(err)
	}
	defer owner.Close()
	pid, _ := strconv.ParseUint(os.Getenv("PC_DNS_PORT_HOLDER"), 10, 32)
	blocks, err := owner.Duplicate(uint32(pid))
	if err != nil {
		t.Fatal(err)
	}
	if err = json.NewEncoder(os.Stdout).Encode(blocks); err != nil {
		t.Fatal(err)
	}
	_, _ = bufio.NewReader(os.Stdin).ReadString('\n')
}

func TestPCDNSReservationsSurviveOwnerExit(t *testing.T) {
	executable, _ := os.Executable()
	child := exec.Command(executable, "-test.run=^TestPCDNSPortOwnerHelper$")
	child.Env = append(os.Environ(), "PC_DNS_PORT_OWNER=1", fmt.Sprintf("PC_DNS_PORT_HOLDER=%d", os.Getpid()))
	input, _ := child.StdinPipe()
	output, _ := child.StdoutPipe()
	child.Stderr = os.Stderr
	if err := child.Start(); err != nil {
		t.Fatal(err)
	}
	var blocks []DNSPortLease
	if err := json.NewDecoder(output).Decode(&blocks); err != nil {
		_ = child.Process.Kill()
		_ = child.Wait()
		t.Fatal(err)
	}
	sockets, err := HoldDNSPorts(blocks)
	if err != nil {
		_ = child.Process.Kill()
		_ = child.Wait()
		t.Fatal(err)
	}
	defer func() { ReleaseDNSPorts(sockets) }()
	_, _ = input.Write([]byte("exit\n"))
	_ = input.Close()
	if err = child.Wait(); err != nil {
		t.Fatal(err)
	}
	for _, block := range blocks {
		network, host := "udp4", "127.0.0.1"
		if block.IPv6 {
			network, host = "udp6", "::1"
		}
		if block.TCP {
			if block.IPv6 {
				network = "tcp6"
			} else {
				network = "tcp4"
			}
		}
		address := net.JoinHostPort(host, strconv.Itoa(int(block.Start)))
		if block.TCP {
			socket, err := net.Listen(network, address)
			if err == nil {
				socket.Close()
				t.Fatalf("TCP reservation escaped after owner exit: %s", address)
			}
		} else {
			socket, err := net.ListenPacket(network, address)
			if err == nil {
				socket.Close()
				t.Fatalf("UDP reservation escaped after owner exit: %s", address)
			}
		}
	}
	for _, socket := range sockets {
		_ = windows.Closesocket(socket)
	}
	sockets = nil
	for _, block := range blocks {
		kind, protocol, af := windows.SOCK_DGRAM, windows.IPPROTO_UDP, windows.AF_INET
		if block.TCP {
			kind, protocol = windows.SOCK_STREAM, windows.IPPROTO_TCP
		}
		if block.IPv6 {
			af = windows.AF_INET6
		}
		socket, err := windows.Socket(af, kind, protocol)
		if err != nil {
			t.Fatal(err)
		}
		if block.IPv6 {
			err = windows.Bind(socket, &windows.SockaddrInet6{Port: int(block.Start), Addr: [16]byte{15: 1}})
		} else {
			err = windows.Bind(socket, &windows.SockaddrInet4{Port: int(block.Start), Addr: [4]byte{127, 0, 0, 1}})
		}
		_ = windows.Closesocket(socket)
		if err != nil {
			t.Fatalf("reservation not released after holder exit: %v", err)
		}
	}
}

func TestPCDNSReservedControlPreservesWindowsSockets(t *testing.T) {
	owner, err := NewDNSPorts([]netip.Addr{netip.MustParseAddr("127.0.0.1"), netip.MustParseAddr("::1")})
	if err != nil {
		t.Fatal(err)
	}
	defer owner.Close()
	for _, ipv6 := range []bool{false, true} {
		for _, tcp := range []bool{false, true} {
			network, host := "udp4", "127.0.0.1"
			if ipv6 {
				network, host = "udp6", "::1"
			}
			destination := net.JoinHostPort(host, "9")
			if tcp {
				if ipv6 {
					network = "tcp6"
				} else {
					network = "tcp4"
				}
				listener, err := net.Listen(network, net.JoinHostPort(host, "0"))
				if err != nil {
					t.Fatal(err)
				}
				defer listener.Close()
				destination = listener.Addr().String()
				go func() {
					peer, err := listener.Accept()
					if err == nil {
						peer.Close()
					}
				}()
			}
			dialer := net.Dialer{Control: func(network, _ string, conn syscall.RawConn) error {
				return SystemDNSControl(network, net.JoinHostPort(host, "53"), conn)
			}}
			conn, err := dialer.Dial(network, destination)
			if err != nil {
				t.Fatal(err)
			}
			local, _ := netip.ParseAddrPort(conn.LocalAddr().String())
			_ = conn.Close()
			block := owner.Block(ipv6, tcp)
			if local.Port() < block.Start || uint32(local.Port()) >= uint32(block.Start)+uint32(block.Count) {
				t.Fatalf("unreserved upstream socket: %s %+v", local, block)
			}
			if local.Addr() != netip.MustParseAddr(host) {
				t.Fatalf("upstream source changed: %s", local)
			}
		}
	}
}
