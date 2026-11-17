package main

import (
	"context"
	"os"
	"os/exec"
	"strconv"
	"strings"
)

type IncomingEvent struct {
	Type     string            `json:"type"`
	ActionID string            `json:"actionId"`
	WidgetID string            `json:"widgetId"`
	Value    string            `json:"value"`
	TS       int64             `json:"ts"`
	Layout   *DeckLayoutConfig `json:"layout,omitempty"`
}

type AckMessage struct {
	Type     string `json:"type"`
	ActionID string `json:"actionId,omitempty"`
	WidgetID string `json:"widgetId,omitempty"`
	Ok       bool   `json:"ok"`
	Message  string `json:"message"`
}

func launchAppAction(cfg HostConfig, appKey string) string {
	lang := normalizeLanguage(cfg.Language)
	cmd := appKey
	for _, a := range cfg.Apps {
		if a.ID == appKey || a.Command == appKey {
			cmd = a.Command
			break
		}
	}

	switch cmd {
	case "terminal":
		if err := launchTerminalPlatform(); err != nil {
			return tr(lang, "ack_terminal_err", err)
		}
		return tr(lang, "ack_terminal_opened")
	case "vscode":
		repo := cfg.RepoPath
		if repo == "" {
			if cwd, err := os.Getwd(); err == nil {
				repo = cwd
			}
		}
		if _, err := exec.LookPath("code"); err == nil {
			c := exec.Command("code", repo)
			hideSubprocessWindow(c)
			if err := c.Start(); err == nil {
				return tr(lang, "ack_vscode_opened")
			}
		}
		return tr(lang, "ack_vscode_missing")
	case "browser":
		if err := openBrowserPlatform("https://github.com"); err != nil {
			return tr(lang, "ack_browser_err", err)
		}
		return tr(lang, "ack_browser_opened")
	case "explorer":
		p := cfg.RepoPath
		if p == "" {
			if home, err := os.UserHomeDir(); err == nil {
				p = home
			}
		}
		if err := launchExplorerPlatform(p); err != nil {
			return tr(lang, "ack_files_err", err)
		}
		return tr(lang, "ack_files_opened")
	case "docker_desktop":
		_ = launchDockerDesktopPlatform()
		return tr(lang, "ack_docker_launched")
	default:
		if err := runShellCommandPlatform(cmd); err != nil {
			return tr(lang, "ack_app_err", err)
		}
		return tr(lang, "ack_app_opened", cmd)
	}
}

func executeEventAction(ctx context.Context, cfg HostConfig, tc *TelemetryCollector, ev IncomingEvent) AckMessage {
	lang := normalizeLanguage(cfg.Language)
	msg := tr(lang, "ack_executed")

	switch ev.Type {
	case "LAUNCH_APP":
		msg = launchAppAction(cfg, ev.Value)

	case "TRIGGER_MACRO":
		macroCmd := ev.Value
		for _, m := range cfg.Macros {
			if m.ID == ev.Value {
				macroCmd = m.Command
				break
			}
		}
		switch macroCmd {
		case "lock_screen":
			lockScreenPlatform()
			msg = tr(lang, "ack_screen_locked")
		case "screenshot":
			sendMediaKeyPlatform(0x2C)
			msg = tr(lang, "ack_screenshot")
		default:
			_ = runShellCommandPlatform(macroCmd)
			msg = tr(lang, "ack_macro_ran", ev.Value)
		}

	case "SET_VOLUME":
		if v, err := strconv.ParseFloat(ev.Value, 64); err == nil {
			vol := int(v + 0.5)
			if vol < 0 {
				vol = 0
			}
			if vol > 100 {
				vol = 100
			}
			tc.mu.Lock()
			tc.masterVolume = vol
			tc.mu.Unlock()
			applySystemVolumePlatform(ctx, vol)
			msg = tr(lang, "ack_volume_set", vol)
		}

	case "TOGGLE_MUTE":
		tc.mu.Lock()
		tc.isMuted = !tc.isMuted
		muted := tc.isMuted
		tc.mu.Unlock()
		applySystemMutePlatform(muted)
		if muted {
			msg = tr(lang, "ack_muted")
		} else {
			msg = tr(lang, "ack_unmuted")
		}

	case "TOGGLE_MIC_MUTE":
		tc.mu.Lock()
		tc.micMuted = !tc.micMuted
		micMuted := tc.micMuted
		tc.mu.Unlock()
		if micMuted {
			msg = tr(lang, "ack_mic_muted")
		} else {
			msg = tr(lang, "ack_mic_unmuted")
		}

	case "MEDIA_CMD":
		switch ev.Value {
		case "play_pause":
			tc.mu.Lock()
			tc.mediaPlaying = !tc.mediaPlaying
			tc.mu.Unlock()
			sendMediaKeyPlatform(0xB3)
			msg = tr(lang, "ack_media_toggle")
		case "next":
			sendMediaKeyPlatform(0xB0)
			msg = tr(lang, "ack_media_next")
		case "prev":
			sendMediaKeyPlatform(0xB1)
			msg = tr(lang, "ack_media_prev")
		}

	case "DOCKER_TOGGLE":
		parts := strings.SplitN(ev.Value, ":", 2)
		if len(parts) == 2 {
			name, action := parts[0], parts[1]
			dockerCmd := "stop"
			if action == "start" {
				dockerCmd = "start"
			}
			c := exec.Command("docker", dockerCmd, name)
			hideSubprocessWindow(c)
			if err := c.Start(); err != nil {
				msg = tr(lang, "ack_docker_err", err)
			} else {
				msg = tr(lang, "ack_docker_action", dockerCmd, name)
			}
		}

	case "AI_DECISION":
		tc.mu.Lock()
		tc.aiPendingPrompt = nil
		tc.mu.Unlock()
		if ev.Value == "allow" {
			msg = tr(lang, "ack_ai_allowed")
		} else {
			msg = tr(lang, "ack_ai_denied")
		}

	case "DISMISS_NOTIF":
		tc.mu.Lock()
		filtered := tc.notifications[:0]
		for _, n := range tc.notifications {
			if n.ID != ev.Value {
				filtered = append(filtered, n)
			}
		}
		tc.notifications = filtered
		tc.mu.Unlock()
		msg = tr(lang, "ack_notif_dismissed")
	}

	return AckMessage{
		Type:     "ack",
		ActionID: ev.ActionID,
		WidgetID: ev.WidgetID,
		Ok:       true,
		Message:  msg,
	}
}
