# DeskDeck — Stream Deck & Màn Hình Phụ PC Không Dây / USB

**DeskDeck** biến điện thoại hoặc máy tính bảng Android (xoay ngang mặc định) thành một bảng điều khiển **Stream Deck & Secondary Monitor** kết nối hai chiều với máy tính Windows / macOS / Linux qua **Wi-Fi 1 chạm (QR / LAN Discovery)** hoặc **USB ADB**.

---

## Tính năng chính

- **Kết nối 1 chạm linh hoạt**
  - **Quét mã QR Wi-Fi** (CameraX + ML Kit) trực tiếp từ Terminal hoặc Web Dashboard trên PC.
  - **Tự động tìm PC trong mạng LAN** qua UDP Discovery (`UDP 9877`).
  - **Kết nối cáp USB độ trễ cực thấp** qua `adb reverse tcp:9876 tcp:9876`.
  - **Lưu danh sách máy tính** để nối lại ngay ở lần mở sau.
- **Giao diện Stream Deck ngang tối ưu không gian**
  - Thanh điều hướng trên cùng siêu gọn (hiển thị trạng thái kết nối, tab chuyển màn hình và nút cài đặt nhanh).
  - **Vuốt ngang mượt mà** giữa nhiều màn hình (*Pomodoro & Focus*, *Developer Cockpit*, *Media & Hệ thống*, hoặc màn hình tự tạo).
  - Haptic feedback khi bấm phím chức năng, macro, điều khiển media và Pomodoro.
- **Bộ Widget phong phú & Tùy chỉnh 2 chiều**
  - **Pomodoro Timer** (`25m Tập trung / 5m Nghỉ ngắn / 15m Nghỉ dài`) tích hợp sẵn thanh tiến trình và chu kỳ.
  - **Giám sát phần cứng Real-time**: CPU load/nhiệt độ, RAM, GPU, băng thông mạng & dung lượng ổ đĩa.
  - **Điều khiển PC tức thì**: Âm lượng hệ thống, Mute Loa/Mic, Play/Pause/Next/Prev Media, mở nhanh ứng dụng (*VS Code, Terminal, Browser, Explorer...*), chạy Macro & phím tắt.
  - **Developer Cockpit**: Trạng thái Git & Docker container, điều khiển Start/Stop container trực tiếp trên điện thoại.
  - **Tùy biến từng màn hình & từng widget** ngay trên app Android hoặc từ **PC Web Dashboard** (`http://127.0.0.1:9876`), tự động đồng bộ hai chiều qua WebSocket.
- **PC Host Agent siêu nhẹ (Native Go `.exe` & Python — Chạy nền độc lập)**
  - **`deskdeck-agent.exe` (Go Native — Khuyên dùng trên Windows):** Build với `-ldflags="-s -w -H windowsgui"` nên **nhấp đúp chạy ngầm ngay lập tức** không mở cửa sổ Terminal đen, tiêu tốn **~6–10 MB RAM**.
  - **`deskdeck_agent.py` (Python 3 chuẩn):** Hỗ trợ `--background` (tự tách tiến trình nền qua `pythonw.exe` hoặc `setsid`), `--stop` tắt sạch sẽ, hoặc đóng gói `.exe` ẩn console với PyInstaller `--noconsole`.
  - **Web Dashboard Responsive & Đa ngôn ngữ (i18n):** Mặc định **English**, chuyển nhanh **English / Tiếng Việt** trên giao diện, tự thích ứng 16:9, 16:10, 4:3, Ultrawide 21:9 & DPI Scaling 125%–150%, kèm nút **Stop Agent** để dừng tiến trình ngầm sạch sẽ.

---

## Hướng dẫn sử dụng nhanh

### 1. Chạy PC Host Agent trên máy tính (Không giữ Terminal)

#### Cách A — Windows chạy ngầm ngay bằng `.exe` (Không cần cài Python)
1. Tải file **`deskdeck-agent.exe`** ở mục **Releases** trên GitHub.
2. Nhấp đúp chạy `deskdeck-agent.exe` — tiến trình chạy nền hoàn toàn không bật cửa sổ Console đen.
3. Trình duyệt tự mở **`http://127.0.0.1:9876`** kèm **Mã QR**, trình chuyển đổi **English / Tiếng Việt**, trình chỉnh sửa widget và nút **Stop Agent** (hoặc chạy `deskdeck-agent.exe --stop` để dừng).

#### Cách B — Chạy nền từ source Go hoặc Python (Windows / macOS / Linux)
```bash
# 1) Go Native (build file .exe ẩn console trên Windows)
cd pc-agent-go
GOOS=windows GOARCH=amd64 CGO_ENABLED=0 go build -trimpath -ldflags="-s -w -H windowsgui" -o deskdeck-agent.exe .
# Hoặc trên Linux/macOS chạy tách nền ngay:
go build -o deskdeck-agent . && ./deskdeck-agent --background

# 2) Python 3 chuẩn (tự tách tiến trình nền, không giữ terminal)
cd pc-agent
python3 deskdeck_agent.py --background
# Windows chạy trực tiếp không console qua pythonw:
start "" pythonw.exe deskdeck_agent.py
# Dừng agent chạy nền sạch sẽ:
python3 deskdeck_agent.py --stop
```

Sau khi chạy:
1. Trình duyệt tự động mở **`http://127.0.0.1:9876`** (hiển thị **Mã QR ghép nối** và **Trình chỉnh sửa Dashboard**). Log được ghi vào `~/.deskdeck/agent.log`.
2. Bạn có thể chia sẻ trực tiếp file `deskdeck_agent.py` từ trong ứng dụng Android sang máy tính qua nút **Cài đặt PC Agent & Dashboard**.

### 2. Kết nối từ ứng dụng Android

- **Cách 1 — Wi-Fi QR (Nhanh nhất):** Mở app DeskDeck → **Quét mã QR (Wi-Fi)** → đưa camera vào mã QR trên PC Dashboard.
- **Cách 2 — LAN Auto-Discovery:** Đảm bảo điện thoại và PC dùng chung Wi-Fi, chọn tên máy tính hiện ra ở mục **PC đang mở trên cùng Wi-Fi**.
- **Cách 3 — Cáp USB (Không cần cùng Wi-Fi, không lo Firewall):**
  1. Bật *USB Debugging* trên điện thoại và cắm cáp vào PC.
  2. Trên PC Dashboard nhấn **Enable USB (ADB Reverse)** hoặc chạy:
     ```bash
     adb reverse tcp:9876 tcp:9876
     ```
  3. Trên app Android chọn **Cắm USB (ADB)** và nhập Token hiển thị trên PC.

### Lưu ý về Firewall

- **Khi dùng USB (`adb reverse`):** Không cần mở hay tắt Firewall vì lưu lượng đi qua tunnel nội bộ `127.0.0.1:9876`.
- **Khi dùng Wi-Fi:** Không cần tắt toàn bộ Firewall; chỉ cần chọn **Allow access** khi Windows Defender Firewall hỏi quyền cho `deskdeck-agent.exe` (hoặc Python), hoặc mở inbound **`TCP 9876`** (HTTP/WebSocket) và **`UDP 9877`** (LAN Discovery) cho mạng Private/LAN.

---

## Cấu trúc dự án

```text
.
├── app/                              # Ứng dụng Android (Kotlin + Jetpack Compose + Material 3)
│   ├── src/main/
│   │   ├── assets/pc_agent/          # Bản sao deskdeck_agent.py đóng gói sẵn để chia sẻ từ app
│   │   ├── java/com/example/
│   │   │   ├── data/                 # WebSocket/HTTP client, UDP discovery, DataStore & Repository
│   │   │   ├── model/                # Dữ liệu Telemetry, Layout, Pomodoro, Connection state
│   │   │   └── ui/
│   │   │       ├── components/       # Widget cards, DeckEditorSheet, PcAgentSetupSheet
│   │   │       ├── screens/          # ConnectHubScreen, DeskDeckScreen, QrScannerScreen
│   │   │       └── theme/            # Color, Typography (Space Grotesk, Inter, JetBrains Mono)
│   │   └── res/                      # Icon, font cục bộ, strings.xml
│   └── src/test/                     # Unit & Robolectric tests
├── pc-agent-go/                      # PC Host Agent Native bằng Go (0 dependency, windowsgui, ~6-10MB RAM)
├── pc-agent/                         # PC Host Agent Python 3 chuẩn (pythonw / --background / PyInstaller)
└── .github/workflows/
    └── release-apk.yml               # GitHub Actions tự động test, build APK + EXE & tạo Release
```

---

## Build thủ công & CI/CD Release

### Build & Test cục bộ

- **Android** (Gradle `9.3.1`, JDK 21):
  ```bash
  gradle :app:testDebugUnitTest
  gradle :app:assembleDebug
  ```
- **PC Agent Windows `.exe` ẩn Terminal** (Go `1.23+`):
  ```bash
  cd pc-agent-go
  go test ./...
  GOOS=windows GOARCH=amd64 CGO_ENABLED=0 go build -trimpath -ldflags="-s -w -H windowsgui" -o deskdeck-agent.exe .
  ```

### Tự động Build APK + EXE & Tạo GitHub Release

Workflow `.github/workflows/release-apk.yml` sẽ tự động chạy mỗi khi bạn `push` code lên nhánh `main` (hoặc kích hoạt thủ công bằng `workflow_dispatch`):

1. Chạy unit & Robolectric tests trên Android (`gradle :app:testDebugUnitTest`) và Go tests (`go test ./...`).
2. Build file `.apk` (tự động ký Release nếu có cấu hình Secrets `KEYSTORE_BASE64`, `STORE_PASSWORD`, `KEY_PASSWORD`, hoặc tự dùng `debug.keystore.base64`).
3. Cross-compile `deskdeck-agent.exe` (`-H windowsgui`) cho Windows (`amd64`).
4. Phát hành **GitHub Release** mới đính kèm đầy đủ `DeskDeck-*.apk`, `deskdeck-agent.exe` và `deskdeck_agent.py`.
