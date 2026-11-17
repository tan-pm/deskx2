# DeskDeck PC Host Agent

Daemon siêu nhẹ (~15–25 MB RAM) chạy trên Windows, macOS và Linux bằng **Python 3 chuẩn** (không cần cài Electron hay Node.js).

## Chạy nhanh trong 1 lệnh

```bash
python3 deskdeck_agent.py
# Trên Windows nếu lệnh python3 không có sẵn:
python deskdeck_agent.py
```

Sau khi chạy:
1. Agent sẽ **tự động mở trình duyệt** tại **`http://127.0.0.1:9876`** (hoặc `http://localhost:9876`) và **in sẵn mã QR ngay trong cửa sổ Terminal** để quét từ app DeskDeck (Wi-Fi 1 chạm).
2. Ngoài ra, ứng dụng Android cùng mạng Wi-Fi cũng tự động phát hiện laptop qua tín hiệu LAN để chạm 1 lần kết nối ngay.
3. Nếu muốn dùng cáp USB, cắm điện thoại bật USB Debugging và nhấn nút **"Kích hoạt USB (ADB Reverse)"** trên Web Dashboard (hoặc chạy `adb reverse tcp:9876 tcp:9876`).

## Lưu ý về Firewall

- **Dùng cáp USB (`adb reverse`)**: **KHÔNG cần tắt Firewall** vì kết nối đi qua đường hầm USB (`127.0.0.1:9876`).
- **Dùng Wi-Fi (WebSocket / HTTP trên cổng `9876` và UDP `9877`)**: Không cần tắt toàn bộ Firewall, chỉ cần nhấn **Allow access** khi Windows Defender Firewall hỏi quyền cho `Python` (hoặc mở inbound `TCP 9876` và `UDP 9877` cho mạng Private/LAN). Dù dùng `ws://` hay `http://` thường qua Wi-Fi thì máy tính vẫn nhận kết nối vào cổng `9876` nên quy tắc Firewall là giống nhau.

