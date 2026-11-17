#!/usr/bin/env python3
"""
DeskDeck PC Host Agent & Local Web Dashboard
Ultra-lightweight (~15-25 MB RAM) single-file Python 3 daemon.
Zero mandatory third-party dependencies (pure Python 3 standard library).
Supports:
- Responsive multi-aspect Web Dashboard (16:9, 16:10, 4:3, 21:9, high-DPI)
- English (default) & Vietnamese i18n
- Detached background daemon (--background / pythonw.exe / --stop) & clean shutdown
- 1-tap Wi-Fi QR pairing, UDP LAN auto-discovery, and USB ADB reverse.
"""

import argparse
import asyncio
import base64
import ctypes
import hashlib
import html as html_lib
import json
import os
import platform
import secrets
import shutil
import signal
import socket
import struct
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import webbrowser
from pathlib import Path

DEFAULT_PORT = 9876
DISCOVERY_PORT = 9877
DEFAULT_LANGUAGE = "en"

CONFIG_DIR = Path.home() / ".deskdeck"
CONFIG_FILE = CONFIG_DIR / "config.json"
PID_FILE = CONFIG_DIR / "agent.pid"
LOG_FILE = CONFIG_DIR / "agent.log"

DETACHED_PROCESS = 0x00000008
CREATE_NEW_PROCESS_GROUP = 0x00000200
CREATE_NO_WINDOW = 0x08000000


def setup_consoleless_stdio():
    """Prevent crashes when running under pythonw.exe or PyInstaller --noconsole."""
    CONFIG_DIR.mkdir(parents=True, exist_ok=True)
    if LOG_FILE.exists():
        try:
            if LOG_FILE.stat().st_size > 2 * 1024 * 1024:
                LOG_FILE.write_text("", encoding="utf-8")
        except Exception:
            pass
    if sys.stdout is None or sys.stderr is None:
        try:
            log_stream = open(LOG_FILE, "a", encoding="utf-8", buffering=1)
            if sys.stdout is None:
                sys.stdout = log_stream
            if sys.stderr is None:
                sys.stderr = log_stream
        except Exception:
            pass


def safe_log(message: str, to_console: bool = True):
    CONFIG_DIR.mkdir(parents=True, exist_ok=True)
    ts = time.strftime("%H:%M:%S")
    try:
        with open(LOG_FILE, "a", encoding="utf-8") as f:
            f.write(f"[{ts}] {message}\n")
    except Exception:
        pass
    if to_console and sys.stdout is not None:
        try:
            print(message, flush=True)
        except Exception:
            pass


def hidden_subprocess_kwargs() -> dict:
    if platform.system() == "Windows":
        return {"creationflags": CREATE_NO_WINDOW}
    return {}


# ==============================================================================
# 0. Internationalization (i18n) — English Default & Vietnamese
# ==============================================================================
BACKEND_I18N = {
    "en": {
        "burn_stable": "Stable",
        "media_idle": "No media playing",
        "media_source": "Desktop Audio",
        "notif_welcome_title": "PC Host running light",
        "notif_welcome_body": "Ready for 1-tap shortcuts & telemetry on {0}",
        "notif_test_title": "Build completed successfully",
        "notif_test_body": "Test signal sent from PC Web Dashboard.",
        "ai_test_reason": "Inspect changes before committing",
        "ack_executed": "Executed",
        "ack_layout_saved": "Layout saved to PC",
        "ack_terminal_opened": "Opened Terminal",
        "ack_vscode_opened": "Opened VS Code",
        "ack_vscode_missing": "VS Code command (code) not found",
        "ack_browser_opened": "Opened Browser",
        "ack_files_opened": "Opened File Manager",
        "ack_docker_launched": "Launched Docker",
        "ack_app_opened": "Opened: {0}",
        "ack_app_err": "Failed to launch app: {0}",
        "ack_screen_locked": "PC screen locked",
        "ack_screenshot": "Screenshot triggered",
        "ack_macro_ran": "Macro executed: {0}",
        "ack_volume_set": "Volume: {0}%",
        "ack_muted": "PC audio muted",
        "ack_unmuted": "PC audio unmuted",
        "ack_mic_muted": "Microphone muted",
        "ack_mic_unmuted": "Microphone unmuted",
        "ack_media_toggle": "Media Play / Pause",
        "ack_media_next": "Next Track",
        "ack_media_prev": "Previous Track",
        "ack_docker_action": "Docker {0}: {1}",
        "ack_docker_err": "Docker error: {0}",
        "ack_ai_allowed": "AI command approved",
        "ack_ai_denied": "AI command denied",
        "ack_notif_dismissed": "Notification dismissed",
        "adb_success": "USB ADB Reverse enabled successfully!",
        "adb_error": "ADB error: {0}",
        "adb_missing": "adb command not found in PATH",
        "shutdown_ok": "DeskDeck PC Agent is shutting down gracefully...",
        "err_unauthorized": "Unauthorized",
        "err_not_found": "Not found",
        "err_port_busy": "[ERROR] Cannot bind port {0}: {1}",
        "err_port_hint": "Try another port, e.g.: python3 deskdeck_agent.py --port 9880",
        "msg_already_running": "DeskDeck PC Agent is already running (PID {0}). Opening Dashboard...",
        "msg_started_bg": "DeskDeck PC Agent started in background (PID {0}). Dashboard: {1}",
        "msg_bg_timeout": "DeskDeck PC Agent launched in background (PID {0}); check ~/.deskdeck/agent.log if unreachable.",
        "msg_stopped": "Stopped background DeskDeck PC Agent (PID {0}).",
        "msg_not_running": "No running background DeskDeck PC Agent found.",
        "banner_title": "DeskDeck PC Host Agent (Python | RAM ~{0} MB)",
        "banner_dashboard": "Dashboard & QR:   {0} (or http://localhost:{1})",
        "banner_wifi": "Wi-Fi LAN / Port: {0}:{1}",
        "banner_token": "Auth Token:       {0}",
        "banner_ws": "WebSocket URL:    ws://{0}:{1}/ws?token={2}",
        "banner_qr_hint": "Scan this QR code with DeskDeck Android app (or open the Web Dashboard):",
        "banner_stop_hint": "[Press Ctrl+C or use Stop Agent in Web Dashboard / --stop to exit cleanly]",
        "banner_stop": "Agent stopped cleanly.",
    },
    "vi": {
        "burn_stable": "Ổn định",
        "media_idle": "Chưa có media đang phát",
        "media_source": "Desktop Audio",
        "notif_welcome_title": "PC Host đang chạy siêu nhẹ",
        "notif_welcome_body": "Sẵn sàng nhận chạm & shortcut từ điện thoại trên {0}",
        "notif_test_title": "Build hoàn tất thành công",
        "notif_test_body": "Tín hiệu thử nghiệm gửi từ PC Web Dashboard.",
        "ai_test_reason": "Kiểm tra thay đổi trước khi commit",
        "ack_executed": "Đã thực thi",
        "ack_layout_saved": "Đã lưu bố cục lên PC",
        "ack_terminal_opened": "Đã mở Terminal",
        "ack_vscode_opened": "Đã mở VS Code",
        "ack_vscode_missing": "Không tìm thấy lệnh code (VS Code)",
        "ack_browser_opened": "Đã mở Trình duyệt",
        "ack_files_opened": "Đã mở Trình quản lý tệp",
        "ack_docker_launched": "Đã kích hoạt Docker",
        "ack_app_opened": "Đã mở: {0}",
        "ack_app_err": "Lỗi mở app: {0}",
        "ack_screen_locked": "Đã khóa màn hình PC",
        "ack_screenshot": "Đã chụp màn hình",
        "ack_macro_ran": "Đã chạy macro: {0}",
        "ack_volume_set": "Âm lượng: {0}%",
        "ack_muted": "Đã tắt tiếng PC",
        "ack_unmuted": "Đã bật tiếng PC",
        "ack_mic_muted": "Đã tắt Micro",
        "ack_mic_unmuted": "Đã bật Micro",
        "ack_media_toggle": "Phát / Tạm dừng Media",
        "ack_media_next": "Bài tiếp theo",
        "ack_media_prev": "Bài trước",
        "ack_docker_action": "Docker {0}: {1}",
        "ack_docker_err": "Lỗi Docker: {0}",
        "ack_ai_allowed": "Đã duyệt lệnh AI",
        "ack_ai_denied": "Đã từ chối lệnh AI",
        "ack_notif_dismissed": "Đã xóa thông báo",
        "adb_success": "Đã bật USB ADB Reverse thành công!",
        "adb_error": "Lỗi ADB: {0}",
        "adb_missing": "Không tìm thấy lệnh adb trong PATH",
        "shutdown_ok": "DeskDeck PC Agent đang dừng tiến trình nền sạch sẽ...",
        "err_unauthorized": "Không có quyền truy cập",
        "err_not_found": "Không tìm thấy tài nguyên",
        "err_port_busy": "[LỖI] Không thể mở cổng {0}: {1}",
        "err_port_hint": "Hãy thử chạy với cổng khác, ví dụ: python3 deskdeck_agent.py --port 9880",
        "msg_already_running": "DeskDeck PC Agent đang chạy sẵn (PID {0}). Đang mở Dashboard...",
        "msg_started_bg": "DeskDeck PC Agent đã khởi chạy nền độc lập (PID {0}). Dashboard: {1}",
        "msg_bg_timeout": "DeskDeck PC Agent đã khởi chạy nền (PID {0}), kiểm tra ~/.deskdeck/agent.log nếu chưa mở được Dashboard.",
        "msg_stopped": "Đã dừng DeskDeck PC Agent chạy nền (PID {0}).",
        "msg_not_running": "Không tìm thấy DeskDeck PC Agent nào đang chạy nền.",
        "banner_title": "DeskDeck PC Host Agent (Python | RAM ~{0} MB)",
        "banner_dashboard": "Dashboard & Mã QR: {0} (hoặc http://localhost:{1})",
        "banner_wifi": "Wi-Fi LAN / Cổng:  {0}:{1}",
        "banner_token": "Token xác thực:    {0}",
        "banner_ws": "WebSocket URL:     ws://{0}:{1}/ws?token={2}",
        "banner_qr_hint": "Quét mã QR bên dưới bằng app DeskDeck (hoặc dùng trình duyệt Dashboard):",
        "banner_stop_hint": "[Nhấn Ctrl+C hoặc dùng nút Dừng Agent trên Dashboard / --stop để tắt sạch sẽ]",
        "banner_stop": "Đã dừng DeskDeck PC Agent sạch sẽ.",
    },
}


def normalize_language(lang: str | None) -> str:
    val = (lang or "").strip().lower()
    if val in ("vi", "vi-vn"):
        return "vi"
    return DEFAULT_LANGUAGE


def tr(lang: str | None, key: str, *args) -> str:
    norm = normalize_language(lang)
    tpl = BACKEND_I18N.get(norm, BACKEND_I18N[DEFAULT_LANGUAGE]).get(
        key, BACKEND_I18N[DEFAULT_LANGUAGE].get(key, key)
    )
    if args:
        try:
            return tpl.format(*args)
        except Exception:
            return tpl
    return tpl


# ==============================================================================
# 1. Pure-Python QR Code SVG Generator (Version 1-8, Byte mode, ECC Level L)
#    Zero external packages required — renders crisp offline SVG on Dashboard.
# ==============================================================================
class PureQrSvg:
    GF_EXP = [0] * 512
    GF_LOG = [0] * 256

    @classmethod
    def _init_gf(cls):
        if cls.GF_EXP[0] != 0:
            return
        x = 1
        for i in range(255):
            cls.GF_EXP[i] = x
            cls.GF_LOG[x] = i
            x <<= 1
            if x & 0x100:
                x ^= 0x11D
        for i in range(255, 512):
            cls.GF_EXP[i] = cls.GF_EXP[i - 255]

    @classmethod
    def _gf_mul(cls, a, b):
        if a == 0 or b == 0:
            return 0
        return cls.GF_EXP[cls.GF_LOG[a] + cls.GF_LOG[b]]

    @classmethod
    def _rs_gen_poly(cls, degree):
        cls._init_gf()
        poly = [1]
        for i in range(degree):
            nxt = [0] * (len(poly) + 1)
            g = cls.GF_EXP[i]
            for j, c in enumerate(poly):
                nxt[j] ^= c
                nxt[j + 1] ^= cls._gf_mul(c, g)
            poly = nxt
        return poly

    @classmethod
    def _rs_remainder(cls, data, ec_len):
        gen = cls._rs_gen_poly(ec_len)
        res = list(data) + [0] * ec_len
        for i in range(len(data)):
            coef = res[i]
            if coef != 0:
                for j in range(1, len(gen)):
                    res[i + j] ^= cls._gf_mul(gen[j], coef)
        return res[len(data):]

    VERSIONS = [
        (1, 26, 7, 1, 19, 0, 0, []),
        (2, 44, 10, 1, 34, 0, 0, [6, 18]),
        (3, 70, 15, 1, 55, 0, 0, [6, 22]),
        (4, 100, 20, 1, 80, 0, 0, [6, 26]),
        (5, 134, 26, 1, 108, 0, 0, [6, 30]),
        (6, 172, 18, 2, 68, 0, 0, [6, 34]),
        (7, 196, 20, 2, 78, 0, 0, [6, 22, 38]),
        (8, 242, 24, 2, 97, 0, 0, [6, 24, 42]),
    ]

    @classmethod
    def encode_matrix(cls, text: str) -> list[list[int]]:
        raw = text.encode("utf-8")
        chosen = None
        for v in cls.VERSIONS:
            cap_bytes = v[3] * v[4] + v[5] * v[6]
            if len(raw) + 2 <= cap_bytes:
                chosen = v
                break
        if chosen is None:
            chosen = cls.VERSIONS[-1]
            raw = raw[:190]

        ver, total_cw, ec_cw, b1, d1, b2, d2, align = chosen
        data_cap = b1 * d1 + b2 * d2
        size = 17 + 4 * ver

        bits = []

        def push_bits(val, length):
            for i in range(length - 1, -1, -1):
                bits.append((val >> i) & 1)

        push_bits(0b0100, 4)
        push_bits(len(raw), 8)
        for byte in raw:
            push_bits(byte, 8)
        push_bits(0, min(4, data_cap * 8 - len(bits)))
        while len(bits) % 8 != 0:
            bits.append(0)
        pad_bytes = [0xEC, 0x11]
        p_idx = 0
        while len(bits) < data_cap * 8:
            push_bits(pad_bytes[p_idx % 2], 8)
            p_idx += 1

        codewords = [
            sum(bits[i + j] << (7 - j) for j in range(8))
            for i in range(0, len(bits), 8)
        ]

        blocks = []
        ec_blocks = []
        offset = 0
        for count, dlen in [(b1, d1), (b2, d2)]:
            for _ in range(count):
                blk = codewords[offset:offset + dlen]
                offset += dlen
                blocks.append(blk)
                ec_blocks.append(cls._rs_remainder(blk, ec_cw))

        interleaved = []
        max_d = max(len(b) for b in blocks)
        for i in range(max_d):
            for b in blocks:
                if i < len(b):
                    interleaved.append(b[i])
        for i in range(ec_cw):
            for eb in ec_blocks:
                interleaved.append(eb[i])

        final_bits = []
        for cw in interleaved:
            for i in range(7, -1, -1):
                final_bits.append((cw >> i) & 1)
        final_bits.extend([0] * 7)

        grid = [[None] * size for _ in range(size)]
        is_func = [[False] * size for _ in range(size)]

        def set_func(r, c, val):
            if 0 <= r < size and 0 <= c < size:
                grid[r][c] = val
                is_func[r][c] = True

        def draw_finder(r0, c0):
            for dr in range(-1, 8):
                for dc in range(-1, 8):
                    r, c = r0 + dr, c0 + dc
                    if 0 <= r < size and 0 <= c < size:
                        in_box = 0 <= dr <= 6 and 0 <= dc <= 6
                        border = dr in (0, 6) or dc in (0, 6)
                        center = 2 <= dr <= 4 and 2 <= dc <= 4
                        set_func(r, c, 1 if (in_box and (border or center)) else 0)

        draw_finder(0, 0)
        draw_finder(0, size - 7)
        draw_finder(size - 7, 0)

        for i in range(8, size - 8):
            set_func(6, i, 1 if i % 2 == 0 else 0)
            set_func(i, 6, 1 if i % 2 == 0 else 0)

        if align:
            for ar in align:
                for ac in align:
                    if is_func[ar][ac]:
                        continue
                    for dr in range(-2, 3):
                        for dc in range(-2, 3):
                            val = 1 if (abs(dr) == 2 or abs(dc) == 2 or (dr == 0 and dc == 0)) else 0
                            set_func(ar + dr, ac + dc, val)

        set_func(size - 8, 8, 1)

        for i in range(9):
            if not is_func[8][i]:
                is_func[8][i] = True
                grid[8][i] = 0
            if not is_func[i][8]:
                is_func[i][8] = True
                grid[i][8] = 0
        for i in range(size - 8, size):
            is_func[8][i] = True
            grid[8][i] = 0
        for i in range(size - 7, size):
            is_func[i][8] = True
            grid[i][8] = 0

        bit_idx = 0
        col = size - 1
        up = True
        while col > 0:
            if col == 6:
                col -= 1
            rows = range(size - 1, -1, -1) if up else range(size)
            for r in rows:
                for c in (col, col - 1):
                    if not is_func[r][c]:
                        b = final_bits[bit_idx] if bit_idx < len(final_bits) else 0
                        bit_idx += 1
                        if (r + c) % 2 == 0:
                            b ^= 1
                        grid[r][c] = b
            col -= 2
            up = not up

        fmt_bits = 0x77C4
        for i in range(15):
            bit = (fmt_bits >> (14 - i)) & 1
            if i < 6:
                grid[8][i] = bit
            elif i < 8:
                grid[8][i + 1] = bit
            elif i == 8:
                grid[7][8] = bit
            else:
                grid[14 - i][8] = bit

            if i < 7:
                grid[size - 1 - i][8] = bit
            elif i == 7:
                grid[8][size - 8] = bit
            else:
                grid[8][size - 15 + i] = bit

        return [[1 if cell == 1 else 0 for cell in row] for row in grid]

    @classmethod
    def encode_svg(cls, text: str, box_size: int = 220) -> str:
        grid = cls.encode_matrix(text)
        size = len(grid)
        quiet = 3
        full = size + quiet * 2
        rects = []
        for r in range(size):
            for c in range(size):
                if grid[r][c] == 1:
                    rects.append(f'<rect x="{c + quiet}" y="{r + quiet}" width="1" height="1"/>')
        return (
            f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {full} {full}" '
            f'width="{box_size}" height="{box_size}" shape-rendering="crispEdges">'
            f'<rect width="{full}" height="{full}" fill="#FFFFFF" rx="2"/>'
            f'<g fill="#0B0E14">{"".join(rects)}</g></svg>'
        )

    @classmethod
    def render_terminal(cls, text: str) -> str:
        grid = cls.encode_matrix(text)
        size = len(grid)
        quiet = 2
        full = size + quiet * 2
        padded = [[0] * full for _ in range(full)]
        for r in range(size):
            for c in range(size):
                padded[r + quiet][c + quiet] = grid[r][c]
        lines = []
        for r in range(0, full, 2):
            row_chars = []
            for c in range(full):
                top_white = padded[r][c] == 0
                bot_white = (padded[r + 1][c] == 0) if (r + 1 < full) else True
                if top_white and bot_white:
                    row_chars.append("█")
                elif top_white and not bot_white:
                    row_chars.append("▀")
                elif not top_white and bot_white:
                    row_chars.append("▄")
                else:
                    row_chars.append(" ")
            lines.append("  " + "".join(row_chars))
        return "\n".join(lines)


# ==============================================================================
# 2. Default Full-Featured Layout & Config Persistence
# ==============================================================================
def default_config() -> dict:
    return {
        "language": DEFAULT_LANGUAGE,
        "token": secrets.token_hex(6),
        "pcName": socket.gethostname(),
        "repoPath": os.getcwd(),
        "apps": [
            {"id": "app_terminal", "label": "Terminal", "command": "terminal", "icon": "terminal", "color": "cyan"},
            {"id": "app_vscode", "label": "VS Code", "command": "vscode", "icon": "code", "color": "violet"},
            {"id": "app_browser", "label": "Browser", "command": "browser", "icon": "public", "color": "emerald"},
            {"id": "app_explorer", "label": "Files", "command": "explorer", "icon": "folder", "color": "amber"},
            {"id": "app_docker", "label": "Docker", "command": "docker_desktop", "icon": "dns", "color": "cyan"},
        ],
        "macros": [
            {"id": "macro_lock", "label": "Lock Screen", "hotkey": "Win+L / Ctrl+Cmd+Q", "command": "lock_screen"},
            {"id": "macro_shot", "label": "Screenshot", "hotkey": "PrtSc", "command": "screenshot"},
            {"id": "macro_dev", "label": "Run Dev Server", "hotkey": "npm run dev", "command": "echo DeskDeck Dev Triggered"},
            {"id": "macro_clean", "label": "Clear Terminal", "hotkey": "Ctrl+L", "command": "echo Clear"},
        ],
        "layout": {
            "version": 1,
            "presetName": "Standard Stream Deck",
            "pages": [
                {
                    "id": "page_overview",
                    "title": "Overview & Focus",
                    "widgets": [
                        {"id": "w_clock", "type": "CLOCK_WEATHER", "title": "Clock & System", "colSpan": 2, "accentColorKey": "cyan"},
                        {"id": "w_pomo", "type": "POMODORO_TIMER", "title": "Pomodoro Focus", "colSpan": 2, "accentColorKey": "red"},
                        {"id": "w_cpu", "type": "CPU_LOAD", "title": "CPU", "colSpan": 1, "accentColorKey": "cyan"},
                        {"id": "w_ram", "type": "RAM_USAGE", "title": "RAM", "colSpan": 1, "accentColorKey": "violet"},
                        {"id": "w_media", "type": "MEDIA_PLAYER", "title": "Media Player", "colSpan": 2, "accentColorKey": "cyan"},
                        {"id": "w_vol", "type": "VOLUME_MIXER", "title": "PC Volume", "colSpan": 2, "accentColorKey": "amber"},
                        {"id": "w_net", "type": "NETWORK_SPEED", "title": "Network & Disk", "colSpan": 2, "accentColorKey": "emerald"},
                    ],
                },
                {
                    "id": "page_shortcuts",
                    "title": "App & Macro Deck",
                    "widgets": [
                        {"id": "w_apps", "type": "APP_LAUNCHER", "title": "App Launcher", "colSpan": 2, "accentColorKey": "cyan"},
                        {"id": "w_macros", "type": "MACRO_BUTTON", "title": "Macro Pad", "colSpan": 2, "accentColorKey": "violet"},
                        {"id": "w_gpu", "type": "GPU_STATS", "title": "GPU & Temp", "colSpan": 2, "accentColorKey": "amber"},
                    ],
                },
                {
                    "id": "page_dev",
                    "title": "Dev & Alerts",
                    "widgets": [
                        {"id": "w_ai", "type": "AI_QUOTA_PROMPT", "title": "AI Coding & Approvals", "colSpan": 2, "accentColorKey": "violet"},
                        {"id": "w_dev", "type": "DEV_DOCKER_GIT", "title": "Docker & Git Status", "colSpan": 2, "accentColorKey": "emerald"},
                        {"id": "w_notif", "type": "PC_NOTIFICATIONS", "title": "PC Notifications", "colSpan": 4, "accentColorKey": "amber"},
                    ],
                },
            ],
        },
    }


def load_or_create_config() -> dict:
    CONFIG_DIR.mkdir(parents=True, exist_ok=True)
    if CONFIG_FILE.exists():
        try:
            with open(CONFIG_FILE, "r", encoding="utf-8") as f:
                data = json.load(f)
                if "token" in data and "layout" in data:
                    data["language"] = normalize_language(data.get("language"))
                    return data
        except Exception:
            pass
    cfg = default_config()
    save_config(cfg)
    return cfg


def save_config(cfg: dict):
    cfg["language"] = normalize_language(cfg.get("language"))
    CONFIG_DIR.mkdir(parents=True, exist_ok=True)
    with open(CONFIG_FILE, "w", encoding="utf-8") as f:
        json.dump(cfg, f, indent=2, ensure_ascii=False)


# ==============================================================================
# 3. Lightweight System & Dev Telemetry Collector (~0 overhead)
# ==============================================================================
try:
    import psutil
except ImportError:
    psutil = None


class TelemetryCollector:
    def __init__(self, config: dict):
        self.config = config
        self.hostname = socket.gethostname()
        self.boot_time = time.time()
        self.prev_cpu = None
        self.prev_net = None
        self.prev_net_t = time.time()
        self.cpu_history = [18.0, 22.0, 20.0, 25.0, 21.0, 19.0, 24.0, 23.0]
        self.ram_history = [46.0, 46.2, 46.5, 46.4, 47.0, 46.8, 47.1, 47.0]
        lang = normalize_language(config.get("language"))
        self.has_custom_media = False
        self.media_playing = False
        self.media_title = tr(lang, "media_idle")
        self.media_artist = tr(lang, "media_source")
        self.master_volume = 68
        self.is_muted = False
        self.mic_muted = False
        self.ai_pending_prompt = None
        self.notifications = [
            {
                "id": "notif_welcome",
                "app": "DeskDeck Agent",
                "title": tr(lang, "notif_welcome_title"),
                "body": tr(lang, "notif_welcome_body", self.hostname),
                "time": time.strftime("%H:%M"),
            }
        ]
        self._last_dev_poll = 0.0
        self._cached_docker = []
        self._cached_git = {
            "repo": os.path.basename(config.get("repoPath", os.getcwd())),
            "branch": "main",
            "changed": 0,
            "prs": 0,
        }

    def sync_language(self, lang: str):
        norm = normalize_language(lang)
        if not self.has_custom_media:
            self.media_title = tr(norm, "media_idle")
            self.media_artist = tr(norm, "media_source")
        for n in self.notifications:
            if n.get("id") == "notif_welcome":
                n["title"] = tr(norm, "notif_welcome_title")
                n["body"] = tr(norm, "notif_welcome_body", self.hostname)

    def get_agent_rss_mb(self) -> float:
        if psutil:
            try:
                return round(psutil.Process(os.getpid()).memory_info().rss / (1024 * 1024), 1)
            except Exception:
                pass
        sys_name = platform.system()
        if sys_name == "Linux":
            try:
                with open("/proc/self/statm", "r", encoding="utf-8") as f:
                    rss_pages = int(f.read().split()[1])
                    return round(rss_pages * os.sysconf("SC_PAGE_SIZE") / (1024 * 1024), 1)
            except Exception:
                pass
        return 19.5

    def _read_cpu_ram_stdlib(self):
        sys_name = platform.system()
        cpu_pct = 15.0
        ram_used, ram_total = 7.4, 16.0

        if psutil:
            cpu_pct = round(psutil.cpu_percent(interval=None), 1)
            vm = psutil.virtual_memory()
            ram_used = round((vm.total - vm.available) / (1024 ** 3), 1)
            ram_total = round(vm.total / (1024 ** 3), 1)
            return cpu_pct, ram_used, ram_total

        if sys_name == "Linux":
            try:
                with open("/proc/stat", "r", encoding="utf-8") as f:
                    parts = [int(x) for x in f.readline().split()[1:8]]
                idle = parts[3] + parts[4]
                total = sum(parts)
                if self.prev_cpu is not None:
                    d_total = total - self.prev_cpu[0]
                    d_idle = idle - self.prev_cpu[1]
                    if d_total > 0:
                        cpu_pct = round(100.0 * (1.0 - d_idle / d_total), 1)
                self.prev_cpu = (total, idle)
                mem = {}
                with open("/proc/meminfo", "r", encoding="utf-8") as f:
                    for line in f:
                        k, v = line.split(":", 1)
                        mem[k] = int(v.strip().split()[0])
                total_kb = mem.get("MemTotal", 16 * 1024 * 1024)
                avail_kb = mem.get("MemAvailable", total_kb // 2)
                ram_total = round(total_kb / (1024 * 1024), 1)
                ram_used = round((total_kb - avail_kb) / (1024 * 1024), 1)
            except Exception:
                pass
        elif sys_name == "Windows":
            try:
                class MEMORYSTATUSEX(ctypes.Structure):
                    _fields_ = [
                        ("dwLength", ctypes.c_ulong),
                        ("dwMemoryLoad", ctypes.c_ulong),
                        ("ullTotalPhys", ctypes.c_ulonglong),
                        ("ullAvailPhys", ctypes.c_ulonglong),
                        ("ullTotalPageFile", ctypes.c_ulonglong),
                        ("ullAvailPageFile", ctypes.c_ulonglong),
                        ("ullTotalVirtual", ctypes.c_ulonglong),
                        ("ullAvailVirtual", ctypes.c_ulonglong),
                        ("sullAvailExtendedVirtual", ctypes.c_ulonglong),
                    ]

                stat = MEMORYSTATUSEX()
                stat.dwLength = ctypes.sizeof(MEMORYSTATUSEX)
                ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(stat))
                ram_total = round(stat.ullTotalPhys / (1024 ** 3), 1)
                ram_used = round((stat.ullTotalPhys - stat.ullAvailPhys) / (1024 ** 3), 1)

                class FILETIME(ctypes.Structure):
                    _fields_ = [("dwLowDateTime", ctypes.c_uint32), ("dwHighDateTime", ctypes.c_uint32)]

                idle_t, kern_t, user_t = FILETIME(), FILETIME(), FILETIME()
                ctypes.windll.kernel32.GetSystemTimes(ctypes.byref(idle_t), ctypes.byref(kern_t), ctypes.byref(user_t))
                to_int = lambda ft: (ft.dwHighDateTime << 32) | ft.dwLowDateTime
                idle, total = to_int(idle_t), to_int(kern_t) + to_int(user_t)
                if self.prev_cpu is not None:
                    d_total = total - self.prev_cpu[0]
                    d_idle = idle - self.prev_cpu[1]
                    if d_total > 0:
                        cpu_pct = round(max(0.0, min(100.0, 100.0 * (1.0 - d_idle / d_total))), 1)
                self.prev_cpu = (total, idle)
            except Exception:
                pass
        elif sys_name == "Darwin":
            try:
                load1 = os.getloadavg()[0]
                cores = os.cpu_count() or 4
                cpu_pct = round(min(100.0, (load1 / cores) * 100.0), 1)
            except Exception:
                pass

        return cpu_pct, ram_used, ram_total

    def _read_net_kbps(self):
        now = time.time()
        dt = max(0.5, now - self.prev_net_t)
        down_kbps, up_kbps = 0.0, 0.0
        if psutil:
            io = psutil.net_io_counters()
            cur = (io.bytes_recv, io.bytes_sent)
            if self.prev_net:
                down_kbps = round((cur[0] - self.prev_net[0]) / dt / 1024.0, 1)
                up_kbps = round((cur[1] - self.prev_net[1]) / dt / 1024.0, 1)
            self.prev_net = cur
            self.prev_net_t = now
            return max(0.0, down_kbps), max(0.0, up_kbps)
        if platform.system() == "Linux":
            try:
                rx, tx = 0, 0
                with open("/proc/net/dev", "r", encoding="utf-8") as f:
                    for line in f.readlines()[2:]:
                        parts = line.split()
                        if len(parts) >= 10 and not parts[0].startswith("lo"):
                            rx += int(parts[1])
                            tx += int(parts[9])
                if self.prev_net:
                    down_kbps = round((rx - self.prev_net[0]) / dt / 1024.0, 1)
                    up_kbps = round((tx - self.prev_net[1]) / dt / 1024.0, 1)
                self.prev_net = (rx, tx)
                self.prev_net_t = now
            except Exception:
                pass
        return max(0.0, down_kbps), max(0.0, up_kbps)

    def _poll_dev_status_if_due(self):
        now = time.time()
        if now - self._last_dev_poll < 5.0:
            return
        self._last_dev_poll = now
        sub_kw = hidden_subprocess_kwargs()
        if shutil.which("docker"):
            try:
                out = subprocess.check_output(
                    ["docker", "ps", "-a", "--format", "{{.Names}}|{{.State}}|{{.Status}}"],
                    stderr=subprocess.DEVNULL,
                    timeout=1.5,
                    text=True,
                    **sub_kw,
                )
                containers = []
                for line in out.strip().splitlines()[:6]:
                    parts = line.split("|", 2)
                    if len(parts) == 3:
                        containers.append({"name": parts[0], "state": parts[1], "status": parts[2]})
                self._cached_docker = containers
            except Exception:
                self._cached_docker = []

        repo = self.config.get("repoPath", os.getcwd())
        if shutil.which("git") and os.path.isdir(repo):
            try:
                branch = subprocess.check_output(
                    ["git", "-C", repo, "rev-parse", "--abbrev-ref", "HEAD"],
                    stderr=subprocess.DEVNULL,
                    timeout=1.0,
                    text=True,
                    **sub_kw,
                ).strip()
                st = subprocess.check_output(
                    ["git", "-C", repo, "status", "--porcelain"],
                    stderr=subprocess.DEVNULL,
                    timeout=1.0,
                    text=True,
                    **sub_kw,
                )
                changed = len([ln for ln in st.splitlines() if ln.strip()])
                self._cached_git = {
                    "repo": os.path.basename(os.path.abspath(repo)),
                    "branch": branch or "main",
                    "changed": changed,
                    "prs": 0,
                }
            except Exception:
                pass

    def collect(self) -> dict:
        lang = normalize_language(self.config.get("language"))
        cpu_pct, ram_used, ram_total = self._read_cpu_ram_stdlib()
        down_kbps, up_kbps = self._read_net_kbps()
        disk_pct = 0.0
        try:
            du = shutil.disk_usage(Path.home())
            disk_pct = round(du.used / du.total * 100.0, 1)
        except Exception:
            pass

        self.cpu_history = (self.cpu_history + [cpu_pct])[-20:]
        ram_pct = round((ram_used / max(0.1, ram_total)) * 100.0, 1)
        self.ram_history = (self.ram_history + [ram_pct])[-20:]

        self._poll_dev_status_if_due()
        up_sec = int(time.time() - self.boot_time)
        hrs, rem = divmod(up_sec, 3600)
        mins, _ = divmod(rem, 60)

        return {
            "type": "telemetry",
            "timestamp": int(time.time() * 1000),
            "cpuPercent": cpu_pct,
            "cpuTempC": round(42.0 + cpu_pct * 0.35, 1),
            "ramUsedGb": ram_used,
            "ramTotalGb": ram_total,
            "gpuPercent": round(max(4.0, cpu_pct * 0.65), 1),
            "gpuTempC": round(40.0 + cpu_pct * 0.3, 1),
            "netDownKbps": down_kbps,
            "netUpKbps": up_kbps,
            "diskPercent": disk_pct,
            "uptimeFormatted": f"{hrs}h {mins:02d}m",
            "agentRssMb": self.get_agent_rss_mb(),
            "mediaTitle": self.media_title,
            "mediaArtist": self.media_artist,
            "mediaPlaying": self.media_playing,
            "masterVolume": self.master_volume,
            "isMuted": self.is_muted,
            "micMuted": self.mic_muted,
            "cpuHistory": self.cpu_history,
            "ramHistory": self.ram_history,
            "dockerContainers": self._cached_docker,
            "gitRepoName": self._cached_git["repo"],
            "gitBranch": self._cached_git["branch"],
            "gitChangedFiles": self._cached_git["changed"],
            "gitOpenPrs": self._cached_git["prs"],
            "aiProvider": "Local Dev Session",
            "aiQuotaRemainingPct": 82,
            "aiBurnRateText": tr(lang, "burn_stable"),
            "aiPendingPrompt": self.ai_pending_prompt,
            "notifications": self.notifications[:6],
            "apps": self.config.get("apps", []),
            "macros": self.config.get("macros", []),
        }


# ==============================================================================
# 4. Cross-Platform Action Executor (App Launch, Terminal, Volume, Media, Macro)
# ==============================================================================
class ActionExecutor:
    def __init__(self, config: dict, collector: TelemetryCollector):
        self.config = config
        self.collector = collector

    def launch_app(self, app_key: str) -> str:
        lang = normalize_language(self.config.get("language"))
        sys_name = platform.system()
        cmd = app_key
        for a in self.config.get("apps", []):
            if a.get("id") == app_key or a.get("command") == app_key:
                cmd = a.get("command", app_key)
                break

        try:
            if cmd == "terminal":
                if sys_name == "Windows":
                    target = "wt.exe" if shutil.which("wt") else "cmd.exe"
                    subprocess.Popen([target], creationflags=0x00000010)
                elif sys_name == "Darwin":
                    subprocess.Popen(["open", "-a", "Terminal"])
                else:
                    for term in ("x-terminal-emulator", "gnome-terminal", "konsole", "xfce4-terminal", "kitty", "alacritty", "xterm"):
                        if shutil.which(term):
                            subprocess.Popen([term])
                            break
                return tr(lang, "ack_terminal_opened")
            elif cmd == "vscode":
                if shutil.which("code"):
                    subprocess.Popen(["code", self.config.get("repoPath", os.getcwd())], **hidden_subprocess_kwargs())
                    return tr(lang, "ack_vscode_opened")
                return tr(lang, "ack_vscode_missing")
            elif cmd == "browser":
                webbrowser.open("https://github.com")
                return tr(lang, "ack_browser_opened")
            elif cmd == "explorer":
                path = self.config.get("repoPath", str(Path.home()))
                if sys_name == "Windows":
                    subprocess.Popen(["explorer", path])
                elif sys_name == "Darwin":
                    subprocess.Popen(["open", path])
                else:
                    subprocess.Popen(["xdg-open", path])
                return tr(lang, "ack_files_opened")
            elif cmd == "docker_desktop":
                if sys_name == "Windows" and shutil.which("Docker Desktop"):
                    subprocess.Popen(["Docker Desktop"])
                elif sys_name == "Darwin":
                    subprocess.Popen(["open", "-a", "Docker"])
                else:
                    subprocess.Popen(["xdg-open", "http://localhost:9876"])
                return tr(lang, "ack_docker_launched")
            else:
                subprocess.Popen(cmd, shell=True, **hidden_subprocess_kwargs())
                return tr(lang, "ack_app_opened", cmd)
        except Exception as e:
            return tr(lang, "ack_app_err", e)

    def execute(self, event: dict) -> dict:
        lang = normalize_language(self.config.get("language"))
        etype = event.get("type", "")
        val = str(event.get("value", ""))
        wid = event.get("widgetId", "")
        msg = tr(lang, "ack_executed")

        if etype == "LAUNCH_APP":
            msg = self.launch_app(val)
        elif etype == "TRIGGER_MACRO":
            macro_cmd = val
            for m in self.config.get("macros", []):
                if m.get("id") == val:
                    macro_cmd = m.get("command", val)
                    break
            if macro_cmd == "lock_screen":
                sys_name = platform.system()
                if sys_name == "Windows":
                    ctypes.windll.user32.LockWorkStation()
                elif sys_name == "Darwin":
                    subprocess.Popen(["pmset", "displaysleepnow"])
                elif shutil.which("loginctl"):
                    subprocess.Popen(["loginctl", "lock-session"])
                msg = tr(lang, "ack_screen_locked")
            elif macro_cmd == "screenshot":
                self._send_media_key(0x2C)
                msg = tr(lang, "ack_screenshot")
            else:
                subprocess.Popen(macro_cmd, shell=True, **hidden_subprocess_kwargs())
                msg = tr(lang, "ack_macro_ran", val)
        elif etype == "SET_VOLUME":
            try:
                vol = max(0, min(100, int(float(val))))
                self.collector.master_volume = vol
                self._apply_system_volume(vol)
                msg = tr(lang, "ack_volume_set", vol)
            except Exception:
                pass
        elif etype == "TOGGLE_MUTE":
            self.collector.is_muted = not self.collector.is_muted
            self._apply_system_mute(self.collector.is_muted)
            msg = tr(lang, "ack_muted" if self.collector.is_muted else "ack_unmuted")
        elif etype == "TOGGLE_MIC_MUTE":
            self.collector.mic_muted = not self.collector.mic_muted
            msg = tr(lang, "ack_mic_muted" if self.collector.mic_muted else "ack_mic_unmuted")
        elif etype == "MEDIA_CMD":
            if val == "play_pause":
                self.collector.media_playing = not self.collector.media_playing
                self._send_media_key(0xB3)
                msg = tr(lang, "ack_media_toggle")
            elif val == "next":
                self._send_media_key(0xB0)
                msg = tr(lang, "ack_media_next")
            elif val == "prev":
                self._send_media_key(0xB1)
                msg = tr(lang, "ack_media_prev")
        elif etype == "DOCKER_TOGGLE":
            try:
                name, action = val.split(":", 1)
                docker_cmd = "start" if action == "start" else "stop"
                subprocess.Popen(["docker", docker_cmd, name], **hidden_subprocess_kwargs())
                msg = tr(lang, "ack_docker_action", docker_cmd, name)
            except Exception as e:
                msg = tr(lang, "ack_docker_err", e)
        elif etype == "AI_DECISION":
            self.collector.ai_pending_prompt = None
            msg = tr(lang, "ack_ai_allowed" if val == "allow" else "ack_ai_denied")
        elif etype == "DISMISS_NOTIF":
            self.collector.notifications = [n for n in self.collector.notifications if n["id"] != val]
            msg = tr(lang, "ack_notif_dismissed")

        return {"type": "ack", "actionId": event.get("actionId", ""), "widgetId": wid, "ok": True, "message": msg}

    def _apply_system_volume(self, vol: int):
        sys_name = platform.system()
        try:
            if sys_name == "Darwin":
                subprocess.Popen(["osascript", "-e", f"set volume output volume {vol}"])
            elif sys_name == "Linux" and shutil.which("pactl"):
                subprocess.Popen(["pactl", "set-sink-volume", "@DEFAULT_SINK@", f"{vol}%"])
        except Exception:
            pass

    def _apply_system_mute(self, muted: bool):
        sys_name = platform.system()
        try:
            if sys_name == "Darwin":
                subprocess.Popen(["osascript", "-e", f"set volume output muted {'true' if muted else 'false'}"])
            elif sys_name == "Linux" and shutil.which("pactl"):
                subprocess.Popen(["pactl", "set-sink-mute", "@DEFAULT_SINK@", "1" if muted else "0"])
            elif sys_name == "Windows":
                ctypes.windll.user32.keybd_event(0xAD, 0, 0, 0)
                ctypes.windll.user32.keybd_event(0xAD, 0, 2, 0)
        except Exception:
            pass

    def _send_media_key(self, vk_code: int):
        if platform.system() == "Windows":
            try:
                ctypes.windll.user32.keybd_event(vk_code, 0, 0, 0)
                ctypes.windll.user32.keybd_event(vk_code, 0, 2, 0)
            except Exception:
                pass


# ==============================================================================
# 5. Embedded Responsive + i18n PC Web Dashboard
# ==============================================================================
DASHBOARD_HTML_TEMPLATE = """<!DOCTYPE html>
<html lang="__LANG__">
<head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width,initial-scale=1" />
<title>DeskDeck PC Dashboard — __PC_NAME__</title>
<style>
  :root {
    --bg: #0B0E14;
    --surface: #131822;
    --elev: #1A2130;
    --border: #252F42;
    --cyan: #38BDF8;
    --violet: #A78BFA;
    --green: #22C55E;
    --amber: #F59E0B;
    --red: #EF4444;
    --text: #F1F5F9;
    --muted: #94A3B8;
    --pad-page: clamp(12px, 2vw, 24px);
    --pad-card: clamp(14px, 1.8vw, 22px);
    --gap-grid: clamp(12px, 1.6vw, 20px);
    --radius-card: clamp(12px, 1.4vw, 18px);
    --fs-base: clamp(12.5px, 0.85vw + 4px, 14px);
    --fs-title: clamp(16px, 1.25vw + 6px, 20px);
    --fs-sub: clamp(13.5px, 1vw + 5px, 16px);
  }
  * { box-sizing: border-box; }
  html, body { max-width: 100%; overflow-x: hidden; }
  body {
    margin: 0;
    font-family: system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;
    font-size: var(--fs-base);
    line-height: 1.45;
    background: var(--bg);
    color: var(--text);
    padding: var(--pad-page);
  }
  .shell {
    width: 100%;
    max-width: 1680px;
    margin: 0 auto;
    display: flex;
    flex-direction: column;
    gap: var(--gap-grid);
  }
  .topbar {
    background: var(--surface);
    border: 1px solid var(--border);
    border-radius: var(--radius-card);
    padding: clamp(10px, 1.2vw, 16px) var(--pad-card);
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
  }
  .brand-group {
    display: flex;
    align-items: center;
    gap: 10px;
    flex-wrap: wrap;
    min-width: 0;
  }
  .brand-title {
    margin: 0;
    font-size: var(--fs-title);
    font-weight: 700;
    letter-spacing: -0.02em;
  }
  .topbar-actions {
    display: flex;
    align-items: center;
    gap: 8px;
    flex-wrap: wrap;
  }
  .wrap {
    width: 100%;
    display: grid;
    grid-template-columns: clamp(270px, 26vw, 360px) minmax(0, 1fr);
    gap: var(--gap-grid);
    align-items: start;
  }
  @media (max-width: 960px) {
    .wrap { grid-template-columns: minmax(0, 1fr); }
  }
  @media (min-aspect-ratio: 20/9) and (min-width: 1400px) {
    .wrap { grid-template-columns: clamp(300px, 22vw, 380px) minmax(0, 1fr); }
  }
  .card {
    background: var(--surface);
    border: 1px solid var(--border);
    border-radius: var(--radius-card);
    padding: var(--pad-card);
    min-width: 0;
  }
  h2 { margin: 0 0 6px; font-size: var(--fs-title); font-weight: 700; letter-spacing: -0.02em; }
  h3 { margin: 0 0 10px; font-size: var(--fs-sub); font-weight: 700; letter-spacing: -0.01em; }
  .muted { color: var(--muted); font-size: clamp(11.5px, 0.75vw + 3px, 13px); }
  .badge {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    padding: 4px 10px;
    border-radius: 999px;
    font-size: 12px;
    font-weight: 600;
    background: #1E293B;
    border: 1px solid var(--border);
    white-space: nowrap;
  }
  .dot { width: 8px; height: 8px; border-radius: 50%; background: var(--green); flex-shrink: 0; }
  .qr-box {
    display: flex;
    justify-content: center;
    align-items: center;
    padding: clamp(10px, 1.2vw, 16px);
    background: #fff;
    border-radius: 14px;
    margin: 14px 0;
    width: 100%;
  }
  .qr-box svg {
    width: 100%;
    max-width: clamp(160px, 18vw, 220px);
    height: auto;
    aspect-ratio: 1 / 1;
    display: block;
  }
  .mono {
    font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
    font-size: clamp(11.5px, 0.75vw + 3px, 12.5px);
    color: var(--cyan);
    overflow-wrap: anywhere;
    word-break: break-word;
  }
  .row {
    display: flex;
    gap: 8px;
    flex-wrap: wrap;
    align-items: center;
    min-width: 0;
  }
  .btn {
    background: var(--cyan);
    color: #061018;
    border: 0;
    border-radius: 10px;
    padding: 8px 13px;
    min-height: 36px;
    font-weight: 700;
    cursor: pointer;
    font-size: clamp(12px, 0.75vw + 3px, 13px);
    display: inline-flex;
    align-items: center;
    justify-content: center;
    gap: 6px;
    white-space: nowrap;
    max-width: 100%;
  }
  .btn:hover:not(:disabled) { filter: brightness(1.08); }
  .btn:disabled { opacity: 0.45; cursor: not-allowed; }
  .btn-sec { background: var(--elev); color: var(--text); border: 1px solid var(--border); }
  .btn-danger { background: rgba(239, 68, 68, 0.14); color: #FCA5A5; border: 1px solid rgba(239, 68, 68, 0.45); }
  .btn-danger:hover:not(:disabled) { background: rgba(239, 68, 68, 0.25); }
  .action-grid {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(min(100%, 155px), 1fr));
    gap: 8px;
  }
  .action-grid .btn { width: 100%; white-space: normal; text-align: center; }
  .apps-grid {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(min(100%, 240px), 1fr));
    gap: 8px;
    margin-bottom: 12px;
  }
  .pages-grid {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(min(100%, 440px), 1fr));
    gap: 12px;
  }
  .page-card {
    padding: clamp(12px, 1.3vw, 16px);
    border: 1px solid var(--border);
    border-radius: 12px;
    background: var(--bg);
    min-width: 0;
    display: flex;
    flex-direction: column;
    gap: 10px;
  }
  .widget-chip {
    display: flex;
    justify-content: space-between;
    align-items: center;
    background: var(--elev);
    border: 1px solid var(--border);
    padding: 8px 10px;
    border-radius: 12px;
    gap: 8px;
    flex-wrap: wrap;
    min-width: 0;
  }
  input, select {
    background: var(--bg);
    border: 1px solid var(--border);
    color: var(--text);
    padding: 8px 10px;
    min-height: 36px;
    border-radius: 8px;
    font-size: clamp(12px, 0.75vw + 3px, 13px);
    max-width: 100%;
    min-width: 0;
  }
  .lang-label { font-size: 12px; color: var(--muted); font-weight: 600; }
  .toast {
    position: fixed;
    bottom: clamp(12px, 2vw, 24px);
    right: clamp(12px, 2vw, 24px);
    left: auto;
    max-width: calc(100vw - 24px);
    background: var(--green);
    color: #052e16;
    font-weight: 700;
    padding: 10px 16px;
    border-radius: 10px;
    display: none;
    z-index: 60;
    box-shadow: 0 8px 24px rgba(0, 0, 0, 0.4);
  }
  .modal-backdrop {
    position: fixed;
    inset: 0;
    background: rgba(5, 8, 15, 0.72);
    display: none;
    align-items: center;
    justify-content: center;
    padding: 16px;
    z-index: 80;
  }
  .modal-backdrop.open { display: flex; }
  .modal-card {
    background: var(--surface);
    border: 1px solid var(--border);
    border-radius: 16px;
    padding: 20px;
    width: min(420px, 100%);
    box-shadow: 0 20px 50px rgba(0, 0, 0, 0.55);
  }
</style>
</head>
<body>
<div class="shell">
  <header class="topbar">
    <div class="brand-group">
      <h1 class="brand-title">DeskDeck PC</h1>
      <span class="badge"><span class="dot"></span><span>__RSS_MB__ MB RAM</span></span>
      <span class="badge" style="color:var(--cyan)">__PC_NAME__</span>
    </div>
    <div class="topbar-actions">
      <label for="lang-select" class="lang-label" data-i18n="langLabel">Language</label>
      <select id="lang-select" onchange="changeLanguage(this.value)">
        <option value="en">English</option>
        <option value="vi">Tiếng Việt</option>
      </select>
      <button class="btn btn-danger" onclick="openStopModal()" data-i18n="stopAgentBtn">Stop Agent</button>
    </div>
  </header>

  <main class="wrap">
    <section class="card">
      <h2 data-i18n="pairTitle">Connect Phone</h2>
      <p class="muted" style="margin-top:4px;" data-i18n="pairSubtitle">Scan this QR code with the DeskDeck Android app for 1-tap Wi-Fi pairing:</p>
      <div class="qr-box">__QR_SVG__</div>
      <div class="mono"><span data-i18n="wifiLabel">Wi-Fi</span>: __LAN_IP__:__PORT__</div>
      <div class="mono" style="color:var(--violet);margin-top:4px;"><span data-i18n="tokenLabel">Token</span>: __TOKEN__</div>
      <hr style="border:0;border-top:1px solid var(--border);margin:16px 0">
      <div class="action-grid">
        <button class="btn btn-sec" onclick="runAdbReverse()" data-i18n="btnAdb">Enable USB (ADB Reverse)</button>
        <button class="btn btn-sec" onclick="sendTestNotif()" data-i18n="btnTestNotif">Send Test Notification</button>
        <button class="btn btn-sec" onclick="sendTestAi()" data-i18n="btnTestAi">Test AI Approval</button>
      </div>
    </section>

    <section class="card">
      <div class="row" style="justify-content:space-between;margin-bottom:14px">
        <div style="flex:1 1 260px;min-width:0">
          <h2 data-i18n="editorTitle">Dashboard &amp; Shortcuts Editor</h2>
          <div class="muted" data-i18n="editorSubtitle">Changes sync instantly to your connected phone</div>
        </div>
        <button class="btn" onclick="saveAndSync()" data-i18n="btnSaveSync">Save &amp; Sync Phone</button>
      </div>

      <div class="row" style="margin-bottom:16px">
        <span class="muted" data-i18n="presetsLabel">Quick Presets:</span>
        <button class="btn btn-sec" onclick="applyPreset('full')" data-i18n="presetFull">Standard Stream Deck</button>
        <button class="btn btn-sec" onclick="applyPreset('focus')" data-i18n="presetFocus">Focus Pomodoro</button>
        <button class="btn btn-sec" onclick="applyPreset('dev')" data-i18n="presetDev">Developer Cockpit</button>
        <button class="btn btn-sec" onclick="applyPreset('minimal')" data-i18n="presetMinimal">Minimal</button>
      </div>

      <h3 data-i18n="appsSectionTitle">App Launcher Shortcuts</h3>
      <div id="apps-list" class="apps-grid"></div>
      <div class="row" style="margin-bottom:18px">
        <input id="new-app-label" style="flex:1 1 160px" data-i18n-placeholder="phAppLabel" placeholder="Label (e.g. Terminal, Warp)" />
        <input id="new-app-cmd" style="flex:1 1 190px" data-i18n-placeholder="phAppCmd" placeholder="Command (e.g. terminal, code, wt.exe)" />
        <button class="btn btn-sec" onclick="addAppShortcut()" data-i18n="btnAddApp">+ Add App</button>
      </div>

      <div class="row" style="justify-content:space-between;margin-bottom:10px">
        <h3 style="margin:0" data-i18n="pagesSectionTitle">Deck Pages &amp; Widgets (Swipe Horizontally)</h3>
        <button class="btn btn-sec" onclick="addPage()" data-i18n="btnAddPage">+ Add Page</button>
      </div>
      <div id="pages-editor" class="pages-grid"></div>
    </section>
  </main>
</div>

<div id="confirm-modal" class="modal-backdrop" role="dialog" aria-modal="true">
  <div class="modal-card">
    <h3 data-i18n="stopModalTitle">Stop DeskDeck Background Agent?</h3>
    <p class="muted" style="margin:8px 0 16px" data-i18n="stopModalBody">This cleanly closes the background host process, WebSocket server, and discovery beacon.</p>
    <div class="row" style="justify-content:flex-end">
      <button class="btn btn-sec" onclick="closeStopModal()" data-i18n="btnCancel">Cancel</button>
      <button class="btn btn-danger" onclick="confirmStopAgent()" data-i18n="btnConfirmStop">Stop Now</button>
    </div>
  </div>
</div>

<div id="toast" class="toast"></div>
<script>
const PC_NAME = "__PC_NAME__";
let cfg = __CFG_JSON__;

const I18N = {
  en: {
    pageTitle: "DeskDeck PC Dashboard — {pc}",
    langLabel: "Language",
    stopAgentBtn: "Stop Agent",
    pairTitle: "Connect Phone",
    pairSubtitle: "Scan this QR code with the DeskDeck Android app for 1-tap Wi-Fi pairing:",
    wifiLabel: "Wi-Fi",
    tokenLabel: "Token",
    btnAdb: "Enable USB (ADB Reverse)",
    btnTestNotif: "Send Test Notification",
    btnTestAi: "Test AI Approval",
    editorTitle: "Dashboard & Shortcuts Editor",
    editorSubtitle: "Changes sync instantly to your connected phone",
    btnSaveSync: "Save & Sync Phone",
    presetsLabel: "Quick Presets:",
    presetFull: "Standard Stream Deck",
    presetFocus: "Focus Pomodoro",
    presetDev: "Developer Cockpit",
    presetMinimal: "Minimal",
    appsSectionTitle: "App Launcher Shortcuts",
    phAppLabel: "Label (e.g. Terminal, Warp)",
    phAppCmd: "Command (e.g. terminal, code, wt.exe)",
    btnAddApp: "+ Add App",
    pagesSectionTitle: "Deck Pages & Widgets (Swipe Horizontally)",
    btnAddPage: "+ Add Page",
    btnRemove: "Remove",
    pageBadge: "Page {n}",
    btnDeletePage: "Delete Page",
    colLabel: "{n} col",
    colsLabel: "{n} cols",
    btnAddWidget: "+ Add Widget",
    toastSaved: "Saved & synced to connected phone!",
    toastTestNotif: "Test notification pushed to phone",
    toastTestAi: "AI command approval request sent to phone",
    toastLangSaved: "Language switched to English",
    toastMissingAppFields: "Please enter both app label and command",
    toastStopped: "Background agent stopped cleanly.",
    toastStopError: "Could not reach shutdown endpoint.",
    stopModalTitle: "Stop DeskDeck Background Agent?",
    stopModalBody: "This cleanly closes the background host process, WebSocket server, and discovery beacon.",
    btnCancel: "Cancel",
    btnConfirmStop: "Stop Now",
    moveLeftTip: "Move page left",
    moveRightTip: "Move page right",
    moveUpTip: "Move widget up",
    moveDownTip: "Move widget down",
    removeWidgetTip: "Remove widget",
    widget_CLOCK_WEATHER: "Clock & System",
    widget_POMODORO_TIMER: "Pomodoro Focus",
    widget_CPU_LOAD: "CPU",
    widget_RAM_USAGE: "RAM",
    widget_GPU_STATS: "GPU & Temp",
    widget_NETWORK_SPEED: "Network & Disk",
    widget_MEDIA_PLAYER: "Media Player",
    widget_VOLUME_MIXER: "PC Volume",
    widget_APP_LAUNCHER: "App Launcher",
    widget_MACRO_BUTTON: "Macro Pad",
    widget_DEV_DOCKER_GIT: "Docker & Git Status",
    widget_AI_QUOTA_PROMPT: "AI Coding & Approvals",
    widget_PC_NOTIFICATIONS: "PC Notifications",
    page_overview: "Overview & Focus",
    page_shortcuts: "App & Macro Deck",
    page_dev: "Dev & Alerts",
    page_minimal: "Desk Minimal",
    page_focus_1: "Focus Station",
    page_focus_2: "Apps & System",
    page_dev_1: "Dev & Terminal",
    page_dev_2: "Telemetry & Focus",
    defaultNewPage: "Page {n}",
  },
  vi: {
    pageTitle: "Bảng điều khiển DeskDeck PC — {pc}",
    langLabel: "Ngôn ngữ",
    stopAgentBtn: "Dừng Agent",
    pairTitle: "Kết nối Điện thoại",
    pairSubtitle: "Quét mã QR bằng app DeskDeck để ghép nối Wi-Fi 1 chạm:",
    wifiLabel: "Wi-Fi",
    tokenLabel: "Token",
    btnAdb: "Kích hoạt USB (ADB Reverse)",
    btnTestNotif: "Gửi thử thông báo",
    btnTestAi: "Thử AI Approval",
    editorTitle: "Tùy chỉnh Dashboard & Shortcuts",
    editorSubtitle: "Thay đổi sẽ đồng bộ ngay lập tức xuống điện thoại đang kết nối",
    btnSaveSync: "Lưu & Đồng bộ Điện thoại",
    presetsLabel: "Mẫu nhanh:",
    presetFull: "Stream Deck Chuẩn",
    presetFocus: "Focus Pomodoro",
    presetDev: "Developer Cockpit",
    presetMinimal: "Tối giản",
    appsSectionTitle: "Shortcut Mở Ứng dụng (App Launcher)",
    phAppLabel: "Tên (vd: Terminal, Warp)",
    phAppCmd: "Lệnh (vd: terminal, code, wt.exe)",
    btnAddApp: "+ Thêm App",
    pagesSectionTitle: "Các Màn hình & Widget (Lướt ngang Stream Deck)",
    btnAddPage: "+ Thêm Màn hình",
    btnRemove: "Xóa",
    pageBadge: "Màn hình {n}",
    btnDeletePage: "Xóa màn hình",
    colLabel: "{n} cột",
    colsLabel: "{n} cột",
    btnAddWidget: "+ Thêm Widget",
    toastSaved: "Đã lưu & đồng bộ xuống điện thoại!",
    toastTestNotif: "Đã đẩy thông báo thử nghiệm xuống điện thoại",
    toastTestAi: "Đã gửi yêu cầu duyệt lệnh AI xuống điện thoại",
    toastLangSaved: "Đã chuyển ngôn ngữ sang Tiếng Việt",
    toastMissingAppFields: "Vui lòng nhập đầy đủ Tên và Lệnh",
    toastStopped: "Đã dừng tiến trình nền sạch sẽ.",
    toastStopError: "Không thể gửi yêu cầu dừng tới Agent.",
    stopModalTitle: "Dừng tiến trình nền DeskDeck?",
    stopModalBody: "Thao tác này sẽ đóng sạch sẽ WebSocket server, UDP discovery và tiến trình chạy ngầm.",
    btnCancel: "Hủy",
    btnConfirmStop: "Dừng ngay",
    moveLeftTip: "Chuyển trang sang trái",
    moveRightTip: "Chuyển trang sang phải",
    moveUpTip: "Đưa widget lên",
    moveDownTip: "Đưa widget xuống",
    removeWidgetTip: "Xóa widget",
    widget_CLOCK_WEATHER: "Đồng hồ & Hệ thống",
    widget_POMODORO_TIMER: "Pomodoro Focus",
    widget_CPU_LOAD: "CPU",
    widget_RAM_USAGE: "RAM",
    widget_GPU_STATS: "GPU & Nhiệt độ",
    widget_NETWORK_SPEED: "Mạng & Ổ đĩa",
    widget_MEDIA_PLAYER: "Trình phát Media",
    widget_VOLUME_MIXER: "Âm lượng PC",
    widget_APP_LAUNCHER: "Mở nhanh ứng dụng",
    widget_MACRO_BUTTON: "Phím tắt Macro",
    widget_DEV_DOCKER_GIT: "Docker & Git Status",
    widget_AI_QUOTA_PROMPT: "AI Coding & Duyệt lệnh",
    widget_PC_NOTIFICATIONS: "Thông báo PC",
    page_overview: "Tổng quan & Focus",
    page_shortcuts: "App & Macro Deck",
    page_dev: "Dev & Thông báo",
    page_minimal: "Desk Tối giản",
    page_focus_1: "Trạm Tập trung",
    page_focus_2: "Ứng dụng & Hệ thống",
    page_dev_1: "Dev & Terminal",
    page_dev_2: "Thông số & Focus",
    defaultNewPage: "Màn hình {n}",
  }
};

const WIDGET_TYPES = [
  { type: 'CLOCK_WEATHER', colSpan: 2, accent: 'cyan' },
  { type: 'POMODORO_TIMER', colSpan: 2, accent: 'red' },
  { type: 'CPU_LOAD', colSpan: 1, accent: 'cyan' },
  { type: 'RAM_USAGE', colSpan: 1, accent: 'violet' },
  { type: 'GPU_STATS', colSpan: 2, accent: 'amber' },
  { type: 'NETWORK_SPEED', colSpan: 2, accent: 'emerald' },
  { type: 'MEDIA_PLAYER', colSpan: 2, accent: 'cyan' },
  { type: 'VOLUME_MIXER', colSpan: 2, accent: 'amber' },
  { type: 'APP_LAUNCHER', colSpan: 2, accent: 'cyan' },
  { type: 'MACRO_BUTTON', colSpan: 2, accent: 'violet' },
  { type: 'DEV_DOCKER_GIT', colSpan: 2, accent: 'emerald' },
  { type: 'AI_QUOTA_PROMPT', colSpan: 2, accent: 'violet' },
  { type: 'PC_NOTIFICATIONS', colSpan: 4, accent: 'amber' },
];

function normalizeLang(l) {
  return String(l || '').toLowerCase().startsWith('vi') ? 'vi' : 'en';
}

let currentLang = normalizeLang((cfg && cfg.language) || localStorage.getItem('deskdeck_lang') || '__LANG__' || 'en');
if (cfg) cfg.language = currentLang;

function t(key, vars = {}) {
  const dict = I18N[currentLang] || I18N.en;
  let str = dict[key] || I18N.en[key] || key;
  for (const [k, v] of Object.entries(vars)) {
    str = str.replaceAll(`{${k}}`, String(v));
  }
  return str;
}

function esc(s) {
  return String(s || '').replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

function showToast(msg, isError = false) {
  const el = document.getElementById('toast');
  el.textContent = msg;
  el.style.background = isError ? '#EF4444' : '#22C55E';
  el.style.color = isError ? '#FFFFFF' : '#052E16';
  el.style.display = 'block';
  clearTimeout(window._toastTimer);
  window._toastTimer = setTimeout(() => { el.style.display = 'none'; }, 2600);
}

function applyStaticTranslations() {
  document.documentElement.lang = currentLang;
  document.title = t('pageTitle', { pc: PC_NAME });
  const sel = document.getElementById('lang-select');
  if (sel) sel.value = currentLang;

  document.querySelectorAll('[data-i18n]').forEach(el => {
    const k = el.getAttribute('data-i18n');
    if (k) el.textContent = t(k);
  });
  document.querySelectorAll('[data-i18n-placeholder]').forEach(el => {
    const k = el.getAttribute('data-i18n-placeholder');
    if (k) el.setAttribute('placeholder', t(k));
  });
}

async function changeLanguage(lang) {
  currentLang = normalizeLang(lang);
  localStorage.setItem('deskdeck_lang', currentLang);
  if (cfg) cfg.language = currentLang;
  applyStaticTranslations();
  render();
  try {
    await fetch('/api/config', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ language: currentLang })
    });
  } catch (_) {}
  showToast(t('toastLangSaved'));
}

function render() {
  const appsDiv = document.getElementById('apps-list');
  appsDiv.innerHTML = (cfg.apps || []).map((a, idx) => `
    <div class="widget-chip">
      <span style="min-width:0;overflow-wrap:anywhere"><strong>${esc(a.label)}</strong> <span class="mono">(${esc(a.command)})</span></span>
      <button class="btn btn-sec" onclick="cfg.apps.splice(${idx},1);render()">${esc(t('btnRemove'))}</button>
    </div>`).join('');

  const optsHtml = WIDGET_TYPES.map((item, i) => `<option value="${i}">${esc(t('widget_' + item.type))} (${esc(item.type)})</option>`).join('');
  const pagesDiv = document.getElementById('pages-editor');
  pagesDiv.innerHTML = (cfg.layout && cfg.layout.pages ? cfg.layout.pages : []).map((p, pi) => `
    <div class="page-card">
      <div class="row" style="justify-content:space-between">
        <div class="row" style="flex:1 1 200px">
          <span class="badge" style="color:var(--cyan)">${esc(t('pageBadge', { n: pi + 1 }))}</span>
          <input value="${esc(p.title)}" onchange="cfg.layout.pages[${pi}].title=this.value" style="flex:1 1 150px" />
        </div>
        <div class="row">
          <button class="btn btn-sec" title="${esc(t('moveLeftTip'))}" onclick="movePage(${pi},-1)" ${pi===0?'disabled':''}>←</button>
          <button class="btn btn-sec" title="${esc(t('moveRightTip'))}" onclick="movePage(${pi},1)" ${pi===cfg.layout.pages.length-1?'disabled':''}>→</button>
          <button class="btn btn-sec" onclick="deletePage(${pi})" ${cfg.layout.pages.length<=1?'disabled':''}>${esc(t('btnDeletePage'))}</button>
        </div>
      </div>

      <div style="display:flex;flex-direction:column;gap:8px">
        ${(p.widgets || []).map((w, wi) => `
          <div class="widget-chip">
            <input value="${esc(w.title)}" onchange="cfg.layout.pages[${pi}].widgets[${wi}].title=this.value" style="flex:1 1 135px" />
            <small class="mono" style="color:var(--muted)">${esc(w.type)}</small>
            <select onchange="cfg.layout.pages[${pi}].widgets[${wi}].colSpan=Number(this.value)">
              ${[1,2,3,4].map(c => `<option value="${c}" ${Number(w.colSpan)===c?'selected':''}>${esc(c===1 ? t('colLabel',{n:c}) : t('colsLabel',{n:c}))}</option>`).join('')}
            </select>
            <select onchange="cfg.layout.pages[${pi}].widgets[${wi}].accentColorKey=this.value">
              ${['cyan','violet','emerald','amber','red'].map(cl => `<option value="${cl}" ${(w.accentColorKey||'cyan')===cl?'selected':''}>${esc(cl)}</option>`).join('')}
            </select>
            <div class="row" style="gap:4px">
              <button class="btn btn-sec" title="${esc(t('moveUpTip'))}" onclick="moveWidget(${pi},${wi},-1)" ${wi===0?'disabled':''}>↑</button>
              <button class="btn btn-sec" title="${esc(t('moveDownTip'))}" onclick="moveWidget(${pi},${wi},1)" ${wi===p.widgets.length-1?'disabled':''}>↓</button>
              <button class="btn btn-sec" title="${esc(t('removeWidgetTip'))}" onclick="cfg.layout.pages[${pi}].widgets.splice(${wi},1);render()">×</button>
            </div>
          </div>`).join('')}
      </div>

      <div class="row">
        <select id="add-widget-sel-${pi}" style="flex:1 1 200px">${optsHtml}</select>
        <button class="btn btn-sec" onclick="addWidgetToPage(${pi})">${esc(t('btnAddWidget'))}</button>
      </div>
    </div>`).join('');
}

function addPage() {
  cfg.layout.pages.push({
    id: 'page_' + Date.now(),
    title: t('defaultNewPage', { n: cfg.layout.pages.length + 1 }),
    widgets: []
  });
  render();
}
function movePage(pi, dir) {
  const target = pi + dir;
  if (target < 0 || target >= cfg.layout.pages.length) return;
  const arr = cfg.layout.pages;
  [arr[pi], arr[target]] = [arr[target], arr[pi]];
  render();
}
function deletePage(pi) {
  if (cfg.layout.pages.length <= 1) return;
  cfg.layout.pages.splice(pi, 1);
  render();
}
function moveWidget(pi, wi, dir) {
  const arr = cfg.layout.pages[pi].widgets;
  const target = wi + dir;
  if (target < 0 || target >= arr.length) return;
  [arr[wi], arr[target]] = [arr[target], arr[wi]];
  render();
}
function addWidgetToPage(pi) {
  const sel = document.getElementById('add-widget-sel-' + pi);
  const item = WIDGET_TYPES[Number(sel.value)] || WIDGET_TYPES[0];
  cfg.layout.pages[pi].widgets.push({
    id: 'w_' + item.type.toLowerCase() + '_' + (Date.now() % 100000),
    type: item.type,
    title: t('widget_' + item.type),
    colSpan: item.colSpan,
    accentColorKey: item.accent,
  });
  render();
}
function addAppShortcut() {
  const label = document.getElementById('new-app-label').value.trim();
  const cmd = document.getElementById('new-app-cmd').value.trim();
  if (!label || !cmd) {
    showToast(t('toastMissingAppFields'), true);
    return;
  }
  cfg.apps.push({ id: 'app_' + Date.now(), label, command: cmd, icon: 'terminal', color: 'cyan' });
  document.getElementById('new-app-label').value = '';
  document.getElementById('new-app-cmd').value = '';
  render();
}
function makeW(id, type, colSpan, accent) {
  return { id, type, title: t('widget_' + type), colSpan, accentColorKey: accent };
}
function applyPreset(mode) {
  if (mode === 'minimal') {
    cfg.layout.presetName = t('presetMinimal');
    cfg.layout.pages = [{ id: 'p1', title: t('page_minimal'), widgets: [
      makeW('w_clock', 'CLOCK_WEATHER', 2, 'cyan'),
      makeW('w_pomo', 'POMODORO_TIMER', 2, 'red'),
      makeW('w_apps', 'APP_LAUNCHER', 2, 'cyan'),
      makeW('w_vol', 'VOLUME_MIXER', 2, 'amber'),
    ]}];
  } else if (mode === 'focus') {
    cfg.layout.presetName = t('presetFocus');
    cfg.layout.pages = [
      { id: 'p_f1', title: t('page_focus_1'), widgets: [
        makeW('w_pomo', 'POMODORO_TIMER', 2, 'red'),
        makeW('w_clock', 'CLOCK_WEATHER', 2, 'cyan'),
        makeW('w_media', 'MEDIA_PLAYER', 2, 'cyan'),
        makeW('w_vol', 'VOLUME_MIXER', 2, 'amber'),
      ]},
      { id: 'p_f2', title: t('page_focus_2'), widgets: [
        makeW('w_apps', 'APP_LAUNCHER', 2, 'cyan'),
        makeW('w_cpu', 'CPU_LOAD', 1, 'cyan'),
        makeW('w_ram', 'RAM_USAGE', 1, 'violet'),
      ]}
    ];
  } else if (mode === 'dev') {
    cfg.layout.presetName = t('presetDev');
    cfg.layout.pages = [
      { id: 'p_d1', title: t('page_dev_1'), widgets: [
        makeW('w_apps', 'APP_LAUNCHER', 2, 'cyan'),
        makeW('w_macros', 'MACRO_BUTTON', 2, 'violet'),
        makeW('w_ai', 'AI_QUOTA_PROMPT', 2, 'violet'),
        makeW('w_dev', 'DEV_DOCKER_GIT', 2, 'emerald'),
      ]},
      { id: 'p_d2', title: t('page_dev_2'), widgets: [
        makeW('w_pomo', 'POMODORO_TIMER', 2, 'red'),
        makeW('w_cpu', 'CPU_LOAD', 1, 'cyan'),
        makeW('w_ram', 'RAM_USAGE', 1, 'violet'),
        makeW('w_notif', 'PC_NOTIFICATIONS', 4, 'amber'),
      ]}
    ];
  } else {
    cfg.layout.presetName = t('presetFull');
    cfg.layout.pages = [
      { id: 'page_overview', title: t('page_overview'), widgets: [
        makeW('w_clock', 'CLOCK_WEATHER', 2, 'cyan'),
        makeW('w_pomo', 'POMODORO_TIMER', 2, 'red'),
        makeW('w_cpu', 'CPU_LOAD', 1, 'cyan'),
        makeW('w_ram', 'RAM_USAGE', 1, 'violet'),
        makeW('w_media', 'MEDIA_PLAYER', 2, 'cyan'),
        makeW('w_vol', 'VOLUME_MIXER', 2, 'amber'),
        makeW('w_net', 'NETWORK_SPEED', 2, 'emerald'),
      ]},
      { id: 'page_shortcuts', title: t('page_shortcuts'), widgets: [
        makeW('w_apps', 'APP_LAUNCHER', 2, 'cyan'),
        makeW('w_macros', 'MACRO_BUTTON', 2, 'violet'),
        makeW('w_gpu', 'GPU_STATS', 2, 'amber'),
      ]},
      { id: 'page_dev', title: t('page_dev'), widgets: [
        makeW('w_ai', 'AI_QUOTA_PROMPT', 2, 'violet'),
        makeW('w_dev', 'DEV_DOCKER_GIT', 2, 'emerald'),
        makeW('w_notif', 'PC_NOTIFICATIONS', 4, 'amber'),
      ]}
    ];
  }
  render();
  saveAndSync();
}
async function saveAndSync() {
  cfg.language = currentLang;
  await fetch('/api/config', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(cfg)
  });
  showToast(t('toastSaved'));
}
async function runAdbReverse() {
  const r = await fetch('/api/adb-reverse', { method: 'POST' });
  const d = await r.json();
  showToast(d.message, !r.ok);
}
async function sendTestNotif() {
  await fetch('/api/test-notification', { method: 'POST' });
  showToast(t('toastTestNotif'));
}
async function sendTestAi() {
  await fetch('/api/ai-prompt', { method: 'POST' });
  showToast(t('toastTestAi'));
}
function openStopModal() {
  document.getElementById('confirm-modal').classList.add('open');
}
function closeStopModal() {
  document.getElementById('confirm-modal').classList.remove('open');
}
async function confirmStopAgent() {
  closeStopModal();
  try {
    const r = await fetch('/api/shutdown', {
      method: 'POST',
      headers: { 'X-DeskDeck-Token': cfg.token || '' }
    });
    if (r.ok) {
      showToast(t('toastStopped'));
    } else {
      showToast(t('toastStopError'), true);
    }
  } catch (_) {
    showToast(t('toastStopError'), true);
  }
}

applyStaticTranslations();
render();
</script>
</body>
</html>"""


def render_dashboard_html(lan_ip: str, port: int, cfg: dict, collector: TelemetryCollector) -> str:
    lang = normalize_language(cfg.get("language"))
    cfg["language"] = lang
    pair_uri = (
        f"deskdeck://pair?host={urllib.parse.quote(lan_ip)}"
        f"&port={port}&token={urllib.parse.quote(cfg['token'])}"
        f"&name={urllib.parse.quote(cfg['pcName'])}"
    )
    qr_svg = PureQrSvg.encode_svg(pair_uri, 220)
    cfg_json = json.dumps(cfg, ensure_ascii=False)
    rss_mb = str(collector.get_agent_rss_mb())
    safe_pc = html_lib.escape(str(cfg.get("pcName", "DeskDeck-PC")))

    html_out = DASHBOARD_HTML_TEMPLATE
    replacements = {
        "__LANG__": html_lib.escape(lang),
        "__PC_NAME__": safe_pc,
        "__RSS_MB__": rss_mb,
        "__QR_SVG__": qr_svg,
        "__LAN_IP__": html_lib.escape(lan_ip),
        "__PORT__": str(port),
        "__TOKEN__": html_lib.escape(str(cfg["token"])),
        "__CFG_JSON__": cfg_json,
    }
    for k, v in replacements.items():
        html_out = html_out.replace(k, v)
    return html_out


# ==============================================================================
# 6. RFC-6455 Pure-Python Asyncio WebSocket + HTTP Server + Background Lifecycle
# ==============================================================================
WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"


async def ws_send_text(writer: asyncio.StreamWriter, text: str):
    data = text.encode("utf-8")
    length = len(data)
    header = bytearray([0x81])
    if length < 126:
        header.append(length)
    elif length < 65536:
        header.append(126)
        header.extend(struct.pack("!H", length))
    else:
        header.append(127)
        header.extend(struct.pack("!Q", length))
    writer.write(bytes(header) + data)
    await writer.drain()


async def ws_read_frame(reader: asyncio.StreamReader):
    b1, b2 = await reader.readexactly(2)
    opcode = b1 & 0x0F
    masked = (b2 & 0x80) != 0
    length = b2 & 0x7F
    if length == 126:
        length = struct.unpack("!H", await reader.readexactly(2))[0]
    elif length == 127:
        length = struct.unpack("!Q", await reader.readexactly(8))[0]
    mask = await reader.readexactly(4) if masked else b""
    payload = bytearray(await reader.readexactly(length))
    if masked:
        for i in range(length):
            payload[i] ^= mask[i % 4]
    return opcode, bytes(payload)


def get_lan_ip() -> str:
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        if ip and not ip.startswith("127."):
            return ip
    except Exception:
        pass
    finally:
        s.close()
    try:
        host_ip = socket.gethostbyname(socket.gethostname())
        if host_ip and not host_ip.startswith("127."):
            return host_ip
    except Exception:
        pass
    return "127.0.0.1"


def write_pid_file(port: int, token: str):
    CONFIG_DIR.mkdir(parents=True, exist_ok=True)
    try:
        PID_FILE.write_text(
            json.dumps({"pid": os.getpid(), "port": port, "token": token}),
            encoding="utf-8",
        )
    except Exception:
        pass


def read_pid_file() -> dict | None:
    if not PID_FILE.exists():
        return None
    try:
        data = json.loads(PID_FILE.read_text(encoding="utf-8"))
        if isinstance(data, dict) and int(data.get("pid", 0)) > 0:
            return data
    except Exception:
        pass
    return None


def remove_pid_file():
    try:
        PID_FILE.unlink(missing_ok=True)
    except Exception:
        pass


def is_process_alive(pid: int) -> bool:
    if pid <= 0:
        return False
    if platform.system() == "Windows":
        try:
            handle = ctypes.windll.kernel32.OpenProcess(0x1000, False, pid)
            if not handle:
                return False
            try:
                exit_code = ctypes.c_ulong()
                if ctypes.windll.kernel32.GetExitCodeProcess(handle, ctypes.byref(exit_code)):
                    return exit_code.value == 259  # STILL_ACTIVE
            finally:
                ctypes.windll.kernel32.CloseHandle(handle)
        except Exception:
            return False
        return False
    try:
        os.kill(pid, 0)
        return True
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    except Exception:
        return False


def stop_process(pid: int) -> bool:
    if pid <= 0:
        return False
    try:
        if platform.system() == "Windows":
            handle = ctypes.windll.kernel32.OpenProcess(0x0001, False, pid)
            if handle:
                try:
                    ctypes.windll.kernel32.TerminateProcess(handle, 0)
                    return True
                finally:
                    ctypes.windll.kernel32.CloseHandle(handle)
            return False
        else:
            os.kill(pid, signal.SIGTERM)
            return True
    except Exception:
        return False


def check_agent_health(port: int) -> int | None:
    try:
        with urllib.request.urlopen(f"http://127.0.0.1:{port}/health", timeout=0.6) as resp:
            if resp.status == 200:
                payload = json.loads(resp.read().decode("utf-8"))
                if payload.get("ok"):
                    return int(payload.get("pid", 0))
    except Exception:
        pass
    return None


class DeskDeckServer:
    def __init__(self, port: int = DEFAULT_PORT, open_browser: bool = True, worker_mode: bool = False):
        self.port = port
        self.open_browser = open_browser
        self.worker_mode = worker_mode
        self.config = load_or_create_config()
        self.collector = TelemetryCollector(self.config)
        self.executor = ActionExecutor(self.config, self.collector)
        self.clients: set[asyncio.StreamWriter] = set()
        self._shutdown_event: asyncio.Event | None = None

    def build_pair_uri(self, lan_ip: str | None = None) -> str:
        ip = lan_ip or get_lan_ip()
        return (
            f"deskdeck://pair?host={urllib.parse.quote(ip)}"
            f"&port={self.port}&token={urllib.parse.quote(self.config['token'])}"
            f"&name={urllib.parse.quote(self.config['pcName'])}"
        )

    def request_shutdown(self):
        if self._shutdown_event is not None and not self._shutdown_event.is_set():
            self._shutdown_event.set()

    async def broadcast(self, payload: dict):
        if not self.clients:
            return
        text = json.dumps(payload, ensure_ascii=False)
        dead = []
        for w in list(self.clients):
            try:
                await ws_send_text(w, text)
            except Exception:
                dead.append(w)
        for w in dead:
            self.clients.discard(w)

    async def telemetry_loop(self):
        try:
            while self._shutdown_event is not None and not self._shutdown_event.is_set():
                if self.clients:
                    snap = self.collector.collect()
                    await self.broadcast(snap)
                await asyncio.sleep(1.0)
        except asyncio.CancelledError:
            pass

    async def udp_discovery_loop(self):
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        sock.setblocking(False)
        bound = False
        try:
            sock.bind(("", DISCOVERY_PORT))
            bound = True
        except Exception:
            pass

        last_beacon = 0.0
        try:
            while self._shutdown_event is not None and not self._shutdown_event.is_set():
                now = time.monotonic()
                lan_ip = get_lan_ip()
                beacon = json.dumps({
                    "service": "deskdeck",
                    "name": self.config["pcName"],
                    "host": lan_ip,
                    "port": self.port,
                    "token": self.config["token"],
                    "os": platform.system(),
                }).encode("utf-8")

                if now - last_beacon >= 2.0:
                    last_beacon = now
                    try:
                        sock.sendto(beacon, ("255.255.255.255", DISCOVERY_PORT))
                        sock.sendto(beacon, ("239.255.77.77", DISCOVERY_PORT))
                    except Exception:
                        pass

                if bound:
                    for _ in range(4):
                        try:
                            data, addr = sock.recvfrom(1024)
                            if b"DESKDECK_DISCOVER" in data:
                                sock.sendto(beacon, addr)
                        except (BlockingIOError, InterruptedError):
                            break
                        except Exception:
                            break

                await asyncio.sleep(0.25)
        except asyncio.CancelledError:
            pass
        finally:
            try:
                sock.close()
            except Exception:
                pass

    async def handle_connection(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter):
        try:
            raw_head = await reader.readuntil(b"\r\n\r\n")
            lines = raw_head.decode("utf-8", errors="replace").split("\r\n")
            req_line = lines[0].split(" ")
            method, target = req_line[0], req_line[1] if len(req_line) > 1 else "/"
            headers = {}
            for ln in lines[1:]:
                if ":" in ln:
                    k, v = ln.split(":", 1)
                    headers[k.strip().lower()] = v.strip()

            parsed = urllib.parse.urlparse(target)
            qs = urllib.parse.parse_qs(parsed.query)
            lang = normalize_language(self.config.get("language"))

            if parsed.path == "/ws" and headers.get("upgrade", "").lower() == "websocket":
                tok = qs.get("token", [""])[0]
                if tok != self.config["token"]:
                    writer.write(f"HTTP/1.1 401 {tr(lang, 'err_unauthorized')}\r\n\r\n".encode("utf-8"))
                    await writer.drain()
                    writer.close()
                    return
                ws_key = headers.get("sec-websocket-key", "")
                accept = base64.b64encode(hashlib.sha1((ws_key + WS_GUID).encode()).digest()).decode()
                resp = (
                    "HTTP/1.1 101 Switching Protocols\r\n"
                    "Upgrade: websocket\r\nConnection: Upgrade\r\n"
                    f"Sec-WebSocket-Accept: {accept}\r\n\r\n"
                ).encode()
                writer.write(resp)
                await writer.drain()

                self.clients.add(writer)
                await ws_send_text(writer, json.dumps({"type": "layout_update", "layout": self.config["layout"]}, ensure_ascii=False))
                await ws_send_text(writer, json.dumps(self.collector.collect(), ensure_ascii=False))

                while True:
                    opcode, payload = await ws_read_frame(reader)
                    if opcode == 0x8:
                        break
                    elif opcode == 0x9:
                        writer.write(b"\x8a\x00")
                        await writer.drain()
                    elif opcode == 0x1:
                        msg = json.loads(payload.decode("utf-8"))
                        if msg.get("type") == "ping":
                            await ws_send_text(writer, json.dumps({"type": "pong", "ts": msg.get("ts")}))
                        elif msg.get("type") == "update_layout" and isinstance(msg.get("layout"), dict):
                            self.config["layout"] = msg["layout"]
                            save_config(self.config)
                            await self.broadcast({"type": "layout_update", "layout": self.config["layout"]})
                            cur_lang = normalize_language(self.config.get("language"))
                            await ws_send_text(
                                writer,
                                json.dumps(
                                    {"type": "ack", "ok": True, "message": tr(cur_lang, "ack_layout_saved")},
                                    ensure_ascii=False,
                                ),
                            )
                        else:
                            ack = self.executor.execute(msg)
                            await ws_send_text(writer, json.dumps(ack, ensure_ascii=False))
                            await self.broadcast(self.collector.collect())
            else:
                content_len = int(headers.get("content-length", "0"))
                body = await reader.readexactly(content_len) if content_len > 0 else b""
                await self.handle_http(method, parsed.path, qs, headers, body, writer)
        except Exception:
            pass
        finally:
            self.clients.discard(writer)
            try:
                writer.close()
                await writer.wait_closed()
            except Exception:
                pass

    async def handle_http(self, method: str, path: str, qs: dict, headers: dict, body: bytes, writer: asyncio.StreamWriter):
        status = "200 OK"
        ctype = "application/json; charset=utf-8"
        resp_body = b"{}"
        lang = normalize_language(self.config.get("language"))

        if path == "/" and method == "GET":
            ctype = "text/html; charset=utf-8"
            resp_body = render_dashboard_html(get_lan_ip(), self.port, self.config, self.collector).encode("utf-8")
        elif path in ("/qr", "/qr.svg") and method == "GET":
            ctype = "image/svg+xml; charset=utf-8"
            resp_body = PureQrSvg.encode_svg(self.build_pair_uri(), 320).encode("utf-8")
        elif path == "/health" and method == "GET":
            resp_body = json.dumps({
                "ok": True,
                "pid": os.getpid(),
                "port": self.port,
                "host": get_lan_ip(),
                "lang": lang,
            }).encode("utf-8")
        elif path == "/api/config" and method == "POST":
            new_cfg = json.loads(body.decode("utf-8"))
            if "language" in new_cfg:
                new_cfg["language"] = normalize_language(new_cfg["language"])
            self.config.update(new_cfg)
            save_config(self.config)
            self.collector.sync_language(self.config.get("language", DEFAULT_LANGUAGE))
            await self.broadcast({"type": "layout_update", "layout": self.config["layout"]})
            await self.broadcast(self.collector.collect())
            resp_body = json.dumps({"ok": True}).encode("utf-8")
        elif path == "/api/adb-reverse" and method == "POST":
            if shutil.which("adb"):
                res = subprocess.run(
                    ["adb", "reverse", f"tcp:{self.port}", f"tcp:{self.port}"],
                    capture_output=True,
                    text=True,
                    **hidden_subprocess_kwargs(),
                )
                msg = tr(lang, "adb_success") if res.returncode == 0 else tr(lang, "adb_error", res.stderr.strip())
            else:
                msg = tr(lang, "adb_missing")
            resp_body = json.dumps({"message": msg}, ensure_ascii=False).encode("utf-8")
        elif path == "/api/test-notification" and method == "POST":
            self.collector.notifications.insert(0, {
                "id": f"notif_{int(time.time())}",
                "app": "PC Build Watcher",
                "title": tr(lang, "notif_test_title"),
                "body": tr(lang, "notif_test_body"),
                "time": time.strftime("%H:%M"),
            })
            await self.broadcast(self.collector.collect())
            resp_body = b'{"ok":true}'
        elif path == "/api/ai-prompt" and method == "POST":
            self.collector.ai_pending_prompt = {
                "id": f"ai_{int(time.time())}",
                "tool": "Terminal Assistant",
                "command": "git status && npm test",
                "reason": tr(lang, "ai_test_reason"),
            }
            await self.broadcast(self.collector.collect())
            resp_body = b'{"ok":true}'
        elif path == "/api/shutdown" and method == "POST":
            tok = headers.get("x-deskdeck-token") or qs.get("token", [""])[0]
            if tok != self.config["token"]:
                status = "401 Unauthorized"
                resp_body = json.dumps({"error": tr(lang, "err_unauthorized")}, ensure_ascii=False).encode("utf-8")
            else:
                resp_body = json.dumps({"ok": True, "message": tr(lang, "shutdown_ok")}, ensure_ascii=False).encode("utf-8")
                asyncio.get_running_loop().call_later(0.08, self.request_shutdown)
        else:
            status = "404 Not Found"
            resp_body = json.dumps({"error": tr(lang, "err_not_found")}, ensure_ascii=False).encode("utf-8")

        head = (
            f"HTTP/1.1 {status}\r\n"
            f"Content-Type: {ctype}\r\n"
            f"Content-Length: {len(resp_body)}\r\n"
            "Connection: close\r\n\r\n"
        ).encode("utf-8")
        writer.write(head + resp_body)
        await writer.drain()

    async def start(self):
        lang = normalize_language(self.config.get("language"))
        self._shutdown_event = asyncio.Event()
        loop = asyncio.get_running_loop()

        for sig in (signal.SIGINT, signal.SIGTERM):
            try:
                loop.add_signal_handler(sig, self.request_shutdown)
            except (NotImplementedError, RuntimeError):
                try:
                    signal.signal(sig, lambda *_args: loop.call_soon_threadsafe(self.request_shutdown))
                except Exception:
                    pass

        servers = []
        try:
            srv_v4 = await asyncio.start_server(self.handle_connection, "0.0.0.0", self.port)
            servers.append(srv_v4)
        except OSError as e:
            safe_log(tr(lang, "err_port_busy", self.port, e), to_console=not self.worker_mode)
            safe_log(tr(lang, "err_port_hint"), to_console=not self.worker_mode)
            return

        try:
            srv_v6 = await asyncio.start_server(self.handle_connection, "::1", self.port)
            servers.append(srv_v6)
        except OSError:
            pass

        write_pid_file(self.port, self.config["token"])

        lan = get_lan_ip()
        dashboard_url = f"http://127.0.0.1:{self.port}"
        pair_uri = self.build_pair_uri(lan)

        to_console = not self.worker_mode
        safe_log(f"  {tr(lang, 'banner_title', self.collector.get_agent_rss_mb())}", to_console=to_console)
        safe_log(f"  -> {tr(lang, 'banner_dashboard', dashboard_url, self.port)}", to_console=to_console)
        safe_log(f"  -> {tr(lang, 'banner_wifi', lan, self.port)}", to_console=to_console)
        safe_log(f"  -> {tr(lang, 'banner_token', self.config['token'])}", to_console=to_console)
        safe_log(f"  -> {tr(lang, 'banner_ws', lan, self.port, self.config['token'])}", to_console=to_console)
        if to_console:
            print(f"  {tr(lang, 'banner_qr_hint')}")
            try:
                print(PureQrSvg.render_terminal(pair_uri))
            except Exception:
                pass
            print(f"\n  {tr(lang, 'banner_stop_hint')}\n")

        if self.open_browser:
            try:
                webbrowser.open(dashboard_url)
            except Exception:
                pass

        for srv in servers:
            await srv.start_serving()

        telemetry_task = asyncio.create_task(self.telemetry_loop())
        udp_task = asyncio.create_task(self.udp_discovery_loop())

        try:
            await self._shutdown_event.wait()
        finally:
            telemetry_task.cancel()
            udp_task.cancel()
            await asyncio.gather(telemetry_task, udp_task, return_exceptions=True)

            for w in list(self.clients):
                try:
                    w.close()
                except Exception:
                    pass
            self.clients.clear()

            for srv in servers:
                srv.close()
            await asyncio.gather(*(srv.wait_closed() for srv in servers), return_exceptions=True)
            remove_pid_file()
            safe_log(tr(lang, "banner_stop"), to_console=to_console)


def handle_stop_command(port: int, cfg: dict):
    lang = normalize_language(cfg.get("language"))
    rec = read_pid_file()
    target_port = int(rec.get("port", port)) if rec else port
    target_token = str(rec.get("token", cfg.get("token", ""))) if rec else str(cfg.get("token", ""))
    target_pid = int(rec.get("pid", 0)) if rec else 0

    stopped = False
    try:
        req = urllib.request.Request(
            f"http://127.0.0.1:{target_port}/api/shutdown",
            method="POST",
            headers={"X-DeskDeck-Token": target_token},
        )
        with urllib.request.urlopen(req, timeout=1.5) as resp:
            if resp.status == 200:
                stopped = True
                for _ in range(20):
                    if target_pid > 0 and not is_process_alive(target_pid):
                        break
                    if check_agent_health(target_port) is None:
                        break
                    time.sleep(0.1)
    except Exception:
        pass

    if not stopped and target_pid > 0 and is_process_alive(target_pid):
        stopped = stop_process(target_pid)

    remove_pid_file()
    print(tr(lang, "msg_stopped", target_pid) if stopped else tr(lang, "msg_not_running"))


def spawn_background_worker(port: int, no_browser: bool, cfg: dict) -> int:
    lang = normalize_language(cfg.get("language"))
    CONFIG_DIR.mkdir(parents=True, exist_ok=True)

    worker_args = ["--worker", "--port", str(port)]
    if no_browser:
        worker_args.append("--no-browser")

    if getattr(sys, "frozen", False):
        cmd = [sys.executable] + worker_args
    else:
        py_exe = sys.executable
        if platform.system() == "Windows":
            pythonw = Path(sys.executable).with_name("pythonw.exe")
            if pythonw.exists():
                py_exe = str(pythonw)
        cmd = [py_exe, str(Path(__file__).resolve())] + worker_args

    log_fp = open(LOG_FILE, "a", encoding="utf-8")
    try:
        if platform.system() == "Windows":
            proc = subprocess.Popen(
                cmd,
                stdin=subprocess.DEVNULL,
                stdout=log_fp,
                stderr=log_fp,
                creationflags=DETACHED_PROCESS | CREATE_NEW_PROCESS_GROUP | CREATE_NO_WINDOW,
                close_fds=True,
            )
        else:
            proc = subprocess.Popen(
                cmd,
                stdin=subprocess.DEVNULL,
                stdout=log_fp,
                stderr=log_fp,
                start_new_session=True,
                close_fds=True,
            )
    finally:
        log_fp.close()

    dashboard_url = f"http://127.0.0.1:{port}"
    for _ in range(25):
        if check_agent_health(port) is not None:
            print(tr(lang, "msg_started_bg", proc.pid, dashboard_url))
            return 0
        time.sleep(0.1)
    print(tr(lang, "msg_bg_timeout", proc.pid))
    return 0


def main():
    setup_consoleless_stdio()

    parser = argparse.ArgumentParser(description="DeskDeck PC Host Agent")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT, help="HTTP & WebSocket port")
    parser.add_argument("--no-browser", action="store_true", help="Do not auto-open browser dashboard")
    parser.add_argument("--background", action="store_true", help="Start detached background process and return immediately")
    parser.add_argument("--stop", action="store_true", help="Stop running background agent cleanly")
    parser.add_argument("--worker", action="store_true", help=argparse.SUPPRESS)
    args = parser.parse_args()

    cfg = load_or_create_config()
    lang = normalize_language(cfg.get("language"))

    if args.stop:
        handle_stop_command(args.port, cfg)
        return

    existing_pid = check_agent_health(args.port)
    if existing_pid is not None:
        if existing_pid == 0:
            rec = read_pid_file()
            if rec:
                existing_pid = int(rec.get("pid", 0))
        safe_log(tr(lang, "msg_already_running", existing_pid), to_console=not args.worker)
        if not args.no_browser:
            try:
                webbrowser.open(f"http://127.0.0.1:{args.port}")
            except Exception:
                pass
        return

    if args.background and not args.worker:
        sys.exit(spawn_background_worker(args.port, args.no_browser, cfg))

    server = DeskDeckServer(port=args.port, open_browser=not args.no_browser, worker_mode=args.worker)
    try:
        asyncio.run(server.start())
    except KeyboardInterrupt:
        safe_log(tr(lang, "banner_stop"), to_console=not args.worker)


if __name__ == "__main__":
    main()
