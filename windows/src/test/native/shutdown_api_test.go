package clashapi

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/sagernet/sing-box/common/trafficcontrol"
	"github.com/sagernet/sing-box/common/urltest"
	"github.com/sagernet/sing-box/experimental/clashmode"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/service"
)

func TestPCShutdownAuthenticationAndLifecycle(t *testing.T) {
	for _, tc := range []struct {
		name, secret, token, remote string
		callback                    bool
		status                      int
	}{
		{"authorized", "secret", "secret", "127.0.0.1:1234", true, 204},
		{"ipv6", "secret", "secret", "[::1]:1234", true, 204},
		{"no secret", "", "", "127.0.0.1:1234", true, 404},
		{"wrong token", "secret", "wrong", "127.0.0.1:1234", true, 401},
		{"no token", "secret", "", "127.0.0.1:1234", true, 401},
		{"remote", "secret", "secret", "192.0.2.1:1234", true, 403},
		{"malformed remote", "secret", "secret", "localhost", true, 403},
		{"no callback", "secret", "secret", "127.0.0.1:1234", false, 503},
	} {
		t.Run(tc.name, func(t *testing.T) {
			ctx := service.ContextWithDefaultRegistry(context.Background())
			service.MustRegisterPtr(ctx, trafficcontrol.NewManager(nil))
			service.MustRegisterPtr(ctx, &urltest.HistoryStorage{})
			service.MustRegisterPtr(ctx, clashmode.NewManager(ctx, log.NewNOPFactory().NewLogger("test"), "Rule", nil))
			response := httptest.NewRecorder()
			called := false
			if tc.callback {
				ctx = service.ContextWith(ctx, context.CancelFunc(func() {
					called = true
					if !response.Flushed || response.Code != http.StatusNoContent {
						t.Error("shutdown callback ran before the 204 response was flushed")
					}
				}))
			}
			instance, err := NewServer(ctx, log.NewNOPFactory(), option.ClashAPIOptions{Secret: tc.secret})
			if err != nil {
				t.Fatal(err)
			}
			server := instance.(*Server)
			request := httptest.NewRequest(http.MethodPost, "/noki/shutdown", nil)
			request.RemoteAddr = tc.remote
			request.Header.Set("X-Forwarded-For", "127.0.0.1")
			if tc.token != "" {
				request.Header.Set("Authorization", "Bearer "+tc.token)
			}
			server.httpServer.Handler.ServeHTTP(response, request)
			if response.Code != tc.status {
				t.Fatalf("status=%d, want %d: %s", response.Code, tc.status, response.Body.String())
			}
			if called != (tc.status == 204) {
				t.Fatalf("unexpected callback: %v", called)
			}
		})
	}
}
