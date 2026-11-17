package main

import (
	"strings"
	"testing"
)

func TestBuildPairURIAndQR(t *testing.T) {
	cfg := defaultConfig()
	uri := buildPairURI("192.168.1.50", 9876, cfg)
	if !strings.HasPrefix(uri, "deskdeck://pair?host=192.168.1.50&port=9876&token=") {
		t.Fatalf("unexpected pair URI: %s", uri)
	}
	svg := encodeQRSVG(uri, 210)
	if !strings.Contains(svg, "<svg") || !strings.Contains(svg, "<rect") {
		t.Fatalf("invalid QR SVG output")
	}
	term := renderQRTerminal(uri)
	if len(strings.Split(term, "\n")) < 10 {
		t.Fatalf("terminal QR output too short")
	}
}

func TestDefaultConfigIncludesPomodoro(t *testing.T) {
	cfg := defaultConfig()
	if len(cfg.Layout.Pages) < 2 {
		t.Fatalf("expected multiple pages, got %d", len(cfg.Layout.Pages))
	}
	foundPomodoro := false
	for _, w := range cfg.Layout.Pages[0].Widgets {
		if w.Type == "POMODORO_TIMER" {
			foundPomodoro = true
			break
		}
	}
	if !foundPomodoro {
		t.Fatalf("expected POMODORO_TIMER in first page")
	}
}

func TestDashboardHTMLRenders(t *testing.T) {
	cfg := defaultConfig()
	html := renderDashboardHTML("192.168.1.50", 9876, cfg)
	if !strings.Contains(html, "DeskDeck PC") || !strings.Contains(html, cfg.Token) {
		t.Fatalf("dashboard HTML missing expected content")
	}
}
