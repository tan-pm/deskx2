package main

import (
	"context"
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
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
)

const (
	defaultPort   = 9876
	discoveryPort = 9877
)

type pidRecord struct {
	PID   int    `json:"pid"`
	Port  int    `json:"port"`
	Token string `json:"token"`
}

type DeskDeckServer struct {
	port        int
	openBrowser bool
	mu          sync.RWMutex
	cfg         HostConfig
	collector   *TelemetryCollector
	clientsMu   sync.Mutex
	clients     map[*WSClient]struct{}
	shutdownFn  context.CancelFunc
	logFile     *os.File
	workerMode  bool
}

func newDeskDeckServer(port int, openBrowser bool, workerMode bool, logFile *os.File) *DeskDeckServer {
	cfg := loadOrCreateConfig()
	return &DeskDeckServer{
		port:        port,
		openBrowser: openBrowser,
		cfg:         cfg,
		collector:   newTelemetryCollector(cfg),
		clients:     make(map[*WSClient]struct{}),
		logFile:     logFile,
		workerMode:  workerMode,
	}
}

func (s *DeskDeckServer) logLine(format string, args ...any) {
	line := fmt.Sprintf(format, args...)
	if s.logFile != nil {
		_, _ = fmt.Fprintf(s.logFile, "[%s] %s\n", time.Now().Format("15:04:05"), line)
	}
	if !s.workerMode {
		_, _ = fmt.Fprintln(os.Stdout, line)
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

func (s *DeskDeckServer) closeAllClients() {
	s.clientsMu.Lock()
	targets := make([]*WSClient, 0, len(s.clients))
	for c := range s.clients {
		targets = append(targets, c)
	}
	s.clients = make(map[*WSClient]struct{})
	s.clientsMu.Unlock()

	for _, c := range targets {
		c.Close()
	}
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

func (s *DeskDeckServer) handleWS(ctx context.Context, w http.ResponseWriter, r *http.Request) {
	tok := r.URL.Query().Get("token")
	cfg := s.getConfig()
	lang := normalizeLanguage(cfg.Language)
	if tok != cfg.Token {
		http.Error(w, tr(lang, "err_unauthorized"), http.StatusUnauthorized)
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
		select {
		case <-ctx.Done():
			return
		default:
		}
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
					Message: tr(normalizeLanguage(updatedCfg.Language), "ack_layout_saved"),
				}); err == nil {
					_ = client.SendText(b)
				}
			} else {
				currentCfg := s.getConfig()
				ack := executeEventAction(ctx, currentCfg, s.collector, ev)
				if b, err := json.Marshal(ack); err == nil {
					_ = client.SendText(b)
				}
				s.broadcastJSON(s.collector.Collect(currentCfg))
			}
		}
	}
}

func (s *DeskDeckServer) routes(ctx context.Context) http.Handler {
	mux := http.NewServeMux()

	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		cfg := s.getConfig()
		lang := normalizeLanguage(cfg.Language)
		if r.URL.Path != "/" || r.Method != http.MethodGet {
			writeJSON(w, http.StatusNotFound, map[string]string{"error": tr(lang, "err_not_found")})
			return
		}
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_, _ = io.WriteString(w, renderDashboardHTML(getLanIP(), s.port, cfg))
	})

	qrHandler := func(w http.ResponseWriter, r *http.Request) {
		cfg := s.getConfig()
		lang := normalizeLanguage(cfg.Language)
		if r.Method != http.MethodGet {
			writeJSON(w, http.StatusMethodNotAllowed, map[string]string{"error": tr(lang, "err_method_not_allowed")})
			return
		}
		w.Header().Set("Content-Type", "image/svg+xml; charset=utf-8")
		_, _ = io.WriteString(w, encodeQRSVG(buildPairURI(getLanIP(), s.port, cfg), 320))
	}
	mux.HandleFunc("/qr", qrHandler)
	mux.HandleFunc("/qr.svg", qrHandler)

	mux.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, http.StatusOK, map[string]any{
			"ok":   true,
			"pid":  os.Getpid(),
			"port": s.port,
			"host": getLanIP(),
			"lang": s.getConfig().Language,
		})
	})

	mux.HandleFunc("/api/config", func(w http.ResponseWriter, r *http.Request) {
		curLang := normalizeLanguage(s.getConfig().Language)
		if r.Method != http.MethodPost {
			writeJSON(w, http.StatusMethodNotAllowed, map[string]string{"error": tr(curLang, "err_method_not_allowed")})
			return
		}
		var incoming HostConfig
		if err := json.NewDecoder(io.LimitReader(r.Body, 2*1024*1024)).Decode(&incoming); err != nil {
			writeJSON(w, http.StatusBadRequest, map[string]string{"error": err.Error()})
			return
		}
		s.mu.Lock()
		if incoming.Language != "" {
			s.cfg.Language = normalizeLanguage(incoming.Language)
		}
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

		s.collector.SyncLanguage(updatedCfg.Language)
		s.broadcastJSON(map[string]any{
			"type":   "layout_update",
			"layout": updatedCfg.Layout,
		})
		s.broadcastJSON(s.collector.Collect(updatedCfg))
		writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
	})

	mux.HandleFunc("/api/adb-reverse", func(w http.ResponseWriter, r *http.Request) {
		lang := normalizeLanguage(s.getConfig().Language)
		if r.Method != http.MethodPost {
			writeJSON(w, http.StatusMethodNotAllowed, map[string]string{"error": tr(lang, "err_method_not_allowed")})
			return
		}
		var msg string
		if _, err := exec.LookPath("adb"); err == nil {
			portArg := fmt.Sprintf("tcp:%d", s.port)
			c := exec.Command("adb", "reverse", portArg, portArg)
			hideSubprocessWindow(c)
			out, err := c.CombinedOutput()
			if err == nil {
				msg = tr(lang, "adb_success")
			} else {
				msg = tr(lang, "adb_error", strings.TrimSpace(string(out)))
			}
		} else {
			msg = tr(lang, "adb_missing")
		}
		writeJSON(w, http.StatusOK, map[string]string{"message": msg})
	})

	mux.HandleFunc("/api/test-notification", func(w http.ResponseWriter, r *http.Request) {
		cfg := s.getConfig()
		lang := normalizeLanguage(cfg.Language)
		if r.Method != http.MethodPost {
			writeJSON(w, http.StatusMethodNotAllowed, map[string]string{"error": tr(lang, "err_method_not_allowed")})
			return
		}
		s.collector.mu.Lock()
		n := PcNotificationItem{
			ID:    fmt.Sprintf("notif_%d", time.Now().Unix()),
			App:   "PC Build Watcher",
			Title: tr(lang, "notif_test_title"),
			Body:  tr(lang, "notif_test_body"),
			Time:  time.Now().Format("15:04"),
		}
		s.collector.notifications = append([]PcNotificationItem{n}, s.collector.notifications...)
		s.collector.mu.Unlock()

		s.broadcastJSON(s.collector.Collect(s.getConfig()))
		writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
	})

	mux.HandleFunc("/api/ai-prompt", func(w http.ResponseWriter, r *http.Request) {
		cfg := s.getConfig()
		lang := normalizeLanguage(cfg.Language)
		if r.Method != http.MethodPost {
			writeJSON(w, http.StatusMethodNotAllowed, map[string]string{"error": tr(lang, "err_method_not_allowed")})
			return
		}
		s.collector.mu.Lock()
		s.collector.aiPendingPrompt = &AiPendingPrompt{
			ID:      fmt.Sprintf("ai_%d", time.Now().Unix()),
			Tool:    "Terminal Assistant",
			Command: "git status && npm test",
			Reason:  tr(lang, "ai_test_reason"),
		}
		s.collector.mu.Unlock()

		s.broadcastJSON(s.collector.Collect(s.getConfig()))
		writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
	})

	mux.HandleFunc("/api/shutdown", func(w http.ResponseWriter, r *http.Request) {
		cfg := s.getConfig()
		lang := normalizeLanguage(cfg.Language)
		if r.Method != http.MethodPost {
			writeJSON(w, http.StatusMethodNotAllowed, map[string]string{"error": tr(lang, "err_method_not_allowed")})
			return
		}
		tok := r.Header.Get("X-DeskDeck-Token")
		if tok == "" {
			tok = r.URL.Query().Get("token")
		}
		if tok != cfg.Token {
			writeJSON(w, http.StatusUnauthorized, map[string]string{"error": tr(lang, "err_unauthorized")})
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{"ok": true, "message": tr(lang, "shutdown_ok")})
		if s.shutdownFn != nil {
			go func() {
				time.Sleep(80 * time.Millisecond)
				s.shutdownFn()
			}()
		}
	})

	mux.HandleFunc("/ws", func(w http.ResponseWriter, r *http.Request) {
		s.handleWS(ctx, w, r)
	})
	return mux
}

func (s *DeskDeckServer) telemetryLoop(ctx context.Context, wg *sync.WaitGroup) {
	defer wg.Done()
	ticker := time.NewTicker(1 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			if s.hasClients() {
				s.broadcastJSON(s.collector.Collect(s.getConfig()))
			}
		}
	}
}

func (s *DeskDeckServer) udpDiscoveryLoop(ctx context.Context, wg *sync.WaitGroup) {
	defer wg.Done()
	pc, err := net.ListenPacket("udp4", fmt.Sprintf(":%d", discoveryPort))
	if err != nil {
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

	wg.Add(1)
	go func() {
		defer wg.Done()
		buf := make([]byte, 1024)
		for {
			select {
			case <-ctx.Done():
				return
			default:
			}
			_ = pc.SetReadDeadline(time.Now().Add(700 * time.Millisecond))
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

	beacon := makeBeacon()
	_, _ = pc.WriteTo(beacon, bcastAddr)
	_, _ = pc.WriteTo(beacon, mcastAddr)

	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			beacon := makeBeacon()
			_, _ = pc.WriteTo(beacon, bcastAddr)
			_, _ = pc.WriteTo(beacon, mcastAddr)
		}
	}
}

func writePidFile(port int, token string) {
	_ = os.MkdirAll(configDir(), 0o755)
	rec := pidRecord{PID: os.Getpid(), Port: port, Token: token}
	if b, err := json.Marshal(rec); err == nil {
		_ = os.WriteFile(pidFilePath(), b, 0o644)
	}
}

func readPidFile() (pidRecord, bool) {
	raw, err := os.ReadFile(pidFilePath())
	if err != nil {
		return pidRecord{}, false
	}
	var rec pidRecord
	if json.Unmarshal(raw, &rec) == nil && rec.PID > 0 {
		return rec, true
	}
	return pidRecord{}, false
}

func removePidFile() {
	_ = os.Remove(pidFilePath())
}

func isAgentHealthy(port int) (int, bool) {
	client := &http.Client{Timeout: 600 * time.Millisecond}
	resp, err := client.Get(fmt.Sprintf("http://127.0.0.1:%d/health", port))
	if err != nil {
		return 0, false
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return 0, false
	}
	var body struct {
		OK  bool `json:"ok"`
		PID int  `json:"pid"`
	}
	if json.NewDecoder(resp.Body).Decode(&body) == nil && body.OK {
		return body.PID, true
	}
	return 0, false
}

func openAgentLogFile() *os.File {
	_ = os.MkdirAll(configDir(), 0o755)
	p := logFilePath()
	if info, err := os.Stat(p); err == nil && info.Size() > 2*1024*1024 {
		_ = os.Truncate(p, 0)
	}
	f, err := os.OpenFile(p, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o644)
	if err != nil {
		return nil
	}
	return f
}

func (s *DeskDeckServer) run() error {
	lnV4, err := net.Listen("tcp4", fmt.Sprintf("0.0.0.0:%d", s.port))
	if err != nil {
		return err
	}

	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()
	s.shutdownFn = cancel

	handler := s.routes(ctx)
	srvV4 := &http.Server{Handler: handler}
	var srvV6 *http.Server

	var wg sync.WaitGroup
	wg.Add(1)
	go func() {
		defer wg.Done()
		_ = srvV4.Serve(lnV4)
	}()

	if lnV6, err := net.Listen("tcp6", fmt.Sprintf("[::1]:%d", s.port)); err == nil {
		srvV6 = &http.Server{Handler: handler}
		wg.Add(1)
		go func() {
			defer wg.Done()
			_ = srvV6.Serve(lnV6)
		}()
	}

	wg.Add(2)
	go s.telemetryLoop(ctx, &wg)
	go s.udpDiscoveryLoop(ctx, &wg)

	cfg := s.getConfig()
	lang := normalizeLanguage(cfg.Language)
	writePidFile(s.port, cfg.Token)
	defer removePidFile()

	lan := getLanIP()
	dashboardURL := fmt.Sprintf("http://127.0.0.1:%d", s.port)
	pairURI := buildPairURI(lan, s.port, cfg)

	s.logLine("  %s", tr(lang, "banner_title", readProcessRSSMBPlatform()))
	s.logLine("  -> %s", tr(lang, "banner_dashboard", dashboardURL, s.port))
	s.logLine("  -> %s", tr(lang, "banner_wifi", lan, s.port))
	s.logLine("  -> %s", tr(lang, "banner_token", cfg.Token))
	s.logLine("  -> %s", tr(lang, "banner_ws", lan, s.port, cfg.Token))
	if !s.workerMode {
		fmt.Println("  " + tr(lang, "banner_qr_hint"))
		fmt.Println(renderQRTerminal(pairURI))
		fmt.Println("")
		fmt.Println("  " + tr(lang, "banner_stop_hint"))
	}

	if s.openBrowser {
		_ = openBrowserPlatform(dashboardURL)
	}

	<-ctx.Done()

	s.closeAllClients()
	shutdownCtx, shutdownCancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer shutdownCancel()
	_ = srvV4.Shutdown(shutdownCtx)
	if srvV6 != nil {
		_ = srvV6.Shutdown(shutdownCtx)
	}
	wg.Wait()
	s.logLine("%s", tr(lang, "banner_stop"))
	return nil
}

func handleStopCommand(port int, cfg HostConfig) {
	lang := normalizeLanguage(cfg.Language)
	rec, hasPid := readPidFile()
	targetPort := port
	targetToken := cfg.Token
	targetPID := 0
	if hasPid {
		if rec.Port > 0 {
			targetPort = rec.Port
		}
		if rec.Token != "" {
			targetToken = rec.Token
		}
		targetPID = rec.PID
	}

	stopped := false
	req, err := http.NewRequest(http.MethodPost, fmt.Sprintf("http://127.0.0.1:%d/api/shutdown", targetPort), nil)
	if err == nil {
		req.Header.Set("X-DeskDeck-Token", targetToken)
		client := &http.Client{Timeout: 1500 * time.Millisecond}
		if resp, err := client.Do(req); err == nil {
			_ = resp.Body.Close()
			if resp.StatusCode == http.StatusOK {
				stopped = true
				for i := 0; i < 20; i++ {
					if targetPID > 0 && !isProcessAlivePlatform(targetPID) {
						break
					}
					if _, alive := isAgentHealthy(targetPort); !alive {
						break
					}
					time.Sleep(100 * time.Millisecond)
				}
			}
		}
	}

	if !stopped && targetPID > 0 && isProcessAlivePlatform(targetPID) {
		if err := stopProcessPlatform(targetPID); err == nil {
			stopped = true
		}
	}

	removePidFile()
	if stopped {
		fmt.Println(tr(lang, "msg_stopped", targetPID))
	} else {
		fmt.Println(tr(lang, "msg_not_running"))
	}
}

func spawnBackgroundWorker(port int, noBrowser bool, cfg HostConfig) int {
	lang := normalizeLanguage(cfg.Language)
	exe, err := os.Executable()
	if err != nil {
		return 1
	}
	args := []string{"--worker", "--port", strconv.Itoa(port)}
	if noBrowser {
		args = append(args, "--no-browser")
	}
	cmd := exec.Command(exe, args...)
	detachSubprocessPlatform(cmd)
	cmd.Stdin = nil
	cmd.Stdout = nil
	cmd.Stderr = nil
	if err := cmd.Start(); err != nil {
		fmt.Fprintf(os.Stderr, "%v\n", err)
		return 1
	}
	childPID := cmd.Process.Pid
	_ = cmd.Process.Release()

	dashboardURL := fmt.Sprintf("http://127.0.0.1:%d", port)
	for i := 0; i < 25; i++ {
		if _, ok := isAgentHealthy(port); ok {
			fmt.Println(tr(lang, "msg_started_bg", childPID, dashboardURL))
			return 0
		}
		time.Sleep(100 * time.Millisecond)
	}
	fmt.Println(tr(lang, "msg_bg_timeout", childPID))
	return 0
}

func main() {
	setupConsolePlatform()

	port := flag.Int("port", defaultPort, "HTTP & WebSocket port")
	noBrowser := flag.Bool("no-browser", false, "Do not auto-open browser dashboard")
	background := flag.Bool("background", false, "Start detached lite background process and exit immediately")
	stop := flag.Bool("stop", false, "Stop running background agent cleanly")
	worker := flag.Bool("worker", false, "Internal background worker mode")
	flag.Parse()

	cfg := loadOrCreateConfig()
	lang := normalizeLanguage(cfg.Language)

	if *stop {
		handleStopCommand(*port, cfg)
		return
	}

	if existingPID, healthy := isAgentHealthy(*port); healthy {
		if existingPID == 0 {
			if rec, ok := readPidFile(); ok {
				existingPID = rec.PID
			}
		}
		fmt.Println(tr(lang, "msg_already_running", existingPID))
		if !*noBrowser {
			_ = openBrowserPlatform(fmt.Sprintf("http://127.0.0.1:%d", *port))
		}
		return
	}

	if *background && !*worker {
		os.Exit(spawnBackgroundWorker(*port, *noBrowser, cfg))
	}

	logFile := openAgentLogFile()
	if logFile != nil {
		defer logFile.Close()
	}

	srv := newDeskDeckServer(*port, !*noBrowser, *worker, logFile)
	if err := srv.run(); err != nil {
		srv.logLine("%s", tr(lang, "err_port_busy", *port, err))
		srv.logLine("%s", tr(lang, "err_port_hint"))
		os.Exit(1)
	}
}
