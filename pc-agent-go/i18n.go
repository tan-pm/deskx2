package main

import (
	"fmt"
	"strings"
)

const DefaultLanguage = "en"

var backendI18N = map[string]map[string]string{
	"en": {
		"burn_stable":            "Stable",
		"media_idle":             "No media playing",
		"media_source":           "Desktop Audio",
		"notif_welcome_title":    "Native PC Host running light",
		"notif_welcome_body":     "Ready for 1-tap shortcuts & telemetry on %s",
		"notif_test_title":       "Build completed successfully",
		"notif_test_body":        "Test signal sent from PC Web Dashboard.",
		"ai_test_reason":         "Inspect changes before committing",
		"ack_executed":           "Executed",
		"ack_layout_saved":       "Layout saved to PC",
		"ack_terminal_opened":    "Opened Terminal",
		"ack_terminal_err":       "Failed to open Terminal: %v",
		"ack_vscode_opened":      "Opened VS Code",
		"ack_vscode_missing":     "VS Code command (code) not found",
		"ack_browser_opened":     "Opened Browser",
		"ack_browser_err":        "Failed to open Browser: %v",
		"ack_files_opened":       "Opened File Manager",
		"ack_files_err":          "Failed to open File Manager: %v",
		"ack_docker_launched":    "Launched Docker",
		"ack_app_opened":         "Opened: %s",
		"ack_app_err":            "Failed to launch app: %v",
		"ack_screen_locked":      "PC screen locked",
		"ack_screenshot":         "Screenshot triggered",
		"ack_macro_ran":          "Macro executed: %s",
		"ack_volume_set":         "Volume: %d%%",
		"ack_muted":              "PC audio muted",
		"ack_unmuted":            "PC audio unmuted",
		"ack_mic_muted":          "Microphone muted",
		"ack_mic_unmuted":        "Microphone unmuted",
		"ack_media_toggle":       "Media Play / Pause",
		"ack_media_next":         "Next Track",
		"ack_media_prev":         "Previous Track",
		"ack_docker_action":      "Docker %s: %s",
		"ack_docker_err":         "Docker error: %v",
		"ack_ai_allowed":         "AI command approved",
		"ack_ai_denied":          "AI command denied",
		"ack_notif_dismissed":    "Notification dismissed",
		"adb_success":            "USB ADB Reverse enabled successfully!",
		"adb_error":              "ADB error: %s",
		"adb_missing":            "adb command not found in PATH",
		"shutdown_ok":            "DeskDeck PC Agent is shutting down gracefully...",
		"err_unauthorized":       "Unauthorized",
		"err_not_found":          "Not found",
		"err_method_not_allowed": "Method not allowed",
		"err_port_busy":          "[ERROR] Cannot bind port %d: %v",
		"err_port_hint":          "Try another port, e.g.: deskdeck-agent.exe --port 9880",
		"msg_already_running":    "DeskDeck PC Agent is already running (PID %d). Opening Dashboard...",
		"msg_started_bg":         "DeskDeck PC Agent started in background (PID %d). Dashboard: %s",
		"msg_bg_timeout":         "DeskDeck PC Agent launched in background (PID %d), check ~/.deskdeck/agent.log if unreachable.",
		"msg_stopped":            "Stopped background DeskDeck PC Agent (PID %d).",
		"msg_not_running":        "No running background DeskDeck PC Agent found.",
		"banner_title":           "DeskDeck PC Host Agent (Native Go | RAM ~%.1f MB)",
		"banner_dashboard":       "Dashboard & QR:   %s (or http://localhost:%d)",
		"banner_wifi":            "Wi-Fi LAN / Port: %s:%d",
		"banner_token":           "Auth Token:       %s",
		"banner_ws":              "WebSocket URL:    ws://%s:%d/ws?token=%s",
		"banner_qr_hint":         "Scan this QR code with DeskDeck Android app (or open the Web Dashboard):",
		"banner_stop_hint":       "[Press Ctrl+C or use Stop Agent in Web Dashboard / --stop to exit cleanly]",
		"banner_stop":            "Agent stopped cleanly.",
	},
	"vi": {
		"burn_stable":            "Ổn định",
		"media_idle":             "Chưa có media đang phát",
		"media_source":           "Desktop Audio",
		"notif_welcome_title":    "PC Host Native đang chạy siêu nhẹ",
		"notif_welcome_body":     "Sẵn sàng nhận chạm & shortcut từ điện thoại trên %s",
		"notif_test_title":       "Build hoàn tất thành công",
		"notif_test_body":        "Tín hiệu thử nghiệm gửi từ PC Web Dashboard.",
		"ai_test_reason":         "Kiểm tra thay đổi trước khi commit",
		"ack_executed":           "Đã thực thi",
		"ack_layout_saved":       "Đã lưu bố cục lên PC",
		"ack_terminal_opened":    "Đã mở Terminal",
		"ack_terminal_err":       "Lỗi mở Terminal: %v",
		"ack_vscode_opened":      "Đã mở VS Code",
		"ack_vscode_missing":     "Không tìm thấy lệnh code (VS Code)",
		"ack_browser_opened":     "Đã mở Trình duyệt",
		"ack_browser_err":        "Lỗi mở trình duyệt: %v",
		"ack_files_opened":       "Đã mở Trình quản lý tệp",
		"ack_files_err":          "Lỗi mở trình quản lý tệp: %v",
		"ack_docker_launched":    "Đã kích hoạt Docker",
		"ack_app_opened":         "Đã mở: %s",
		"ack_app_err":            "Lỗi mở app: %v",
		"ack_screen_locked":      "Đã khóa màn hình PC",
		"ack_screenshot":         "Đã chụp màn hình",
		"ack_macro_ran":          "Đã chạy macro: %s",
		"ack_volume_set":         "Âm lượng: %d%%",
		"ack_muted":              "Đã tắt tiếng PC",
		"ack_unmuted":            "Đã bật tiếng PC",
		"ack_mic_muted":          "Đã tắt Micro",
		"ack_mic_unmuted":        "Đã bật Micro",
		"ack_media_toggle":       "Phát / Tạm dừng Media",
		"ack_media_next":         "Bài tiếp theo",
		"ack_media_prev":         "Bài trước",
		"ack_docker_action":      "Docker %s: %s",
		"ack_docker_err":         "Lỗi Docker: %v",
		"ack_ai_allowed":         "Đã duyệt lệnh AI",
		"ack_ai_denied":          "Đã từ chối lệnh AI",
		"ack_notif_dismissed":    "Đã xóa thông báo",
		"adb_success":            "Đã bật USB ADB Reverse thành công!",
		"adb_error":              "Lỗi ADB: %s",
		"adb_missing":            "Không tìm thấy lệnh adb trong PATH",
		"shutdown_ok":            "DeskDeck PC Agent đang dừng tiến trình nền sạch sẽ...",
		"err_unauthorized":       "Không có quyền truy cập",
		"err_not_found":          "Không tìm thấy tài nguyên",
		"err_method_not_allowed": "Phương thức HTTP không được hỗ trợ",
		"err_port_busy":          "[LỖI] Không thể mở cổng %d: %v",
		"err_port_hint":          "Hãy thử chạy với cổng khác, ví dụ: deskdeck-agent.exe --port 9880",
		"msg_already_running":    "DeskDeck PC Agent đang chạy sẵn (PID %d). Đang mở Dashboard...",
		"msg_started_bg":         "DeskDeck PC Agent đã khởi chạy nền độc lập (PID %d). Dashboard: %s",
		"msg_bg_timeout":         "DeskDeck PC Agent đã khởi chạy nền (PID %d), kiểm tra ~/.deskdeck/agent.log nếu chưa mở được Dashboard.",
		"msg_stopped":            "Đã dừng DeskDeck PC Agent chạy nền (PID %d).",
		"msg_not_running":        "Không tìm thấy DeskDeck PC Agent nào đang chạy nền.",
		"banner_title":           "DeskDeck PC Host Agent (Native Go | RAM ~%.1f MB)",
		"banner_dashboard":       "Dashboard & Mã QR: %s (hoặc http://localhost:%d)",
		"banner_wifi":            "Wi-Fi LAN / Cổng:  %s:%d",
		"banner_token":           "Token xác thực:    %s",
		"banner_ws":              "WebSocket URL:     ws://%s:%d/ws?token=%s",
		"banner_qr_hint":         "Quét mã QR bên dưới bằng app DeskDeck (hoặc dùng trình duyệt Dashboard):",
		"banner_stop_hint":       "[Nhấn Ctrl+C hoặc dùng nút Dừng Agent trên Dashboard / --stop để tắt sạch sẽ]",
		"banner_stop":            "Đã dừng DeskDeck PC Agent sạch sẽ.",
	},
}

func normalizeLanguage(lang string) string {
	switch strings.ToLower(strings.TrimSpace(lang)) {
	case "vi", "vi-vn":
		return "vi"
	default:
		return DefaultLanguage
	}
}

func tr(lang, key string, args ...any) string {
	norm := normalizeLanguage(lang)
	dict, ok := backendI18N[norm]
	if !ok {
		dict = backendI18N[DefaultLanguage]
	}
	tpl, ok := dict[key]
	if !ok {
		tpl = backendI18N[DefaultLanguage][key]
		if tpl == "" {
			tpl = key
		}
	}
	if len(args) > 0 {
		return fmt.Sprintf(tpl, args...)
	}
	return tpl
}
