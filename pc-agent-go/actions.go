package main

import (
	"fmt"
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
			return fmt.Sprintf("Lỗi mở Terminal: %v", err)
		}
		return "Đã mở Terminal"
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
				return "Đã mở VS Code"
			}
		}
		return "Không tìm thấy lệnh code (VS Code)"
	case "browser":
		if err := openBrowserPlatform("https://github.com"); err != nil {
			return fmt.Sprintf("Lỗi mở trình duyệt: %v", err)
		}
		return "Đã mở Trình duyệt"
	case "explorer":
		p := cfg.RepoPath
		if p == "" {
			if home, err := os.UserHomeDir(); err == nil {
				p = home
			}
		}
		if err := launchExplorerPlatform(p); err != nil {
			return fmt.Sprintf("Lỗi mở Explorer: %v", err)
		}
		return "Đã mở Trình quản lý tệp"
	case "docker_desktop":
		_ = launchDockerDesktopPlatform()
		return "Đã kích hoạt Docker"
	default:
		if err := runShellCommandPlatform(cmd); err != nil {
			return fmt.Sprintf("Lỗi mở app: %v", err)
		}
		return "Đã mở: " + cmd
	}
}

func executeEventAction(cfg HostConfig, tc *TelemetryCollector, ev IncomingEvent) AckMessage {
	msg := "Đã thực thi"

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
			msg = "Đã khóa màn hình PC"
		case "screenshot":
			sendMediaKeyPlatform(0x2C)
			msg = "Đã chụp màn hình"
		default:
			_ = runShellCommandPlatform(macroCmd)
			msg = "Đã chạy macro: " + ev.Value
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
			applySystemVolumePlatform(vol)
			msg = fmt.Sprintf("Âm lượng: %d%%", vol)
		}

	case "TOGGLE_MUTE":
		tc.mu.Lock()
		tc.isMuted = !tc.isMuted
		muted := tc.isMuted
		tc.mu.Unlock()
		applySystemMutePlatform(muted)
		if muted {
			msg = "Đã tắt tiếng PC"
		} else {
			msg = "Đã bật tiếng PC"
		}

	case "TOGGLE_MIC_MUTE":
		tc.mu.Lock()
		tc.micMuted = !tc.micMuted
		micMuted := tc.micMuted
		tc.mu.Unlock()
		if micMuted {
			msg = "Đã tắt Micro"
		} else {
			msg = "Đã bật Micro"
		}

	case "MEDIA_CMD":
		switch ev.Value {
		case "play_pause":
			tc.mu.Lock()
			tc.mediaPlaying = !tc.mediaPlaying
			tc.mu.Unlock()
			sendMediaKeyPlatform(0xB3)
			msg = "Phát / Tạm dừng Media"
		case "next":
			sendMediaKeyPlatform(0xB0)
			msg = "Bài tiếp theo"
		case "prev":
			sendMediaKeyPlatform(0xB1)
			msg = "Bài trước"
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
				msg = fmt.Sprintf("Docker error: %v", err)
			} else {
				msg = fmt.Sprintf("Docker %s: %s", dockerCmd, name)
			}
		}

	case "AI_DECISION":
		tc.mu.Lock()
		tc.aiPendingPrompt = nil
		tc.mu.Unlock()
		if ev.Value == "allow" {
			msg = "Đã duyệt lệnh AI"
		} else {
			msg = "Đã từ chối lệnh AI"
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
		msg = "Đã xóa thông báo"
	}

	return AckMessage{
		Type:     "ack",
		ActionID: ev.ActionID,
		WidgetID: ev.WidgetID,
		Ok:       true,
		Message:  msg,
	}
}
