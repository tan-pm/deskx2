# DeskDeck PC Host Agent (Python 3)

Daemon siêu nhẹ (~15–25 MB RAM) chạy trên Windows, macOS và Linux bằng **Python 3 chuẩn** (không cần cài Electron, Node.js hay thư viện bên thứ ba).

## 1. Chạy nền độc lập (Lite Process — Không giữ Terminal)

### Cách A — Chạy tự tách nền đa nền tảng (`--background`)
```bash
# Khởi chạy nền và trả lại terminal ngay lập tức (Windows tự ưu tiên pythonw.exe)
python3 deskdeck_agent.py --background

# Kiểm tra log nền nếu cần:
# ~/.deskdeck/agent.log

# Dừng tiến trình nền sạch sẽ (không để lại zombie process):
python3 deskdeck_agent.py --stop
```

### Cách B — Chạy không cửa sổ Console trên Windows (`pythonw.exe`)
```powershell
# Mở ngầm không hiện cửa sổ CMD/PowerShell đen:
start "" pythonw.exe deskdeck_agent.py
```

### Cách C — Chạy nền trên Linux / macOS (`nohup` hoặc `--background`)
```bash
nohup python3 deskdeck_agent.py --no-browser > ~/.deskdeck/agent.log 2>&1 &
```

### Đóng gói thành file `.exe` ẩn Console (PyInstaller)
```bash
pip install pyinstaller
pyinstaller --noconsole --windowed --onefile --name deskdeck-agent-py deskdeck_agent.py
```
File thực thi sẽ nằm tại `dist/deskdeck-agent-py.exe`, chạy ngầm trực tiếp không hiện Terminal và có thể tắt bất kỳ lúc nào qua nút **Stop Agent / Dừng Agent** trên Web Dashboard (`http://127.0.0.1:9876`) hoặc `deskdeck-agent-py.exe --stop`.

---

## 2. Giao diện Responsive & Đa ngôn ngữ (i18n)

- **Ngôn ngữ mặc định:** **English**, chuyển nhanh **English ↔ Tiếng Việt** ngay trên thanh tiêu đề của Web Dashboard (tự động lưu xuống `~/.deskdeck/config.json` & `localStorage`).
- **Tương thích mọi tỷ lệ màn hình:** `16:9`, `16:10`, `4:3`, Ultrawide `21:9`, và tự co giãn mượt khi resize cửa sổ hoặc bật DPI Scaling `125%` – `150%`.

---

## 3. Lưu ý về Firewall

- **Dùng cáp USB (`adb reverse`):** **KHÔNG cần tắt Firewall** vì kết nối đi qua đường hầm USB (`127.0.0.1:9876`).
- **Dùng Wi-Fi (`TCP 9876`, `UDP 9877`):** Không cần tắt toàn bộ Firewall; chỉ cần chọn **Allow access** khi Windows Defender Firewall hỏi quyền (hoặc mở inbound `TCP 9876` & `UDP 9877` cho mạng Private/LAN).
