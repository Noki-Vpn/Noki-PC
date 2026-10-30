package log

import (
	"context"
	"io"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestPCCoreLogKeepsRecentErrorsWithinTenMiB(t *testing.T) {
	path := filepath.Join(t.TempDir(), "core.log")
	factory := NewDefaultFactory(context.Background(), Formatter{DisableColors: true}, io.Discard, path, nil, false)
	if err := factory.Start(); err != nil {
		t.Fatal(err)
	}
	payload := strings.Repeat("x", 1<<20)
	for i := 0; i < 14; i++ {
		factory.Logger().Info(payload)
	}
	factory.Logger().Error("last-error-marker")
	immediate, readErr := os.ReadFile(path)
	if readErr != nil || !strings.Contains(string(immediate), "last-error-marker") {
		t.Fatal("error left only in memory until close")
	}
	if err := factory.Close(); err != nil {
		t.Fatal(err)
	}
	var total int64
	for _, name := range []string{path, path + ".1"} {
		if info, err := os.Stat(name); err == nil {
			total += info.Size()
		} else if !os.IsNotExist(err) {
			t.Fatal(err)
		}
	}
	if total > 10<<20 {
		t.Fatalf("local core logs grew to %d bytes; limit is 10 MiB", total)
	}
	current, err := os.ReadFile(path)
	if err != nil || !strings.Contains(string(current), "last-error-marker") {
		t.Fatalf("latest error lost: %v", err)
	}
	// Reopening after a core restart must append, not erase the previous failed attempt.
	next := NewDefaultFactory(context.Background(), Formatter{DisableColors: true}, io.Discard, path, nil, false)
	if err = next.Start(); err != nil {
		t.Fatal(err)
	}
	next.Logger().Error("next-attempt-marker")
	if err = next.Close(); err != nil {
		t.Fatal(err)
	}
	current, err = os.ReadFile(path)
	if err != nil || !strings.Contains(string(current), "last-error-marker") || !strings.Contains(string(current), "next-attempt-marker") {
		t.Fatal("restart erased diagnostic history")
	}
}

func TestPCCoreLogContinuesWhenWindowsReaderLocksBackup(t *testing.T) {
	path := filepath.Join(t.TempDir(), "core.log")
	if err := os.WriteFile(path+".1", []byte("older log"), 0o600); err != nil {
		t.Fatal(err)
	}
	reader, err := os.Open(path + ".1")
	if err != nil {
		t.Fatal(err)
	}
	defer reader.Close()
	writer, err := newLocalLogFile(context.Background(), path)
	if err != nil {
		t.Fatal(err)
	}
	defer writer.Close()
	if _, err = writer.Write([]byte(strings.Repeat("x", localLogSegmentSize))); err != nil {
		t.Fatal(err)
	}
	if _, err = writer.Write([]byte("rotation-error-marker")); err != nil {
		t.Fatal(err)
	}
	if err = writer.Flush(); err != nil {
		t.Fatal(err)
	}
	current, err := os.ReadFile(path)
	if err != nil || !strings.Contains(string(current), "rotation-error-marker") {
		t.Fatal("locked backup stopped local logging")
	}
}
