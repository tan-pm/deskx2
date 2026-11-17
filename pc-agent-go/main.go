package main

import (
	"bufio"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"os/exec"
	"os/signal"
	"strings"
	"sync"
	"syscall"
	"time"
)

const (
	defaultPort   = 9876
	discoveryPort = 9877
)

type DeskDeckServer struct {
	port        int
	openBrowser bool
	mu          sync.RWMutex
	cfg         HostConfig
	collector   *TelemetryCollector
	clientsMu   sync.Mutex
	clients     map[*WSClient]struct{}
}

func newDeskDeckServer(port int, openBrowser bool) *DeskDeckServer {
	cfg := loadOrCreateConfig()
	return &DeskDeckServer{
		port:        port,
		openBrowser: openBrowser,
		cfg:         cfg,
		collector:   newTelemetryCollector(cfg),
		clients:     make(map[*WSClient]struct{}),
	}
}

func (s *DeskDeckServer) getConfig() HostConfig {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.cfg
}

func (s *DeskDeckServer) addClient(c *WSClient) {
	s.clientsMu.Lock()
	s.clients[c] = struct{}{}
	s.clientsMu.Unlock()
}

func (s *DeskDeckServer) removeClient(c *WSClient) {
	s.clientsMu.Lock()
	delete(s.clients, c)
	s.clientsMu.Unlock()
}

func (s *DeskDeckServer) hasClients() bool {
	s.clientsMu.Lock()
	defer s.clientsMu.Unlock()
	return len(s.clients) > 0
}

func (s *DeskDeckServer) broadcastJSON(v any) {
	data, err := json.Marshal(v)
	if err != nil {
		return
	}
	s.clientsMu.Lock()
	targets := make([]*WSClient, 0, len(s.clients))
	for c := range s.clients {
		targets = append(targets, c)
	}
	s.clientsMu.Unlock()

	var dead []*WSClient
	for _, c := range targets {
		if err := c.SendText(data); err != nil {
			dead = append(dead, c)
		}
	}
	if len(dead) > 0 {
		s.clientsMu.Lock()
		for _, c := range dead {
			delete(s.clients, c)
			c.Close()
		}
		s.clientsMu.Unlock()
	}
}

func getLanIP() string {
	conn, err := net.DialTimeout("udp4", "8.8.8.8:80", 400*time.Millisecond)
	if err == nil {
		defer conn.Close()
		if addr, ok := conn.LocalAddr().(*net.UDPAddr); ok {
			ip := addr.IP.String()
			if ip != "" && !strings.HasPrefix(ip, "127.") {
				return ip
			}
		}
	}

	ifaces, err := net.Interfaces()
	if err == nil {
		for _, iface := range ifaces {
			if (iface.Flags&net.FlagUp) == 0 || (iface.Flags&net.FlagLoopback) != 0 {
				continue
			}
			addrs, err := iface.Addrs()
			if err != nil {
				continue
			}
			for _, a := range addrs {
				if ipNet, ok := a.(*net.IPNet); ok {
					v4 := ipNet.IP.To4()
					if v4 != nil && !v4.IsLoopback() {
						return v4.String()
					}
				}
			}
		}
	}
	return "127.0.0.1"
}

func writeJSON(w http.ResponseWriter, status int, payload any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(payload)
}

func (s *DeskDeckServer) handleWS(w http.ResponseWriter, r *http.Request) {
	tok := r.URL.Query().Get("token")
	cfg := s.getConfig()
	if tok != cfg.Token {
		http.Error(w, "Unauthorized", http.StatusUnauthorized)
		return
	}

	client, err := upgradeWebSocket(w, r)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	s.addClient(client)
	defer func() {
		s.removeClient(client)
		client.Close()
	}()

	if b, err := json.Marshal(map[string]any{
		"type":   "layout_update",
		"layout": cfg.Layout,
	}); err == nil {
		_ = client.SendText(b)
	}
	if b, err := json.Marshal(s.collector.Collect(cfg)); err == nil {
		_ = client.SendText(b)
	}

	for {
		opcode, payload, err := client.ReadFrame()
		if err != nil {
			return
		}
		switch opcode {
		case 0x8: // Close
			return
		case 0x9: // Ping
			_ = client.SendPong(payload)
		case 0x1: // Text
			var ev IncomingEvent
			if err := json.Unmarshal(payload, &ev); err != nil {
				continue
			}
			if ev.Type == "ping" {
				if b, err := json.Marshal(map[string]any{"type": "pong", "ts": ev.TS}); err == nil {
					_ = client.SendText(b)
				}
			} else if ev.Type == "update_layout" && ev.Layout != nil {
				s.mu.Lock()
				s.cfg.Layout = *ev.Layout
				updatedCfg := s.cfg
				_ = saveConfig(updatedCfg)
				s.mu.Unlock()

				s.broadcastJSON(map[string]any{
					"type":   "layout_update",
					"layout": updatedCfg.Layout,
				})
				if b, err := json.Marshal(AckMessage{
					Type:    "ack",
					Ok:      true,
					Message: "Đã lưu bố cục lên PC",
				}); err == nil {
					_ = client.SendText(b)
				}
			} else {
				currentCfg := s.getConfig()
				ack := executeEventAction(currentCfg, s.collector, ev)
				if b, err := json.Marshal(ack); err == nil {
					_ = client.SendText(b)
				}
				s.broadcastJSON(s.collector.Collect(currentCfg))
			}
		}
	}
}

func (s *DeskDeckServer) routes() http.Handler {
	mux := http.NewServeMux()

	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/" || r.Method != http.MethodGet {
			writeJSON(w, http.StatusNotFound, map[string]string{"error": "not found"})
			return
		}
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_, _ = io.WriteString(w, renderDashboardHTML(getLanIP(), s.port, s.getConfig()))
	})

	qrHandler := func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet {
			writeJSON(w, http.StatusMethodNotAllowed, map[string]string{"error": "method not allowed"})
			return
		}
		w.Header().Set("Content-Type", "image/svg+xml; charset=utf-8")
		_, _ = io.WriteString(w, encodeQRSVG(buildPairURI(getLanIP(), s.port, s.getConfig()), 320))
	}
	mux.HandleFunc("/qr", qrHandler)
	mux.HandleFunc("/qr.svg", qrHandler)

	mux.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, http.StatusOK, map[string]any{
			"ok":   true,
			"port": s.port,
			"host": getLanIP(),
		})
	})

	mux.HandleFunc("/api/config", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			writeJSON(w, http.StatusMethodNotAllowed, map[string]string{"error": "method not allowed"})
			return
		}
		var incoming HostConfig
		if err := json.NewDecoder(io.LimitReader(r.Body, 2*1024*1024)).Decode(&incoming); err != nil {
			writeJSON(w, http.StatusBadRequest, map[string]string{"error": err.Error()})
			return
		}
		s.mu.Lock()
		if incoming.Token != "" {
			s.cfg.Token = incoming.Token
		}
		if incoming.PCName != "" {
			s.cfg.PCName = incoming.PCName
		}
		if incoming.RepoPath != "" {
			s.cfg.RepoPath = incoming.RepoPath
		}
		if incoming.Apps != nil {
			s.cfg.Apps = incoming.Apps
		}
		if incoming.Macros != nil {
			s.cfg.Macros = incoming.Macros
		}
		if len(incoming.Layout.Pages) > 0 {
			s.cfg.Layout = incoming.Layout
		}
		updatedCfg := s.cfg
		_ = saveConfig(updatedCfg)
		s.mu.Unlock()

		s.broadcastJSON(map[string]any{
			"type":   "layout_update",
			"layout": updatedCfg.Layout,
		})
		s.broadcastJSON(s.collector.Collect(updatedCfg))
		writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
	})

	mux.HandleFunc("/api/adb-reverse", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			writeJSON(w, http.StatusMethodNotAllowed, map[string]string{"error": "method not allowed"})
			return
		}
		var msg string
		if _, err := exec.LookPath("adb"); err == nil {
			portArg := fmt.Sprintf("tcp:%d", s.port)
			c := exec.Command("adb", "reverse", portArg, portArg)
			hideSubprocessWindow(c)
			out, err := c.CombinedOutput()
			if err == nil {
				msg = "Đã bật USB ADB Reverse thành công!"
			} else {
				msg = "ADB: " + strings.TrimSpace(string(out))
			}
		} else {
			msg = "Không tìm thấy lệnh adb trong PATH"
		}
		writeJSON(w, http.StatusOK, map[string]string{"message": msg})
	})

	mux.HandleFunc("/api/test-notification", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			writeJSON(w, http.StatusMethodNotAllowed, map[string]string{"error": "method not allowed"})
			return
		}
		s.collector.mu.Lock()
		n := PcNotificationItem{
			ID:    fmt.Sprintf("notif_%d", time.Now().Unix()),
			App:   "PC Build Watcher",
			Title: "Build hoàn tất thành công",
			Body:  "Tín hiệu thử nghiệm gửi từ PC Web Dashboard.",
			Time:  time.Now().Format("15:04"),
		}
		s.collector.notifications = append([]PcNotificationItem{n}, s.collector.notifications...)
		s.collector.mu.Unlock()

		s.broadcastJSON(s.collector.Collect(s.getConfig()))
		writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
	})

	mux.HandleFunc("/api/ai-prompt", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			writeJSON(w, http.StatusMethodNotAllowed, map[string]string{"error": "method not allowed"})
			return
		}
		s.collector.mu.Lock()
		s.collector.aiPendingPrompt = &AiPendingPrompt{
			ID:      fmt.Sprintf("ai_%d", time.Now().Unix()),
			Tool:    "Terminal Assistant",
			Command: "git status && npm test",
			Reason:  "Kiểm tra thay đổi trước khi commit",
		}
		s.collector.mu.Unlock()

		s.broadcastJSON(s.collector.Collect(s.getConfig()))
		writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
	})

	mux.HandleFunc("/ws", s.handleWS)
	return mux
}

func (s *DeskDeckServer) telemetryLoop(stop <-chan struct{}) {
	ticker := time.NewTicker(1 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-stop:
			return
		case <-ticker.C:
			if s.hasClients() {
				s.broadcastJSON(s.collector.Collect(s.getConfig()))
			}
		}
	}
}

func (s *DeskDeckServer) udpDiscoveryLoop(stop <-chan struct{}) {
	pc, err := net.ListenPacket("udp4", fmt.Sprintf(":%d", discoveryPort))
	if err != nil {
		// Fallback to ephemeral UDP port for sending broadcasts even if 9877 is busy
		pc, err = net.ListenPacket("udp4", ":0")
		if err != nil {
			return
		}
	}
	defer pc.Close()

	bcastAddr := &net.UDPAddr{IP: net.IPv4bcast, Port: discoveryPort}
	mcastAddr := &net.UDPAddr{IP: net.ParseIP("239.255.77.77"), Port: discoveryPort}

	makeBeacon := func() []byte {
		cfg := s.getConfig()
		b, _ := json.Marshal(map[string]any{
			"service": "deskdeck",
			"name":    cfg.PCName,
			"host":    getLanIP(),
			"port":    s.port,
			"token":   cfg.Token,
			"os":      getOSDisplayName(),
		})
		return b
	}

	// Reader goroutine for instant DESKDECK_DISCOVER replies
	go func() {
		buf := make([]byte, 1024)
		for {
			select {
			case <-stop:
				return
			default:
			}
			_ = pc.SetReadDeadline(time.Now().Add(1 * time.Second))
			n, addr, err := pc.ReadFrom(buf)
			if err != nil {
				var netErr net.Error
				if errors.As(err, &netErr) && netErr.Timeout() {
					continue
				}
				return
			}
			if strings.Contains(string(buf[:n]), "DESKDECK_DISCOVER") {
				_, _ = pc.WriteTo(makeBeacon(), addr)
			}
		}
	}()

	ticker := time.NewTicker(2 * time.Second)
	defer ticker.Stop()

	// Send first beacon right away
	beacon := makeBeacon()
	_, _ = pc.WriteTo(beacon, bcastAddr)
	_, _ = pc.WriteTo(beacon, mcastAddr)

	for {
		select {
		case <-stop:
			return
		case <-ticker.C:
			beacon := makeBeacon()
			_, _ = pc.WriteTo(beacon, bcastAddr)
			_, _ = pc.WriteTo(beacon, mcastAddr)
		}
	}
}

func (s *DeskDeckServer) run() error {
	lnV4, err := net.Listen("tcp4", fmt.Sprintf("0.0.0.0:%d", s.port))
	if err != nil {
		return err
	}
	defer lnV4.Close()

	handler := s.routes()
	srvV4 := &http.Server{Handler: handler}
	go func() { _ = srvV4.Serve(lnV4) }()

	// Optional IPv6 loopback listener (helps localhost / adb reverse on Windows/macOS)
	if lnV6, err := net.Listen("tcp6", fmt.Sprintf("[::1]:%d", s.port)); err == nil {
		srvV6 := &http.Server{Handler: handler}
		go func() { _ = srvV6.Serve(lnV6) }()
		defer lnV6.Close()
	}

	stop := make(chan struct{})
	defer close(stop)

	go s.telemetryLoop(stop)
	go s.udpDiscoveryLoop(stop)

	cfg := s.getConfig()
	lan := getLanIP()
	dashboardURL := fmt.Sprintf("http://127.0.0.1:%d", s.port)
	pairURI := buildPairURI(lan, s.port, cfg)

	fmt.Printf("\n  DeskDeck PC Host Agent (Native Go | RAM ~%.1f MB)\n", readProcessRSSMBPlatform())
	fmt.Printf("  -> Mở PC Dashboard & Mã QR: %s  (hoặc http://localhost:%d)\n", dashboardURL, s.port)
	fmt.Printf("  -> Wi-Fi LAN IP / Port:      %s:%d\n", lan, s.port)
	fmt.Printf("  -> Token xác thực:           %s\n", cfg.Token)
	fmt.Printf("  -> Wi-Fi Endpoint:           ws://%s:%d/ws?token=%s\n\n", lan, s.port, cfg.Token)
	fmt.Println("  Quét mã QR trực tiếp bên dưới bằng app DeskDeck (hoặc dùng trình duyệt Dashboard):")
	fmt.Println(renderQRTerminal(pairURI))
	fmt.Println("")
	fmt.Println("  [Nhấn Ctrl+C hoặc đóng cửa sổ này để dừng Agent]")

	if s.openBrowser {
		_ = openBrowserPlatform(dashboardURL)
	}

	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, os.Interrupt, syscall.SIGTERM)
	<-sigCh
	fmt.Println("\nĐã dừng DeskDeck PC Agent.")
	return nil
}

func main() {
	setupConsolePlatform()

	port := flag.Int("port", defaultPort, "HTTP & WebSocket port")
	noBrowser := flag.Bool("no-browser", false, "Do not auto-open browser dashboard")
	flag.Parse()

	srv := newDeskDeckServer(*port, !*noBrowser)
	if err := srv.run(); err != nil {
		fmt.Printf("\n[LỖI] Không thể mở cổng %d: %v\n", *port, err)
		fmt.Printf("Hãy thử chạy với cổng khác, ví dụ: deskdeck-agent.exe --port 9880\n")
		fmt.Print("Nhấn Enter để thoát...")
		_, _ = bufio.NewReader(os.Stdin).ReadBytes('\n')
		os.Exit(1)
	}
}
