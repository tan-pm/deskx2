package main

import (
	"fmt"
	"math"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

type DockerContainerItem struct {
	Name   string `json:"name"`
	State  string `json:"state"`
	Status string `json:"status"`
}

type AiPendingPrompt struct {
	ID      string `json:"id"`
	Tool    string `json:"tool"`
	Command string `json:"command"`
	Reason  string `json:"reason"`
}

type PcNotificationItem struct {
	ID    string `json:"id"`
	App   string `json:"app"`
	Title string `json:"title"`
	Body  string `json:"body"`
	Time  string `json:"time"`
}

type TelemetrySnapshot struct {
	Type                string                `json:"type"`
	Timestamp           int64                 `json:"timestamp"`
	CpuPercent          float64               `json:"cpuPercent"`
	CpuTempC            float64               `json:"cpuTempC"`
	RamUsedGb           float64               `json:"ramUsedGb"`
	RamTotalGb          float64               `json:"ramTotalGb"`
	GpuPercent          float64               `json:"gpuPercent"`
	GpuTempC            float64               `json:"gpuTempC"`
	NetDownKbps         float64               `json:"netDownKbps"`
	NetUpKbps           float64               `json:"netUpKbps"`
	DiskPercent         float64               `json:"diskPercent"`
	UptimeFormatted     string                `json:"uptimeFormatted"`
	AgentRssMb          float64               `json:"agentRssMb"`
	MediaTitle          string                `json:"mediaTitle"`
	MediaArtist         string                `json:"mediaArtist"`
	MediaPlaying        bool                  `json:"mediaPlaying"`
	MasterVolume        int                   `json:"masterVolume"`
	IsMuted             bool                  `json:"isMuted"`
	MicMuted            bool                  `json:"micMuted"`
	CpuHistory          []float64             `json:"cpuHistory"`
	RamHistory          []float64             `json:"ramHistory"`
	DockerContainers    []DockerContainerItem `json:"dockerContainers"`
	GitRepoName         string                `json:"gitRepoName"`
	GitBranch           string                `json:"gitBranch"`
	GitChangedFiles     int                   `json:"gitChangedFiles"`
	GitOpenPrs          int                   `json:"gitOpenPrs"`
	AiProvider          string                `json:"aiProvider"`
	AiQuotaRemainingPct int                   `json:"aiQuotaRemainingPct"`
	AiBurnRateText      string                `json:"aiBurnRateText"`
	AiPendingPrompt     *AiPendingPrompt      `json:"aiPendingPrompt"`
	Notifications       []PcNotificationItem  `json:"notifications"`
	Apps                []AppShortcut         `json:"apps"`
	Macros              []MacroItem           `json:"macros"`
}

type GitSummary struct {
	Repo    string
	Branch  string
	Changed int
	Prs     int
}

type TelemetryCollector struct {
	mu              sync.Mutex
	hostname        string
	bootTime        time.Time
	prevCpuTotal    uint64
	prevCpuIdle     uint64
	hasPrevCpu      bool
	prevNetRx       uint64
	prevNetTx       uint64
	prevNetTime     time.Time
	hasPrevNet      bool
	cpuHistory      []float64
	ramHistory      []float64
	mediaPlaying    bool
	mediaTitle      string
	mediaArtist     string
	hasCustomMedia  bool
	masterVolume    int
	isMuted         bool
	micMuted        bool
	aiPendingPrompt *AiPendingPrompt
	notifications   []PcNotificationItem
	lastDevPoll     time.Time
	cachedDocker    []DockerContainerItem
	cachedGit       GitSummary
	lastNetPoll     time.Time
	cachedDownKbps  float64
	cachedUpKbps    float64
}

func round1(v float64) float64 {
	return math.Round(v*10) / 10
}

func newTelemetryCollector(cfg HostConfig) *TelemetryCollector {
	lang := normalizeLanguage(cfg.Language)
	hostname, err := os.Hostname()
	if err != nil || hostname == "" {
		hostname = cfg.PCName
	}
	repoPath := cfg.RepoPath
	if repoPath == "" {
		if cwd, err := os.Getwd(); err == nil {
			repoPath = cwd
		}
	}
	return &TelemetryCollector{
		hostname:     hostname,
		bootTime:     time.Now(),
		prevNetTime:  time.Now(),
		cpuHistory:   []float64{18.0, 22.0, 20.0, 25.0, 21.0, 19.0, 24.0, 23.0},
		ramHistory:   []float64{46.0, 46.2, 46.5, 46.4, 47.0, 46.8, 47.1, 47.0},
		mediaTitle:   tr(lang, "media_idle"),
		mediaArtist:  tr(lang, "media_source"),
		masterVolume: 68,
		cachedDocker: []DockerContainerItem{},
		cachedGit: GitSummary{
			Repo:   filepath.Base(repoPath),
			Branch: "main",
		},
		notifications: []PcNotificationItem{
			{
				ID:    "notif_welcome",
				App:   "DeskDeck Agent",
				Title: tr(lang, "notif_welcome_title"),
				Body:  tr(lang, "notif_welcome_body", hostname),
				Time:  time.Now().Format("15:04"),
			},
		},
	}
}

func (tc *TelemetryCollector) SyncLanguage(lang string) {
	norm := normalizeLanguage(lang)
	tc.mu.Lock()
	defer tc.mu.Unlock()
	if !tc.hasCustomMedia {
		tc.mediaTitle = tr(norm, "media_idle")
		tc.mediaArtist = tr(norm, "media_source")
	}
	for i := range tc.notifications {
		if tc.notifications[i].ID == "notif_welcome" {
			tc.notifications[i].Title = tr(norm, "notif_welcome_title")
			tc.notifications[i].Body = tr(norm, "notif_welcome_body", tc.hostname)
		}
	}
}

func (tc *TelemetryCollector) pollDevStatusLocked(repoPath string) {
	if time.Since(tc.lastDevPoll) < 5*time.Second {
		return
	}
	tc.lastDevPoll = time.Now()

	if _, err := exec.LookPath("docker"); err == nil {
		cmd := exec.Command("docker", "ps", "-a", "--format", "{{.Names}}|{{.State}}|{{.Status}}")
		hideSubprocessWindow(cmd)
		if out, err := cmd.Output(); err == nil {
			lines := strings.Split(strings.TrimSpace(string(out)), "\n")
			containers := make([]DockerContainerItem, 0, 6)
			for _, ln := range lines {
				ln = strings.TrimSpace(ln)
				if ln == "" {
					continue
				}
				parts := strings.SplitN(ln, "|", 3)
				if len(parts) == 3 {
					containers = append(containers, DockerContainerItem{
						Name:   parts[0],
						State:  parts[1],
						Status: parts[2],
					})
				}
				if len(containers) >= 6 {
					break
				}
			}
			tc.cachedDocker = containers
		} else {
			tc.cachedDocker = []DockerContainerItem{}
		}
	}

	if repoPath == "" {
		if cwd, err := os.Getwd(); err == nil {
			repoPath = cwd
		}
	}
	if _, err := exec.LookPath("git"); err == nil {
		if info, err := os.Stat(repoPath); err == nil && info.IsDir() {
			cmdBranch := exec.Command("git", "-C", repoPath, "rev-parse", "--abbrev-ref", "HEAD")
			hideSubprocessWindow(cmdBranch)
			if outB, err := cmdBranch.Output(); err == nil {
				branch := strings.TrimSpace(string(outB))
				if branch == "" {
					branch = "main"
				}
				changed := 0
				cmdSt := exec.Command("git", "-C", repoPath, "status", "--porcelain")
				hideSubprocessWindow(cmdSt)
				if outSt, err := cmdSt.Output(); err == nil {
					for _, ln := range strings.Split(string(outSt), "\n") {
						if strings.TrimSpace(ln) != "" {
							changed++
						}
					}
				}
				absPath, err := filepath.Abs(repoPath)
				if err != nil {
					absPath = repoPath
				}
				tc.cachedGit = GitSummary{
					Repo:    filepath.Base(absPath),
					Branch:  branch,
					Changed: changed,
					Prs:     0,
				}
			}
		}
	}
}

func (tc *TelemetryCollector) readNetworkSpeedLocked() (float64, float64) {
	now := time.Now()
	if !tc.lastNetPoll.IsZero() && now.Sub(tc.lastNetPoll) < 1800*time.Millisecond {
		return tc.cachedDownKbps, tc.cachedUpKbps
	}
	tc.lastNetPoll = now

	rx, tx, ok := readNetworkBytesPlatform()
	if !ok {
		return tc.cachedDownKbps, tc.cachedUpKbps
	}
	dt := now.Sub(tc.prevNetTime).Seconds()
	if dt < 0.5 {
		dt = 0.5
	}
	if tc.hasPrevNet && rx >= tc.prevNetRx && tx >= tc.prevNetTx {
		tc.cachedDownKbps = round1(float64(rx-tc.prevNetRx) / dt / 1024.0)
		tc.cachedUpKbps = round1(float64(tx-tc.prevNetTx) / dt / 1024.0)
	}
	tc.prevNetRx = rx
	tc.prevNetTx = tx
	tc.prevNetTime = now
	tc.hasPrevNet = true
	return tc.cachedDownKbps, tc.cachedUpKbps
}

func (tc *TelemetryCollector) Collect(cfg HostConfig) TelemetrySnapshot {
	lang := normalizeLanguage(cfg.Language)
	tc.mu.Lock()
	defer tc.mu.Unlock()

	cpuPct, ramUsed, ramTotal, newTotal, newIdle := readSystemCpuAndRamPlatform(tc.prevCpuTotal, tc.prevCpuIdle, tc.hasPrevCpu)
	tc.prevCpuTotal = newTotal
	tc.prevCpuIdle = newIdle
	tc.hasPrevCpu = true

	downKbps, upKbps := tc.readNetworkSpeedLocked()

	homeDir, err := os.UserHomeDir()
	if err != nil || homeDir == "" {
		homeDir = "."
	}
	diskPct := readDiskPercentPlatform(homeDir)

	tc.cpuHistory = append(tc.cpuHistory, cpuPct)
	if len(tc.cpuHistory) > 20 {
		tc.cpuHistory = tc.cpuHistory[len(tc.cpuHistory)-20:]
	}

	ramDenom := ramTotal
	if ramDenom < 0.1 {
		ramDenom = 0.1
	}
	ramPct := round1((ramUsed / ramDenom) * 100.0)
	tc.ramHistory = append(tc.ramHistory, ramPct)
	if len(tc.ramHistory) > 20 {
		tc.ramHistory = tc.ramHistory[len(tc.ramHistory)-20:]
	}

	tc.pollDevStatusLocked(cfg.RepoPath)

	upSec := int(time.Since(tc.bootTime).Seconds())
	hrs := upSec / 3600
	mins := (upSec % 3600) / 60

	gpuPct := round1(math.Max(4.0, cpuPct*0.65))
	notifs := make([]PcNotificationItem, 0, 6)
	for i := 0; i < len(tc.notifications) && i < 6; i++ {
		notifs = append(notifs, tc.notifications[i])
	}

	cpuHistCopy := append([]float64(nil), tc.cpuHistory...)
	ramHistCopy := append([]float64(nil), tc.ramHistory...)
	dockerCopy := append([]DockerContainerItem(nil), tc.cachedDocker...)

	appsCopy := cfg.Apps
	if appsCopy == nil {
		appsCopy = []AppShortcut{}
	}
	macrosCopy := cfg.Macros
	if macrosCopy == nil {
		macrosCopy = []MacroItem{}
	}

	var promptCopy *AiPendingPrompt
	if tc.aiPendingPrompt != nil {
		p := *tc.aiPendingPrompt
		promptCopy = &p
	}

	return TelemetrySnapshot{
		Type:                "telemetry",
		Timestamp:           time.Now().UnixMilli(),
		CpuPercent:          cpuPct,
		CpuTempC:            round1(42.0 + cpuPct*0.35),
		RamUsedGb:           ramUsed,
		RamTotalGb:          ramTotal,
		GpuPercent:          gpuPct,
		GpuTempC:            round1(40.0 + cpuPct*0.3),
		NetDownKbps:         downKbps,
		NetUpKbps:           upKbps,
		DiskPercent:         diskPct,
		UptimeFormatted:     formatUptime(hrs, mins),
		AgentRssMb:          readProcessRSSMBPlatform(),
		MediaTitle:          tc.mediaTitle,
		MediaArtist:         tc.mediaArtist,
		MediaPlaying:        tc.mediaPlaying,
		MasterVolume:        tc.masterVolume,
		IsMuted:             tc.isMuted,
		MicMuted:            tc.micMuted,
		CpuHistory:          cpuHistCopy,
		RamHistory:          ramHistCopy,
		DockerContainers:    dockerCopy,
		GitRepoName:         tc.cachedGit.Repo,
		GitBranch:           tc.cachedGit.Branch,
		GitChangedFiles:     tc.cachedGit.Changed,
		GitOpenPrs:          tc.cachedGit.Prs,
		AiProvider:          "Local Dev Session",
		AiQuotaRemainingPct: 82,
		AiBurnRateText:      tr(lang, "burn_stable"),
		AiPendingPrompt:     promptCopy,
		Notifications:       notifs,
		Apps:                appsCopy,
		Macros:              macrosCopy,
	}
}

func formatUptime(hrs, mins int) string {
	return fmt.Sprintf("%dh %02dm", hrs, mins)
}
