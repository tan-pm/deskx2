//go:build !windows

package main

import (
	"fmt"
	"os"
	"os/exec"
	"runtime"
	"strconv"
	"strings"
	"syscall"
)

func setupConsolePlatform() {}

func getOSDisplayName() string {
	switch runtime.GOOS {
	case "darwin":
		return "macOS"
	case "linux":
		return "Linux"
	default:
		return runtime.GOOS
	}
}

func hideSubprocessWindow(_ *exec.Cmd) {}

func readProcessRSSMBPlatform() float64 {
	if runtime.GOOS == "linux" {
		raw, err := os.ReadFile("/proc/self/statm")
		if err == nil {
			fields := strings.Fields(string(raw))
			if len(fields) >= 2 {
				pages, err := strconv.ParseUint(fields[1], 10, 64)
				if err == nil {
					return round1(float64(pages*uint64(os.Getpagesize())) / (1024.0 * 1024.0))
				}
			}
		}
	}
	return 8.5
}

func readSystemCpuAndRamPlatform(prevTotal, prevIdle uint64, hasPrev bool) (float64, float64, float64, uint64, uint64) {
	cpuPct := 14.0
	ramUsed := 7.0
	ramTotal := 16.0

	if runtime.GOOS == "linux" {
		if raw, err := os.ReadFile("/proc/stat"); err == nil {
			lines := strings.Split(string(raw), "\n")
			if len(lines) > 0 {
				fields := strings.Fields(lines[0])
				if len(fields) >= 8 {
					var nums [7]uint64
					for i := 0; i < 7; i++ {
						nums[i], _ = strconv.ParseUint(fields[i+1], 10, 64)
					}
					idle := nums[3] + nums[4]
					var total uint64
					for _, n := range nums {
						total += n
					}
					if hasPrev && total > prevTotal && idle >= prevIdle {
						dTotal := total - prevTotal
						dIdle := idle - prevIdle
						cpuPct = round1(100.0 * (1.0 - float64(dIdle)/float64(dTotal)))
					}
					prevTotal = total
					prevIdle = idle
				}
			}
		}

		if raw, err := os.ReadFile("/proc/meminfo"); err == nil {
			var totalKB, availKB uint64
			for _, line := range strings.Split(string(raw), "\n") {
				parts := strings.SplitN(line, ":", 2)
				if len(parts) != 2 {
					continue
				}
				valFields := strings.Fields(parts[1])
				if len(valFields) == 0 {
					continue
				}
				v, _ := strconv.ParseUint(valFields[0], 10, 64)
				switch strings.TrimSpace(parts[0]) {
				case "MemTotal":
					totalKB = v
				case "MemAvailable":
					availKB = v
				}
			}
			if totalKB > 0 {
				ramTotal = round1(float64(totalKB) / (1024.0 * 1024.0))
				ramUsed = round1(float64(totalKB-availKB) / (1024.0 * 1024.0))
			}
		}
	}

	return cpuPct, ramUsed, ramTotal, prevTotal, prevIdle
}

func readDiskPercentPlatform(path string) float64 {
	var st syscall.Statfs_t
	if err := syscall.Statfs(path, &st); err == nil && st.Blocks > 0 {
		used := st.Blocks - st.Bfree
		return round1(float64(used) / float64(st.Blocks) * 100.0)
	}
	return 0.0
}

func readNetworkBytesPlatform() (uint64, uint64, bool) {
	if runtime.GOOS != "linux" {
		return 0, 0, false
	}
	raw, err := os.ReadFile("/proc/net/dev")
	if err != nil {
		return 0, 0, false
	}
	var rxTotal, txTotal uint64
	lines := strings.Split(string(raw), "\n")
	for i := 2; i < len(lines); i++ {
		ln := strings.TrimSpace(lines[i])
		if ln == "" || strings.HasPrefix(ln, "lo:") {
			continue
		}
		colonIdx := strings.IndexByte(ln, ':')
		if colonIdx < 0 {
			continue
		}
		fields := strings.Fields(ln[colonIdx+1:])
		if len(fields) >= 9 {
			rx, _ := strconv.ParseUint(fields[0], 10, 64)
			tx, _ := strconv.ParseUint(fields[8], 10, 64)
			rxTotal += rx
			txTotal += tx
		}
	}
	return rxTotal, txTotal, true
}

func lockScreenPlatform() {
	if runtime.GOOS == "darwin" {
		_ = exec.Command("pmset", "displaysleepnow").Start()
		return
	}
	if _, err := exec.LookPath("loginctl"); err == nil {
		_ = exec.Command("loginctl", "lock-session").Start()
	}
}

func sendMediaKeyPlatform(vk byte) {
	if runtime.GOOS == "linux" {
		if _, err := exec.LookPath("playerctl"); err == nil {
			switch vk {
			case 0xB3:
				_ = exec.Command("playerctl", "play-pause").Start()
			case 0xB0:
				_ = exec.Command("playerctl", "next").Start()
			case 0xB1:
				_ = exec.Command("playerctl", "previous").Start()
			}
		}
	}
}

func applySystemMutePlatform(muted bool) {
	if runtime.GOOS == "darwin" {
		val := "false"
		if muted {
			val = "true"
		}
		_ = exec.Command("osascript", "-e", "set volume output muted "+val).Start()
		return
	}
	if _, err := exec.LookPath("pactl"); err == nil {
		val := "0"
		if muted {
			val = "1"
		}
		_ = exec.Command("pactl", "set-sink-mute", "@DEFAULT_SINK@", val).Start()
	}
}

func applySystemVolumePlatform(vol int) {
	if runtime.GOOS == "darwin" {
		_ = exec.Command("osascript", "-e", fmt.Sprintf("set volume output volume %d", vol)).Start()
		return
	}
	if _, err := exec.LookPath("pactl"); err == nil {
		_ = exec.Command("pactl", "set-sink-volume", "@DEFAULT_SINK@", fmt.Sprintf("%d%%", vol)).Start()
	}
}

func openBrowserPlatform(url string) error {
	if runtime.GOOS == "darwin" {
		return exec.Command("open", url).Start()
	}
	return exec.Command("xdg-open", url).Start()
}

func launchTerminalPlatform() error {
	if runtime.GOOS == "darwin" {
		return exec.Command("open", "-a", "Terminal").Start()
	}
	terms := []string{"x-terminal-emulator", "gnome-terminal", "konsole", "xfce4-terminal", "kitty", "alacritty", "xterm"}
	for _, t := range terms {
		if _, err := exec.LookPath(t); err == nil {
			return exec.Command(t).Start()
		}
	}
	return fmt.Errorf("terminal not found")
}

func launchExplorerPlatform(path string) error {
	if runtime.GOOS == "darwin" {
		return exec.Command("open", path).Start()
	}
	return exec.Command("xdg-open", path).Start()
}

func launchDockerDesktopPlatform() error {
	if runtime.GOOS == "darwin" {
		return exec.Command("open", "-a", "Docker").Start()
	}
	return exec.Command("xdg-open", "http://localhost:9876").Start()
}

func runShellCommandPlatform(cmdStr string) error {
	return exec.Command("sh", "-c", cmdStr).Start()
}
