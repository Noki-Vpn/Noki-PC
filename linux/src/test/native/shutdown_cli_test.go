package main

import (
	"context"
	"encoding/json"
	"github.com/sagernet/sing-box/include"
	"github.com/sagernet/sing/service"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"testing"
	"time"
)

func TestPCShutdownClosesCLI(t *testing.T) {
	binary := os.Getenv("PC_NATIVE_TEST_BINARY")
	if binary == "" {
		t.Skip("PC_NATIVE_TEST_BINARY is set by the native runtime build")
	}
	reserve := func() string {
		listener, err := net.Listen("tcp", "127.0.0.1:0")
		if err != nil {
			t.Fatal(err)
		}
		address := listener.Addr().String()
		listener.Close()
		return address
	}
	controller, mixed := reserve(), reserve()
	_, mixedPort, _ := net.SplitHostPort(mixed)
	for cycle := 1; cycle <= 3; cycle++ {
		directory := t.TempDir()
		config := filepath.Join(directory, "config.json")
		logPath, _ := json.Marshal(filepath.Join(directory, "core.log"))
		content := `{"log":{"level":"debug","output":` + string(logPath) + `},"inbounds":[{"type":"mixed","listen":"127.0.0.1","listen_port":` + mixedPort + `}],"outbounds":[{"type":"direct"}],"experimental":{"clash_api":{"external_controller":"` + controller + `","secret":"fixture-secret"}}}`
		if err := os.WriteFile(config, []byte(content), 0600); err != nil {
			t.Fatal(err)
		}
		report := filepath.Join(directory, "startup.txt")
		command := exec.Command(binary, "run", "-c", config, "--startup-report", report)
		if err := command.Start(); err != nil {
			t.Fatal(err)
		}
		exited := make(chan error, 1)
		go func() { exited <- command.Wait() }()
		finished := false
		defer func() {
			if !finished {
				command.Process.Kill()
				<-exited
			}
		}()
		deadline := time.Now().Add(10 * time.Second)
		for {
			if data, _ := os.ReadFile(report); string(data) == "ready" {
				break
			}
			if time.Now().After(deadline) {
				t.Fatal("CLI startup deadline exceeded")
			}
			select {
			case err := <-exited:
				finished = true
				t.Fatalf("CLI exited before readiness: %v", err)
			default:
			}
			time.Sleep(20 * time.Millisecond)
		}
		client := &http.Client{Timeout: time.Second, Transport: &http.Transport{Proxy: nil}}
		defer client.CloseIdleConnections()
		request, _ := http.NewRequest(http.MethodPost, "http://"+controller+"/noki/shutdown", nil)
		request.Header.Set("Authorization", "Bearer fixture-secret")
		start := time.Now()
		response, err := client.Do(request)
		if err != nil {
			t.Fatal(err)
		}
		response.Body.Close()
		if response.StatusCode != 204 {
			t.Fatalf("shutdown status %d", response.StatusCode)
		}
		select {
		case err := <-exited:
			finished = true
			if err != nil {
				t.Fatalf("CLI failed to exit cleanly: %v", err)
			}
		case <-time.After(2 * time.Second):
			t.Fatal("CLI did not finish standard Box.Close within 2s")
		}
		t.Logf("cycle %d authenticated shutdown and exit0: %s", cycle, time.Since(start))
		for _, address := range []string{mixed, controller} {
			listener, err := net.Listen("tcp", address)
			if err != nil {
				t.Fatalf("Box.Close did not release %s: %v", address, err)
			}
			listener.Close()
		}
	}
}

func TestPCShutdownPartialStartupClosesMixedListener(t *testing.T) {
	previous := globalCtx
	globalCtx = include.Context(service.ContextWithDefaultRegistry(context.Background()))
	defer func() { globalCtx = previous }()
	occupied, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer occupied.Close()
	mixed, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	address := mixed.Addr().String()
	_, port, _ := net.SplitHostPort(address)
	mixed.Close()
	config := filepath.Join(t.TempDir(), "config.json")
	content := `{"log":{"disabled":true},"inbounds":[{"type":"mixed","listen":"127.0.0.1","listen_port":` + port + `}],"outbounds":[{"type":"direct"}],"experimental":{"clash_api":{"external_controller":"` + occupied.Addr().String() + `","secret":"fixture-secret"}}}`
	if err := os.WriteFile(config, []byte(content), 0600); err != nil {
		t.Fatal(err)
	}
	entry, err := readConfigAt(config)
	if err != nil {
		t.Fatal(err)
	}
	if instance, _, _, err := create(entry.options); err == nil {
		instance.Close()
		t.Fatal("occupied Clash listener did not reject startup")
	}
	reopened, err := net.Listen("tcp", address)
	if err != nil {
		t.Fatalf("partial startup leaked the mixed listener: %v", err)
	}
	reopened.Close()
}
