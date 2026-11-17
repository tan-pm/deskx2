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

func TestDefaultConfigIncludesPomodoroAndEnglishDefault(t *testing.T) {
	cfg := defaultConfig()
	if cfg.Language != "en" {
		t.Fatalf("expected default language 'en', got %q", cfg.Language)
	}
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

func TestDashboardHTMLRendersResponsiveAndI18n(t *testing.T) {
	cfg := defaultConfig()
	html := renderDashboardHTML("192.168.1.50", 9876, cfg)
	if !strings.Contains(html, "DeskDeck PC") || !strings.Contains(html, cfg.Token) {
		t.Fatalf("dashboard HTML missing expected content")
	}
	if !strings.Contains(html, `id="lang-select"`) || !strings.Contains(html, `openStopModal()`) {
		t.Fatalf("dashboard HTML missing language switcher or stop control")
	}
	if strings.Contains(html, "__LANG__") || strings.Contains(html, "__CFG_JSON__") {
		t.Fatalf("dashboard HTML contains unreplaced template placeholders")
	}
}

func TestI18nDictionaryParityAndFallback(t *testing.T) {
	enDict := backendI18N["en"]
	viDict := backendI18N["vi"]
	if len(enDict) == 0 {
		t.Fatalf("expected non-empty English dictionary")
	}
	for k := range enDict {
		if _, ok := viDict[k]; !ok {
			t.Fatalf("missing Vietnamese translation for key %q", k)
		}
	}
	if normalizeLanguage("unknown") != "en" {
		t.Fatalf("expected unknown language to normalize to 'en'")
	}
	if normalizeLanguage("vi-VN") != "vi" {
		t.Fatalf("expected vi-VN to normalize to 'vi'")
	}
	if got := tr("en", "burn_stable"); got != "Stable" {
		t.Fatalf("expected 'Stable', got %q", got)
	}
	if got := tr("vi", "burn_stable"); got != "Ổn định" {
		t.Fatalf("expected 'Ổn định', got %q", got)
	}
}
