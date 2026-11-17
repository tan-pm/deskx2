package main

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"os"
	"path/filepath"
)

type AppShortcut struct {
	ID      string `json:"id"`
	Label   string `json:"label"`
	Command string `json:"command"`
	Icon    string `json:"icon"`
	Color   string `json:"color"`
}

type MacroItem struct {
	ID      string `json:"id"`
	Label   string `json:"label"`
	Hotkey  string `json:"hotkey"`
	Command string `json:"command"`
}

type DeckWidgetSpec struct {
	ID             string `json:"id"`
	Type           string `json:"type"`
	Title          string `json:"title"`
	ColSpan        int    `json:"colSpan"`
	AccentColorKey string `json:"accentColorKey"`
}

type DeckPage struct {
	ID      string           `json:"id"`
	Title   string           `json:"title"`
	Widgets []DeckWidgetSpec `json:"widgets"`
}

type DeckLayoutConfig struct {
	Version    int        `json:"version"`
	PresetName string     `json:"presetName"`
	Pages      []DeckPage `json:"pages"`
}

type HostConfig struct {
	Language string           `json:"language"`
	Token    string           `json:"token"`
	PCName   string           `json:"pcName"`
	RepoPath string           `json:"repoPath"`
	Apps     []AppShortcut    `json:"apps"`
	Macros   []MacroItem      `json:"macros"`
	Layout   DeckLayoutConfig `json:"layout"`
}

func configDir() string {
	home, err := os.UserHomeDir()
	if err != nil {
		home = "."
	}
	return filepath.Join(home, ".deskdeck")
}

func configPath() string {
	return filepath.Join(configDir(), "config.json")
}

func pidFilePath() string {
	return filepath.Join(configDir(), "agent.pid")
}

func logFilePath() string {
	return filepath.Join(configDir(), "agent.log")
}

func randomHexToken(nBytes int) string {
	b := make([]byte, nBytes)
	if _, err := rand.Read(b); err != nil {
		return "deskdeck1234"
	}
	return hex.EncodeToString(b)
}

func defaultConfig() HostConfig {
	hostname, err := os.Hostname()
	if err != nil || hostname == "" {
		hostname = "DeskDeck-PC"
	}
	cwd, err := os.Getwd()
	if err != nil {
		cwd = "."
	}
	return HostConfig{
		Language: DefaultLanguage,
		Token:    randomHexToken(6),
		PCName:   hostname,
		RepoPath: cwd,
		Apps: []AppShortcut{
			{ID: "app_terminal", Label: "Terminal", Command: "terminal", Icon: "terminal", Color: "cyan"},
			{ID: "app_vscode", Label: "VS Code", Command: "vscode", Icon: "code", Color: "violet"},
			{ID: "app_browser", Label: "Browser", Command: "browser", Icon: "public", Color: "emerald"},
			{ID: "app_explorer", Label: "Files", Command: "explorer", Icon: "folder", Color: "amber"},
			{ID: "app_docker", Label: "Docker", Command: "docker_desktop", Icon: "dns", Color: "cyan"},
		},
		Macros: []MacroItem{
			{ID: "macro_lock", Label: "Lock Screen", Hotkey: "Win+L / Ctrl+Cmd+Q", Command: "lock_screen"},
			{ID: "macro_shot", Label: "Screenshot", Hotkey: "PrtSc", Command: "screenshot"},
			{ID: "macro_dev", Label: "Run Dev Server", Hotkey: "npm run dev", Command: "echo DeskDeck Dev Triggered"},
			{ID: "macro_clean", Label: "Clear Terminal", Hotkey: "Ctrl+L", Command: "echo Clear"},
		},
		Layout: DeckLayoutConfig{
			Version:    1,
			PresetName: "Standard Stream Deck",
			Pages: []DeckPage{
				{
					ID:    "page_overview",
					Title: "Overview & Focus",
					Widgets: []DeckWidgetSpec{
						{ID: "w_clock", Type: "CLOCK_WEATHER", Title: "Clock & System", ColSpan: 2, AccentColorKey: "cyan"},
						{ID: "w_pomo", Type: "POMODORO_TIMER", Title: "Pomodoro Focus", ColSpan: 2, AccentColorKey: "red"},
						{ID: "w_cpu", Type: "CPU_LOAD", Title: "CPU", ColSpan: 1, AccentColorKey: "cyan"},
						{ID: "w_ram", Type: "RAM_USAGE", Title: "RAM", ColSpan: 1, AccentColorKey: "violet"},
						{ID: "w_media", Type: "MEDIA_PLAYER", Title: "Media Player", ColSpan: 2, AccentColorKey: "cyan"},
						{ID: "w_vol", Type: "VOLUME_MIXER", Title: "PC Volume", ColSpan: 2, AccentColorKey: "amber"},
						{ID: "w_net", Type: "NETWORK_SPEED", Title: "Network & Disk", ColSpan: 2, AccentColorKey: "emerald"},
					},
				},
				{
					ID:    "page_shortcuts",
					Title: "App & Macro Deck",
					Widgets: []DeckWidgetSpec{
						{ID: "w_apps", Type: "APP_LAUNCHER", Title: "App Launcher", ColSpan: 2, AccentColorKey: "cyan"},
						{ID: "w_macros", Type: "MACRO_BUTTON", Title: "Macro Pad", ColSpan: 2, AccentColorKey: "violet"},
						{ID: "w_gpu", Type: "GPU_STATS", Title: "GPU & Temp", ColSpan: 2, AccentColorKey: "amber"},
					},
				},
				{
					ID:    "page_dev",
					Title: "Dev & Alerts",
					Widgets: []DeckWidgetSpec{
						{ID: "w_ai", Type: "AI_QUOTA_PROMPT", Title: "AI Coding & Approvals", ColSpan: 2, AccentColorKey: "violet"},
						{ID: "w_dev", Type: "DEV_DOCKER_GIT", Title: "Docker & Git Status", ColSpan: 2, AccentColorKey: "emerald"},
						{ID: "w_notif", Type: "PC_NOTIFICATIONS", Title: "PC Notifications", ColSpan: 4, AccentColorKey: "amber"},
					},
				},
			},
		},
	}
}

func loadOrCreateConfig() HostConfig {
	p := configPath()
	raw, err := os.ReadFile(p)
	if err == nil {
		var cfg HostConfig
		if json.Unmarshal(raw, &cfg) == nil && cfg.Token != "" && len(cfg.Layout.Pages) > 0 {
			cfg.Language = normalizeLanguage(cfg.Language)
			if cfg.PCName == "" {
				if h, err := os.Hostname(); err == nil {
					cfg.PCName = h
				}
			}
			return cfg
		}
	}
	cfg := defaultConfig()
	_ = saveConfig(cfg)
	return cfg
}

func saveConfig(cfg HostConfig) error {
	cfg.Language = normalizeLanguage(cfg.Language)
	dir := configDir()
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return err
	}
	data, err := json.MarshalIndent(cfg, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(configPath(), data, 0o644)
}
