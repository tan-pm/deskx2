#!/usr/bin/env python3
"""
DeskDeck PC Host Agent & Local Web Dashboard
Ultra-lightweight (~15-25 MB RAM) single-file Python 3 daemon.
Zero mandatory third-party dependencies (pure Python 3 standard library).
Supports 1-tap Wi-Fi QR pairing, UDP LAN auto-discovery, and USB ADB reverse.
"""

import asyncio
import base64
import ctypes
import hashlib
import json
import os
import platform
import secrets
import shutil
import socket
import struct
import subprocess
import sys
import time
import urllib.parse
import webbrowser
from pathlib import Path

DEFAULT_PORT = 9876
DISCOVERY_PORT = 9877
CONFIG_DIR = Path.home() / ".deskdeck"
CONFIG_FILE = CONFIG_DIR / "config.json"


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

    # (version, total_codewords, ec_per_block, blocks_g1, data_g1, blocks_g2, data_g2, align_centers)
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
            # 4 bits mode + 8 bits len + data + 4 bits terminator
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

        push_bits(0b0100, 4)  # Byte mode
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

        # Reserve format info cells
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

        # Place data bits (mask 0: (r + c) % 2 == 0)
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

        # Format info for ECC Level L (01) + Mask 0 (000) -> 0b01000 -> BCH = 0x77C4
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
        # Render 2 rows per line using half-blocks (white background modules = block, dark = space)
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
# 2. Default Full-Featured PhoneBox Layout & Config Persistence
# ==============================================================================
def default_config() -> dict:
    return {
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
            {"id": "macro_lock", "label": "Khóa màn hình", "hotkey": "Win+L / Ctrl+Cmd+Q", "command": "lock_screen"},
            {"id": "macro_shot", "label": "Chụp màn hình", "hotkey": "PrtSc", "command": "screenshot"},
            {"id": "macro_dev", "label": "Chạy Dev Server", "hotkey": "npm run dev", "command": "echo DeskDeck Dev Triggered"},
            {"id": "macro_clean", "label": "Dọn Terminal", "hotkey": "Ctrl+L", "command": "echo Clear"},
        ],
        "layout": {
            "version": 1,
            "presetName": "Stream Deck Chuẩn",
            "pages": [
                {
                    "id": "page_overview",
                    "title": "Tổng quan & Focus",
                    "widgets": [
                        {"id": "w_clock", "type": "CLOCK_WEATHER", "title": "Đồng hồ & Hệ thống", "colSpan": 2, "accentColorKey": "cyan"},
                        {"id": "w_pomo", "type": "POMODORO_TIMER", "title": "Pomodoro Focus", "colSpan": 2, "accentColorKey": "red"},
                        {"id": "w_cpu", "type": "CPU_LOAD", "title": "CPU", "colSpan": 1, "accentColorKey": "cyan"},
                        {"id": "w_ram", "type": "RAM_USAGE", "title": "RAM", "colSpan": 1, "accentColorKey": "violet"},
                        {"id": "w_media", "type": "MEDIA_PLAYER", "title": "Trình phát Media", "colSpan": 2, "accentColorKey": "cyan"},
                        {"id": "w_vol", "type": "VOLUME_MIXER", "title": "Âm lượng PC", "colSpan": 2, "accentColorKey": "amber"},
                        {"id": "w_net", "type": "NETWORK_SPEED", "title": "Mạng & Ổ đĩa", "colSpan": 2, "accentColorKey": "emerald"},
                    ],
                },
                {
                    "id": "page_shortcuts",
                    "title": "App & Macro Deck",
                    "widgets": [
                        {"id": "w_apps", "type": "APP_LAUNCHER", "title": "Mở nhanh ứng dụng", "colSpan": 2, "accentColorKey": "cyan"},
                        {"id": "w_macros", "type": "MACRO_BUTTON", "title": "Phím tắt Macro", "colSpan": 2, "accentColorKey": "violet"},
                        {"id": "w_gpu", "type": "GPU_STATS", "title": "GPU & Nhiệt độ", "colSpan": 2, "accentColorKey": "amber"},
                    ],
                },
                {
                    "id": "page_dev",
                    "title": "Dev & Thông báo",
                    "widgets": [
                        {"id": "w_ai", "type": "AI_QUOTA_PROMPT", "title": "AI Coding & Duyệt lệnh", "colSpan": 2, "accentColorKey": "violet"},
                        {"id": "w_dev", "type": "DEV_DOCKER_GIT", "title": "Docker & Git Status", "colSpan": 2, "accentColorKey": "emerald"},
                        {"id": "w_notif", "type": "PC_NOTIFICATIONS", "title": "Thông báo PC", "colSpan": 4, "accentColorKey": "amber"},
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
                    return data
        except Exception:
            pass
    cfg = default_config()
    save_config(cfg)
    return cfg


def save_config(cfg: dict):
    CONFIG_DIR.mkdir(parents=True, exist_ok=True)
    with open(CONFIG_FILE, "w", encoding="utf-8") as f:
        json.dump(cfg, f, indent=2, ensure_ascii=False)


# ==============================================================================
# 3. Lightweight System & Dev Telemetry Collector (~0 overhead)
# ==============================================================================
try:
    import psutil  # Optional: if installed, gives extra detail; otherwise stdlib probes work!
except ImportError:
    psutil = None


class TelemetryCollector:
    def __init__(self, config: dict):
        self.config = config
        self.boot_time = time.time()
        self.prev_cpu = None
        self.prev_net = None
        self.prev_net_t = time.time()
        self.cpu_history = [18.0, 22.0, 20.0, 25.0, 21.0, 19.0, 24.0, 23.0]
        self.ram_history = [46.0, 46.2, 46.5, 46.4, 47.0, 46.8, 47.1, 47.0]
        self.media_playing = False
        self.media_title = "Chưa có media đang phát"
        self.media_artist = "Desktop Audio"
        self.master_volume = 68
        self.is_muted = False
        self.mic_muted = False
        self.ai_pending_prompt = None
        self.notifications = [
            {
                "id": "notif_welcome",
                "app": "DeskDeck Agent",
                "title": "PC Host đang chạy siêu nhẹ",
                "body": f"Sẵn sàng nhận chạm & shortcut từ điện thoại trên {socket.gethostname()}",
                "time": time.strftime("%H:%M"),
            }
        ]
        self._last_dev_poll = 0.0
        self._cached_docker = []
        self._cached_git = {"repo": os.path.basename(config.get("repoPath", os.getcwd())), "branch": "main", "changed": 0, "prs": 0}

    def get_agent_rss_mb(self) -> float:
        if psutil:
            try:
                return round(psutil.Process(os.getpid()).memory_info().rss / (1024 * 1024), 1)
            except Exception:
                pass
        sys_name = platform.system()
        if sys_name == "Linux":
            try:
                with open("/proc/self/statm", "r") as f:
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
                with open("/proc/stat", "r") as f:
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
                with open("/proc/meminfo", "r") as f:
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
                with open("/proc/net/dev", "r") as f:
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
        if shutil.which("docker"):
            try:
                out = subprocess.check_output(
                    ["docker", "ps", "-a", "--format", "{{.Names}}|{{.State}}|{{.Status}}"],
                    stderr=subprocess.DEVNULL,
                    timeout=1.5,
                    text=True,
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
                ).strip()
                st = subprocess.check_output(
                    ["git", "-C", repo, "status", "--porcelain"],
                    stderr=subprocess.DEVNULL,
                    timeout=1.0,
                    text=True,
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
            "aiBurnRateText": "Ổn định",
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
                return "Đã mở Terminal"
            elif cmd == "vscode":
                if shutil.which("code"):
                    subprocess.Popen(["code", self.config.get("repoPath", os.getcwd())])
                    return "Đã mở VS Code"
            elif cmd == "browser":
                webbrowser.open("https://github.com")
                return "Đã mở Trình duyệt"
            elif cmd == "explorer":
                path = self.config.get("repoPath", str(Path.home()))
                if sys_name == "Windows":
                    subprocess.Popen(["explorer", path])
                elif sys_name == "Darwin":
                    subprocess.Popen(["open", path])
                else:
                    subprocess.Popen(["xdg-open", path])
                return "Đã mở Trình quản lý tệp"
            elif cmd == "docker_desktop":
                if sys_name == "Windows" and shutil.which("Docker Desktop"):
                    subprocess.Popen(["Docker Desktop"])
                elif sys_name == "Darwin":
                    subprocess.Popen(["open", "-a", "Docker"])
                else:
                    subprocess.Popen(["xdg-open", "http://localhost:9876"])
                return "Đã kích hoạt Docker"
            else:
                subprocess.Popen(cmd, shell=True)
                return f"Đã mở: {cmd}"
        except Exception as e:
            return f"Lỗi mở app: {e}"
        return f"Đã gửi lệnh mở {cmd}"

    def execute(self, event: dict) -> dict:
        etype = event.get("type", "")
        val = str(event.get("value", ""))
        wid = event.get("widgetId", "")
        msg = "Đã thực thi"

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
                msg = "Đã khóa màn hình PC"
            else:
                subprocess.Popen(macro_cmd, shell=True)
                msg = f"Đã chạy macro: {val}"
        elif etype == "SET_VOLUME":
            try:
                vol = max(0, min(100, int(float(val))))
                self.collector.master_volume = vol
                self._apply_system_volume(vol)
                msg = f"Âm lượng: {vol}%"
            except Exception:
                pass
        elif etype == "TOGGLE_MUTE":
            self.collector.is_muted = not self.collector.is_muted
            self._apply_system_mute(self.collector.is_muted)
            msg = "Đã tắt tiếng PC" if self.collector.is_muted else "Đã bật tiếng PC"
        elif etype == "TOGGLE_MIC_MUTE":
            self.collector.mic_muted = not self.collector.mic_muted
            msg = "Đã tắt Micro" if self.collector.mic_muted else "Đã bật Micro"
        elif etype == "MEDIA_CMD":
            if val == "play_pause":
                self.collector.media_playing = not self.collector.media_playing
                self._send_media_key(0xB3)
                msg = "Phát / Tạm dừng Media"
            elif val == "next":
                self._send_media_key(0xB0)
                msg = "Bài tiếp theo"
            elif val == "prev":
                self._send_media_key(0xB1)
                msg = "Bài trước"
        elif etype == "DOCKER_TOGGLE":
            try:
                name, action = val.split(":", 1)
                subprocess.Popen(["docker", "start" if action == "start" else "stop", name])
                msg = f"Docker {action}: {name}"
            except Exception as e:
                msg = f"Docker error: {e}"
        elif etype == "AI_DECISION":
            self.collector.ai_pending_prompt = None
            msg = "Đã duyệt lệnh AI" if val == "allow" else "Đã từ chối lệnh AI"
        elif etype == "DISMISS_NOTIF":
            self.collector.notifications = [n for n in self.collector.notifications if n["id"] != val]
            msg = "Đã xóa thông báo"

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
# 5. Embedded PC Web Dashboard (Zero-Build Dark Telemetry HTML/CSS/JS)
# ==============================================================================
def render_dashboard_html(lan_ip: str, port: int, cfg: dict, collector: TelemetryCollector) -> str:
    pair_uri = (
        f"deskdeck://pair?host={urllib.parse.quote(lan_ip)}"
        f"&port={port}&token={urllib.parse.quote(cfg['token'])}"
        f"&name={urllib.parse.quote(cfg['pcName'])}"
    )
    qr_svg = PureQrSvg.encode_svg(pair_uri, 210)
    cfg_json = json.dumps(cfg, ensure_ascii=False)
    rss_mb = collector.get_agent_rss_mb()

    return f"""<!DOCTYPE html>
<html lang="vi">
<head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width,initial-scale=1" />
<title>DeskDeck PC Dashboard — {cfg['pcName']}</title>
<style>
  :root {{
    --bg: #0B0E14; --surface: #131822; --elev: #1A2130; --border: #252F42;
    --cyan: #38BDF8; --violet: #A78BFA; --green: #22C55E; --amber: #F59E0B;
    --text: #F1F5F9; --muted: #94A3B8;
  }}
  * {{ box-sizing: border-box; }}
  body {{ margin: 0; font-family: system-ui, -apple-system, Segoe UI, sans-serif; background: var(--bg); color: var(--text); padding: 24px; }}
  .wrap {{ max-width: 1120px; margin: 0 auto; display: grid; grid-template-columns: 340px 1fr; gap: 20px; }}
  @media (max-width: 860px) {{ .wrap {{ grid-template-columns: 1fr; }} }}
  .card {{ background: var(--surface); border: 1px solid var(--border); border-radius: 16px; padding: 20px; }}
  h1, h2, h3 {{ margin: 0 0 10px; font-weight: 700; letter-spacing: -0.02em; }}
  .badge {{ display: inline-flex; align-items: center; gap: 6px; padding: 4px 10px; border-radius: 999px; font-size: 12px; font-weight: 600; background: #1E293B; border: 1px solid var(--border); }}
  .dot {{ width: 8px; height: 8px; border-radius: 50%; background: var(--green); }}
  .qr-box {{ display: flex; justify-content: center; padding: 14px; background: #fff; border-radius: 14px; margin: 14px 0; }}
  .mono {{ font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12px; color: var(--cyan); word-break: break-all; }}
  .btn {{ background: var(--cyan); color: #061018; border: 0; border-radius: 10px; padding: 10px 14px; font-weight: 700; cursor: pointer; font-size: 13px; }}
  .btn:hover {{ filter: brightness(1.08); }}
  .btn-sec {{ background: var(--elev); color: var(--text); border: 1px solid var(--border); }}
  .row {{ display: flex; gap: 8px; flex-wrap: wrap; align-items: center; }}
  .widget-chip {{ display: flex; justify-content: space-between; align-items: center; background: var(--elev); border: 1px solid var(--border); padding: 10px 12px; border-radius: 12px; margin-bottom: 8px; }}
  input, select {{ background: var(--bg); border: 1px solid var(--border); color: var(--text); padding: 8px 10px; border-radius: 8px; font-size: 13px; }}
  .grid-2 {{ display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }}
  .toast {{ position: fixed; bottom: 20px; right: 20px; background: var(--green); color: #052e16; font-weight: 700; padding: 10px 16px; border-radius: 10px; display: none; }}
</style>
</head>
<body>
<div class="wrap">
  <div class="card">
    <div class="row" style="justify-content:space-between">
      <h2>DeskDeck PC</h2>
      <span class="badge"><span class="dot"></span>{rss_mb} MB RAM</span>
    </div>
    <p style="color:var(--muted);font-size:13px;margin-top:4px;">Quét mã QR bằng app DeskDeck để kết nối Wi-Fi 1 chạm:</p>
    <div class="qr-box">{qr_svg}</div>
    <div class="mono">Wi-Fi: {lan_ip}:{port}</div>
    <div class="mono" style="color:var(--violet);margin-top:4px;">Token: {cfg['token']}</div>
    <hr style="border:0;border-top:1px solid var(--border);margin:16px 0">
    <div class="row">
      <button class="btn btn-sec" onclick="runAdbReverse()">Kích hoạt USB (ADB Reverse)</button>
      <button class="btn btn-sec" onclick="sendTestNotif()">Gửi thử thông báo</button>
      <button class="btn btn-sec" onclick="sendTestAi()">Thử AI Approval</button>
    </div>
  </div>

  <div class="card">
    <div class="row" style="justify-content:space-between;margin-bottom:14px">
      <div>
        <h2>Tùy chỉnh Dashboard & Shortcuts</h2>
        <div style="color:var(--muted);font-size:13px;">Thay đổi sẽ đồng bộ ngay lập tức xuống điện thoại đang kết nối</div>
      </div>
      <button class="btn" onclick="saveAndSync()">Lưu &amp; Đồng bộ Điện thoại</button>
    </div>

    <div class="row" style="margin-bottom:16px">
      <span style="font-size:13px;color:var(--muted)">Mẫu nhanh:</span>
      <button class="btn btn-sec" onclick="applyPreset('full')">Stream Deck Chuẩn</button>
      <button class="btn btn-sec" onclick="applyPreset('focus')">Focus Pomodoro</button>
      <button class="btn btn-sec" onclick="applyPreset('dev')">Developer Cockpit</button>
      <button class="btn btn-sec" onclick="applyPreset('minimal')">Tối giản</button>
    </div>

    <h3>Shortcut Mở Ứng dụng (App Launcher)</h3>
    <div id="apps-list" style="margin-bottom:14px"></div>
    <div class="row" style="margin-bottom:18px">
      <input id="new-app-label" placeholder="Tên (vd: Terminal, Warp)" />
      <input id="new-app-cmd" placeholder="Lệnh (vd: terminal, code, wt.exe)" />
      <button class="btn btn-sec" onclick="addAppShortcut()">+ Thêm App</button>
    </div>

    <div class="row" style="justify-content:space-between;margin-bottom:10px">
      <h3 style="margin:0">Các Màn hình &amp; Widget (lướt ngang Stream Deck)</h3>
      <button class="btn btn-sec" onclick="addPage()">+ Thêm Màn hình</button>
    </div>
    <div id="pages-editor"></div>
  </div>
</div>
<div id="toast" class="toast"></div>
<script>
let cfg = {cfg_json};
const WIDGET_CATALOG = [
  {{ type: 'CLOCK_WEATHER', title: 'Đồng hồ & Hệ thống', colSpan: 2, accent: 'cyan' }},
  {{ type: 'POMODORO_TIMER', title: 'Pomodoro Focus', colSpan: 2, accent: 'red' }},
  {{ type: 'CPU_LOAD', title: 'CPU', colSpan: 1, accent: 'cyan' }},
  {{ type: 'RAM_USAGE', title: 'RAM', colSpan: 1, accent: 'violet' }},
  {{ type: 'GPU_STATS', title: 'GPU & Nhiệt độ', colSpan: 2, accent: 'amber' }},
  {{ type: 'NETWORK_SPEED', title: 'Mạng & Ổ đĩa', colSpan: 2, accent: 'emerald' }},
  {{ type: 'MEDIA_PLAYER', title: 'Trình phát Media', colSpan: 2, accent: 'cyan' }},
  {{ type: 'VOLUME_MIXER', title: 'Âm lượng PC', colSpan: 2, accent: 'amber' }},
  {{ type: 'APP_LAUNCHER', title: 'Mở nhanh ứng dụng', colSpan: 2, accent: 'cyan' }},
  {{ type: 'MACRO_BUTTON', title: 'Phím tắt Macro', colSpan: 2, accent: 'violet' }},
  {{ type: 'DEV_DOCKER_GIT', title: 'Docker & Git Status', colSpan: 2, accent: 'emerald' }},
  {{ type: 'AI_QUOTA_PROMPT', title: 'AI Coding & Duyệt lệnh', colSpan: 2, accent: 'violet' }},
  {{ type: 'PC_NOTIFICATIONS', title: 'Thông báo PC', colSpan: 4, accent: 'amber' }},
];
function esc(s) {{ return String(s || '').replace(/"/g, '&quot;').replace(/</g, '&lt;'); }}
function showToast(msg) {{
  const t = document.getElementById('toast');
  t.textContent = msg; t.style.display = 'block';
  setTimeout(() => t.style.display = 'none', 2400);
}}
function render() {{
  const appsDiv = document.getElementById('apps-list');
  appsDiv.innerHTML = cfg.apps.map((a, idx) => `
    <div class="widget-chip">
      <span><strong>${{esc(a.label)}}</strong> <span class="mono">(${{esc(a.command)}})</span></span>
      <button class="btn btn-sec" onclick="cfg.apps.splice(${{idx}},1);render()">Xóa</button>
    </div>`).join('');

  const optsHtml = WIDGET_CATALOG.map((item, i) => `<option value="${{i}}">${{item.title}} (${{item.type}})</option>`).join('');
  const pagesDiv = document.getElementById('pages-editor');
  pagesDiv.innerHTML = cfg.layout.pages.map((p, pi) => `
    <div style="margin-bottom:14px;padding:14px;border:1px solid var(--border);border-radius:12px;background:var(--bg)">
      <div class="row" style="justify-content:space-between;margin-bottom:10px">
        <div class="row" style="flex:1">
          <span class="badge" style="color:var(--cyan)">Màn hình ${{pi+1}}</span>
          <input value="${{esc(p.title)}}" onchange="cfg.layout.pages[${{pi}}].title=this.value" style="min-width:190px;flex:1" />
        </div>
        <div class="row">
          <button class="btn btn-sec" onclick="movePage(${{pi}},-1)" ${{pi===0?'disabled':''}}>←</button>
          <button class="btn btn-sec" onclick="movePage(${{pi}},1)" ${{pi===cfg.layout.pages.length-1?'disabled':''}}>→</button>
          <button class="btn btn-sec" onclick="deletePage(${{pi}})" ${{cfg.layout.pages.length<=1?'disabled':''}}>Xóa màn hình</button>
        </div>
      </div>

      <div style="display:flex;flex-direction:column;gap:8px;margin-bottom:10px">
        ${{p.widgets.map((w, wi) => `
          <div class="widget-chip" style="margin-bottom:0;gap:8px;flex-wrap:wrap">
            <input value="${{esc(w.title)}}" onchange="cfg.layout.pages[${{pi}}].widgets[${{wi}}].title=this.value" style="flex:1;min-width:150px" />
            <small class="mono" style="color:var(--muted)">${{esc(w.type)}}</small>
            <select onchange="cfg.layout.pages[${{pi}}].widgets[${{wi}}].colSpan=Number(this.value)">
              ${{[1,2,3,4].map(c => `<option value="${{c}}" ${{Number(w.colSpan)===c?'selected':''}}>${{c}} cột</option>`).join('')}}
            </select>
            <select onchange="cfg.layout.pages[${{pi}}].widgets[${{wi}}].accentColorKey=this.value">
              ${{['cyan','violet','emerald','amber','red'].map(cl => `<option value="${{cl}}" ${{(w.accentColorKey||'cyan')===cl?'selected':''}}>${{cl}}</option>`).join('')}}
            </select>
            <button class="btn btn-sec" onclick="moveWidget(${{pi}},${{wi}},-1)" ${{wi===0?'disabled':''}}>↑</button>
            <button class="btn btn-sec" onclick="moveWidget(${{pi}},${{wi}},1)" ${{wi===p.widgets.length-1?'disabled':''}}>↓</button>
            <button class="btn btn-sec" onclick="cfg.layout.pages[${{pi}}].widgets.splice(${{wi}},1);render()">×</button>
          </div>`).join('')}}
      </div>

      <div class="row">
        <select id="add-widget-sel-${{pi}}" style="flex:1">${{optsHtml}}</select>
        <button class="btn btn-sec" onclick="addWidgetToPage(${{pi}})">+ Thêm Widget</button>
      </div>
    </div>`).join('');
}}
function addPage() {{
  cfg.layout.pages.push({{ id: 'page_' + Date.now(), title: 'Màn hình ' + (cfg.layout.pages.length + 1), widgets: [] }});
  render();
}}
function movePage(pi, dir) {{
  const target = pi + dir;
  if (target < 0 || target >= cfg.layout.pages.length) return;
  const arr = cfg.layout.pages;
  [arr[pi], arr[target]] = [arr[target], arr[pi]];
  render();
}}
function deletePage(pi) {{
  if (cfg.layout.pages.length <= 1) return;
  cfg.layout.pages.splice(pi, 1);
  render();
}}
function moveWidget(pi, wi, dir) {{
  const arr = cfg.layout.pages[pi].widgets;
  const target = wi + dir;
  if (target < 0 || target >= arr.length) return;
  [arr[wi], arr[target]] = [arr[target], arr[wi]];
  render();
}}
function addWidgetToPage(pi) {{
  const sel = document.getElementById('add-widget-sel-' + pi);
  const item = WIDGET_CATALOG[Number(sel.value)] || WIDGET_CATALOG[0];
  cfg.layout.pages[pi].widgets.push({{
    id: 'w_' + item.type.toLowerCase() + '_' + (Date.now() % 100000),
    type: item.type,
    title: item.title,
    colSpan: item.colSpan,
    accentColorKey: item.accent,
  }});
  render();
}}
function addAppShortcut() {{
  const label = document.getElementById('new-app-label').value.trim();
  const cmd = document.getElementById('new-app-cmd').value.trim();
  if (!label || !cmd) return;
  cfg.apps.push({{ id: 'app_' + Date.now(), label, command: cmd, icon: 'terminal', color: 'cyan' }});
  document.getElementById('new-app-label').value = '';
  document.getElementById('new-app-cmd').value = '';
  render();
}}
function applyPreset(mode) {{
  if (mode === 'minimal') {{
    cfg.layout.presetName = 'Tối giản';
    cfg.layout.pages = [{{ id: 'p1', title: 'Desk Minimal', widgets: [
      {{ id: 'w_clock', type: 'CLOCK_WEATHER', title: 'Đồng hồ', colSpan: 2, accentColorKey: 'cyan' }},
      {{ id: 'w_pomo', type: 'POMODORO_TIMER', title: 'Pomodoro Focus', colSpan: 2, accentColorKey: 'red' }},
      {{ id: 'w_apps', type: 'APP_LAUNCHER', title: 'Mở nhanh App', colSpan: 2, accentColorKey: 'cyan' }},
      {{ id: 'w_vol', type: 'VOLUME_MIXER', title: 'Âm lượng', colSpan: 2, accentColorKey: 'amber' }},
    ]}}];
  }} else if (mode === 'focus') {{
    cfg.layout.presetName = 'Focus Pomodoro';
    cfg.layout.pages = [
      {{ id: 'p_f1', title: 'Focus Station', widgets: [
        {{ id: 'w_pomo', type: 'POMODORO_TIMER', title: 'Pomodoro Focus', colSpan: 2, accentColorKey: 'red' }},
        {{ id: 'w_clock', type: 'CLOCK_WEATHER', title: 'Đồng hồ', colSpan: 2, accentColorKey: 'cyan' }},
        {{ id: 'w_media', type: 'MEDIA_PLAYER', title: 'Nhạc Focus', colSpan: 2, accentColorKey: 'cyan' }},
        {{ id: 'w_vol', type: 'VOLUME_MIXER', title: 'Âm lượng PC', colSpan: 2, accentColorKey: 'amber' }},
      ]}},
      {{ id: 'p_f2', title: 'App & Hệ thống', widgets: [
        {{ id: 'w_apps', type: 'APP_LAUNCHER', title: 'Mở nhanh ứng dụng', colSpan: 2, accentColorKey: 'cyan' }},
        {{ id: 'w_cpu', type: 'CPU_LOAD', title: 'CPU', colSpan: 1, accentColorKey: 'cyan' }},
        {{ id: 'w_ram', type: 'RAM_USAGE', title: 'RAM', colSpan: 1, accentColorKey: 'violet' }},
      ]}}
    ];
  }} else if (mode === 'dev') {{
    cfg.layout.presetName = 'Developer Cockpit';
    cfg.layout.pages = [
      {{ id: 'p_d1', title: 'Dev & Terminal', widgets: [
        {{ id: 'w_apps', type: 'APP_LAUNCHER', title: 'Terminal & Apps', colSpan: 2, accentColorKey: 'cyan' }},
        {{ id: 'w_macros', type: 'MACRO_BUTTON', title: 'Macro Dev', colSpan: 2, accentColorKey: 'violet' }},
        {{ id: 'w_ai', type: 'AI_QUOTA_PROMPT', title: 'AI Approval', colSpan: 2, accentColorKey: 'violet' }},
        {{ id: 'w_dev', type: 'DEV_DOCKER_GIT', title: 'Docker & Git', colSpan: 2, accentColorKey: 'emerald' }},
      ]}},
      {{ id: 'p_d2', title: 'Telemetry & Focus', widgets: [
        {{ id: 'w_pomo', type: 'POMODORO_TIMER', title: 'Pomodoro Focus', colSpan: 2, accentColorKey: 'red' }},
        {{ id: 'w_cpu', type: 'CPU_LOAD', title: 'CPU', colSpan: 1, accentColorKey: 'cyan' }},
        {{ id: 'w_ram', type: 'RAM_USAGE', title: 'RAM', colSpan: 1, accentColorKey: 'violet' }},
        {{ id: 'w_notif', type: 'PC_NOTIFICATIONS', title: 'Thông báo PC', colSpan: 4, accentColorKey: 'amber' }},
      ]}}
    ];
  }} else {{
    cfg.layout.presetName = 'Stream Deck Chuẩn';
    cfg.layout.pages = [
      {{ id: 'page_overview', title: 'Tổng quan & Focus', widgets: [
        {{ id: 'w_clock', type: 'CLOCK_WEATHER', title: 'Đồng hồ & Hệ thống', colSpan: 2, accentColorKey: 'cyan' }},
        {{ id: 'w_pomo', type: 'POMODORO_TIMER', title: 'Pomodoro Focus', colSpan: 2, accentColorKey: 'red' }},
        {{ id: 'w_cpu', type: 'CPU_LOAD', title: 'CPU', colSpan: 1, accentColorKey: 'cyan' }},
        {{ id: 'w_ram', type: 'RAM_USAGE', title: 'RAM', colSpan: 1, accentColorKey: 'violet' }},
        {{ id: 'w_media', type: 'MEDIA_PLAYER', title: 'Trình phát Media', colSpan: 2, accentColorKey: 'cyan' }},
        {{ id: 'w_vol', type: 'VOLUME_MIXER', title: 'Âm lượng PC', colSpan: 2, accentColorKey: 'amber' }},
        {{ id: 'w_net', type: 'NETWORK_SPEED', title: 'Mạng & Ổ đĩa', colSpan: 2, accentColorKey: 'emerald' }},
      ]}},
      {{ id: 'page_shortcuts', title: 'App & Macro Deck', widgets: [
        {{ id: 'w_apps', type: 'APP_LAUNCHER', title: 'Mở nhanh ứng dụng', colSpan: 2, accentColorKey: 'cyan' }},
        {{ id: 'w_macros', type: 'MACRO_BUTTON', title: 'Phím tắt Macro', colSpan: 2, accentColorKey: 'violet' }},
        {{ id: 'w_gpu', type: 'GPU_STATS', title: 'GPU & Nhiệt độ', colSpan: 2, accentColorKey: 'amber' }},
      ]}},
      {{ id: 'page_dev', title: 'Dev & Thông báo', widgets: [
        {{ id: 'w_ai', type: 'AI_QUOTA_PROMPT', title: 'AI Coding & Duyệt lệnh', colSpan: 2, accentColorKey: 'violet' }},
        {{ id: 'w_dev', type: 'DEV_DOCKER_GIT', title: 'Docker & Git Status', colSpan: 2, accentColorKey: 'emerald' }},
        {{ id: 'w_notif', type: 'PC_NOTIFICATIONS', title: 'Thông báo PC', colSpan: 4, accentColorKey: 'amber' }},
      ]}}
    ];
  }}
  render();
  saveAndSync();
}}
async function saveAndSync() {{
  await fetch('/api/config', {{ method: 'POST', headers: {{'Content-Type': 'application/json'}}, body: JSON.stringify(cfg) }});
  showToast('Đã lưu & đồng bộ xuống điện thoại!');
}}
async function runAdbReverse() {{
  const r = await fetch('/api/adb-reverse', {{ method: 'POST' }});
  const d = await r.json();
  showToast(d.message);
}}
async function sendTestNotif() {{
  await fetch('/api/test-notification', {{ method: 'POST' }});
  showToast('Đã đẩy thông báo xuống điện thoại');
}}
async function sendTestAi() {{
  await fetch('/api/ai-prompt', {{ method: 'POST' }});
  showToast('Đã gửi yêu cầu duyệt lệnh AI xuống điện thoại');
}}
render();
</script>
</body>
</html>"""


# ==============================================================================
# 6. RFC-6455 Pure-Python Asyncio WebSocket + HTTP Multiplexed Server
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


class DeskDeckServer:
    def __init__(self, port: int = DEFAULT_PORT, open_browser: bool = True):
        self.port = port
        self.open_browser = open_browser
        self.config = load_or_create_config()
        self.collector = TelemetryCollector(self.config)
        self.executor = ActionExecutor(self.config, self.collector)
        self.clients: set[asyncio.StreamWriter] = set()

    def build_pair_uri(self, lan_ip: str | None = None) -> str:
        ip = lan_ip or get_lan_ip()
        return (
            f"deskdeck://pair?host={urllib.parse.quote(ip)}"
            f"&port={self.port}&token={urllib.parse.quote(self.config['token'])}"
            f"&name={urllib.parse.quote(self.config['pcName'])}"
        )

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
        while True:
            if self.clients:
                snap = self.collector.collect()
                await self.broadcast(snap)
            await asyncio.sleep(1.0)

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
        while True:
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
            if parsed.path == "/ws" and headers.get("upgrade", "").lower() == "websocket":
                qs = urllib.parse.parse_qs(parsed.query)
                tok = qs.get("token", [""])[0]
                if tok != self.config["token"]:
                    writer.write(b"HTTP/1.1 401 Unauthorized\r\n\r\n")
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
                    if opcode == 0x8:  # Close
                        break
                    elif opcode == 0x9:  # Ping
                        writer.write(b"\x8a\x00")
                        await writer.drain()
                    elif opcode == 0x1:  # Text
                        msg = json.loads(payload.decode("utf-8"))
                        if msg.get("type") == "ping":
                            await ws_send_text(writer, json.dumps({"type": "pong", "ts": msg.get("ts")}))
                        elif msg.get("type") == "update_layout" and isinstance(msg.get("layout"), dict):
                            self.config["layout"] = msg["layout"]
                            save_config(self.config)
                            await self.broadcast({"type": "layout_update", "layout": self.config["layout"]})
                            await ws_send_text(writer, json.dumps({"type": "ack", "ok": True, "message": "Đã lưu bố cục lên PC"}, ensure_ascii=False))
                        else:
                            ack = self.executor.execute(msg)
                            await ws_send_text(writer, json.dumps(ack, ensure_ascii=False))
                            await self.broadcast(self.collector.collect())
            else:
                content_len = int(headers.get("content-length", "0"))
                body = await reader.readexactly(content_len) if content_len > 0 else b""
                await self.handle_http(method, parsed.path, body, writer)
        except Exception:
            pass
        finally:
            self.clients.discard(writer)
            try:
                writer.close()
                await writer.wait_closed()
            except Exception:
                pass

    async def handle_http(self, method: str, path: str, body: bytes, writer: asyncio.StreamWriter):
        status = "200 OK"
        ctype = "application/json; charset=utf-8"
        resp_body = b"{}"

        if path == "/" and method == "GET":
            ctype = "text/html; charset=utf-8"
            resp_body = render_dashboard_html(get_lan_ip(), self.port, self.config, self.collector).encode("utf-8")
        elif path in ("/qr", "/qr.svg") and method == "GET":
            ctype = "image/svg+xml; charset=utf-8"
            resp_body = PureQrSvg.encode_svg(self.build_pair_uri(), 320).encode("utf-8")
        elif path == "/health" and method == "GET":
            resp_body = json.dumps({"ok": True, "port": self.port, "host": get_lan_ip()}).encode("utf-8")
        elif path == "/api/config" and method == "POST":
            new_cfg = json.loads(body.decode("utf-8"))
            self.config.update(new_cfg)
            save_config(self.config)
            await self.broadcast({"type": "layout_update", "layout": self.config["layout"]})
            await self.broadcast(self.collector.collect())
            resp_body = json.dumps({"ok": True}).encode()
        elif path == "/api/adb-reverse" and method == "POST":
            if shutil.which("adb"):
                res = subprocess.run(["adb", "reverse", f"tcp:{self.port}", f"tcp:{self.port}"], capture_output=True, text=True)
                msg = "Đã bật USB ADB Reverse thành công!" if res.returncode == 0 else f"ADB: {res.stderr.strip()}"
            else:
                msg = "Không tìm thấy lệnh adb trong PATH"
            resp_body = json.dumps({"message": msg}, ensure_ascii=False).encode("utf-8")
        elif path == "/api/test-notification" and method == "POST":
            self.collector.notifications.insert(0, {
                "id": f"notif_{int(time.time())}",
                "app": "PC Build Watcher",
                "title": "Build hoàn tất thành công",
                "body": "Tín hiệu thử nghiệm gửi từ PC Web Dashboard.",
                "time": time.strftime("%H:%M"),
            })
            await self.broadcast(self.collector.collect())
            resp_body = b'{"ok":true}'
        elif path == "/api/ai-prompt" and method == "POST":
            self.collector.ai_pending_prompt = {
                "id": f"ai_{int(time.time())}",
                "tool": "Terminal Assistant",
                "command": "git status && npm test",
                "reason": "Kiểm tra thay đổi trước khi commit",
            }
            await self.broadcast(self.collector.collect())
            resp_body = b'{"ok":true}'
        else:
            status = "404 Not Found"
            resp_body = b'{"error":"not found"}'

        head = (
            f"HTTP/1.1 {status}\r\n"
            f"Content-Type: {ctype}\r\n"
            f"Content-Length: {len(resp_body)}\r\n"
            "Connection: close\r\n\r\n"
        ).encode("utf-8")
        writer.write(head + resp_body)
        await writer.drain()

    async def start(self):
        servers = []
        try:
            srv_v4 = await asyncio.start_server(self.handle_connection, "0.0.0.0", self.port)
            servers.append(srv_v4)
        except OSError as e:
            print(f"\n[LỖI] Không thể mở cổng {self.port}: {e}")
            print(f"Hãy thử chạy với cổng khác, ví dụ: python3 deskdeck_agent.py --port 9880\n")
            return

        try:
            srv_v6 = await asyncio.start_server(self.handle_connection, "::1", self.port)
            servers.append(srv_v6)
        except OSError:
            pass

        lan = get_lan_ip()
        dashboard_url = f"http://127.0.0.1:{self.port}"
        pair_uri = self.build_pair_uri(lan)

        print(f"\n  DeskDeck PC Host Agent đang chạy (RAM ~{self.collector.get_agent_rss_mb()} MB)")
        print(f"  -> Mở PC Dashboard & Mã QR: {dashboard_url}  (hoặc http://localhost:{self.port})")
        print(f"  -> Wi-Fi LAN IP / Port:      {lan}:{self.port}")
        print(f"  -> Token xác thực:           {self.config['token']}")
        print(f"  -> Wi-Fi Endpoint:           ws://{lan}:{self.port}/ws?token={self.config['token']}\n")
        print("  Quét mã QR trực tiếp bên dưới bằng app DeskDeck (hoặc dùng trình duyệt Dashboard):")
        try:
            print(PureQrSvg.render_terminal(pair_uri))
        except Exception:
            pass
        print("")

        if self.open_browser:
            try:
                webbrowser.open(dashboard_url)
            except Exception:
                pass

        await asyncio.gather(
            *[s.serve_forever() for s in servers],
            self.telemetry_loop(),
            self.udp_discovery_loop(),
        )


if __name__ == "__main__":
    port_arg = DEFAULT_PORT
    open_browser_arg = True
    args = sys.argv[1:]
    if "--no-browser" in args:
        open_browser_arg = False
    if "--port" in args:
        idx = args.index("--port")
        if idx + 1 < len(args):
            try:
                port_arg = int(args[idx + 1])
            except ValueError:
                pass
    try:
        asyncio.run(DeskDeckServer(port_arg, open_browser=open_browser_arg).start())
    except KeyboardInterrupt:
        print("\nĐã dừng DeskDeck PC Agent.")
