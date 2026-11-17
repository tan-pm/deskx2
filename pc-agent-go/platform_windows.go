//go:build windows

package main

import (
	"context"
	"fmt"
	"os"
	"os/exec"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"unsafe"
)

var (
	kernel32               = syscall.NewLazyDLL("kernel32.dll")
	user32                 = syscall.NewLazyDLL("user32.dll")
	psapi                  = syscall.NewLazyDLL("psapi.dll")
	procSetConsoleOutputCP = kernel32.NewProc("SetConsoleOutputCP")
	procSetConsoleCP       = kernel32.NewProc("SetConsoleCP")
	procGlobalMemoryStatus = kernel32.NewProc("GlobalMemoryStatusEx")
	procGetSystemTimes     = kernel32.NewProc("GetSystemTimes")
	procGetDiskFreeSpaceEx = kernel32.NewProc("GetDiskFreeSpaceExW")
	procGetCurrentProcess  = kernel32.NewProc("GetCurrentProcess")
	procOpenProcess        = kernel32.NewProc("OpenProcess")
	procGetExitCodeProcess = kernel32.NewProc("GetExitCodeProcess")
	procCloseHandle        = kernel32.NewProc("CloseHandle")
	procGetProcessMemInfo  = psapi.NewProc("GetProcessMemoryInfo")
	procLockWorkStation    = user32.NewProc("LockWorkStation")
	procKeybdEvent         = user32.NewProc("keybd_event")
)

const (
	createNoWindow               = 0x08000000
	detachedProcess              = 0x00000008
	createNewProcessGroup        = 0x00000200
	processQueryLimitedInfo      = 0x1000
	winStillActive               = 259
)

type memoryStatusEx struct {
	Length               uint32
	MemoryLoad           uint32
	TotalPhys            uint64
	AvailPhys            uint64
	TotalPageFile        uint64
	AvailPageFile        uint64
	TotalVirtual         uint64
	AvailVirtual         uint64
	AvailExtendedVirtual uint64
}

type winFileTime struct {
	LowDateTime  uint32
	HighDateTime uint32
}

type processMemoryCounters struct {
	Cb                         uint32
	PageFaultCount             uint32
	PeakWorkingSetSize         uintptr
	WorkingSetSize             uintptr
	QuotaPeakPagedPoolUsage    uintptr
	QuotaPagedPoolUsage        uintptr
	QuotaPeakNonPagedPoolUsage uintptr
	QuotaNonPagedPoolUsage     uintptr
	PagefileUsage              uintptr
	PeakPagefileUsage          uintptr
}

func setupConsolePlatform() {
	_, _, _ = procSetConsoleOutputCP.Call(65001)
	_, _, _ = procSetConsoleCP.Call(65001)
}

func getOSDisplayName() string {
	return "Windows"
}

func hideSubprocessWindow(cmd *exec.Cmd) {
	cmd.SysProcAttr = &syscall.SysProcAttr{
		HideWindow:    true,
		CreationFlags: createNoWindow,
	}
}

func detachSubprocessPlatform(cmd *exec.Cmd) {
	cmd.SysProcAttr = &syscall.SysProcAttr{
		HideWindow:    true,
		CreationFlags: detachedProcess | createNewProcessGroup | createNoWindow,
	}
}

func isProcessAlivePlatform(pid int) bool {
	if pid <= 0 {
		return false
	}
	handle, _, _ := procOpenProcess.Call(processQueryLimitedInfo, 0, uintptr(pid))
	if handle == 0 {
		return false
	}
	defer procCloseHandle.Call(handle)

	var exitCode uint32
	ret, _, _ := procGetExitCodeProcess.Call(handle, uintptr(unsafe.Pointer(&exitCode)))
	if ret == 0 {
		return false
	}
	return exitCode == winStillActive
}

func stopProcessPlatform(pid int) error {
	proc, err := os.FindProcess(pid)
	if err != nil {
		return err
	}
	return proc.Kill()
}

func readProcessRSSMBPlatform() float64 {
	handle, _, _ := procGetCurrentProcess.Call()
	var pmc processMemoryCounters
	pmc.Cb = uint32(unsafe.Sizeof(pmc))
	ret, _, _ := procGetProcessMemInfo.Call(handle, uintptr(unsafe.Pointer(&pmc)), uintptr(pmc.Cb))
	if ret != 0 && pmc.WorkingSetSize > 0 {
		return round1(float64(pmc.WorkingSetSize) / (1024.0 * 1024.0))
	}
	return 6.5
}

func readSystemCpuAndRamPlatform(prevTotal, prevIdle uint64, hasPrev bool) (float64, float64, float64, uint64, uint64) {
	cpuPct := 12.0
	ramUsed := 7.0
	ramTotal := 16.0

	var mem memoryStatusEx
	mem.Length = uint32(unsafe.Sizeof(mem))
	if ret, _, _ := procGlobalMemoryStatus.Call(uintptr(unsafe.Pointer(&mem))); ret != 0 && mem.TotalPhys > 0 {
		ramTotal = round1(float64(mem.TotalPhys) / (1024.0 * 1024.0 * 1024.0))
		ramUsed = round1(float64(mem.TotalPhys-mem.AvailPhys) / (1024.0 * 1024.0 * 1024.0))
	}

	var idleFT, kernFT, userFT winFileTime
	var newTotal, newIdle uint64
	if ret, _, _ := procGetSystemTimes.Call(
		uintptr(unsafe.Pointer(&idleFT)),
		uintptr(unsafe.Pointer(&kernFT)),
		uintptr(unsafe.Pointer(&userFT)),
	); ret != 0 {
		toUint64 := func(ft winFileTime) uint64 {
			return (uint64(ft.HighDateTime) << 32) | uint64(ft.LowDateTime)
		}
		newIdle = toUint64(idleFT)
		newTotal = toUint64(kernFT) + toUint64(userFT)
		if hasPrev && newTotal > prevTotal && newIdle >= prevIdle {
			dTotal := newTotal - prevTotal
			dIdle := newIdle - prevIdle
			pct := 100.0 * (1.0 - float64(dIdle)/float64(dTotal))
			if pct < 0 {
				pct = 0
			}
			if pct > 100 {
				pct = 100
			}
			cpuPct = round1(pct)
		}
	}
	return cpuPct, ramUsed, ramTotal, newTotal, newIdle
}

func readDiskPercentPlatform(path string) float64 {
	ptr, err := syscall.UTF16PtrFromString(path)
	if err != nil {
		return 0.0
	}
	var freeAvail, totalBytes, totalFree uint64
	ret, _, _ := procGetDiskFreeSpaceEx.Call(
		uintptr(unsafe.Pointer(ptr)),
		uintptr(unsafe.Pointer(&freeAvail)),
		uintptr(unsafe.Pointer(&totalBytes)),
		uintptr(unsafe.Pointer(&totalFree)),
	)
	if ret != 0 && totalBytes > 0 {
		used := totalBytes - totalFree
		return round1(float64(used) / float64(totalBytes) * 100.0)
	}
	return 0.0
}

func readNetworkBytesPlatform() (uint64, uint64, bool) {
	cmd := exec.Command("netstat", "-e")
	hideSubprocessWindow(cmd)
	out, err := cmd.Output()
	if err != nil {
		return 0, 0, false
	}
	for _, line := range strings.Split(string(out), "\n") {
		fields := strings.Fields(line)
		if len(fields) == 3 {
			rx, err1 := strconv.ParseUint(fields[1], 10, 64)
			tx, err2 := strconv.ParseUint(fields[2], 10, 64)
			if err1 == nil && err2 == nil {
				return rx, tx, true
			}
		}
	}
	return 0, 0, false
}

func lockScreenPlatform() {
	_, _, _ = procLockWorkStation.Call()
}

func sendMediaKeyPlatform(vk byte) {
	_, _, _ = procKeybdEvent.Call(uintptr(vk), 0, 0, 0)
	_, _, _ = procKeybdEvent.Call(uintptr(vk), 0, 2, 0)
}

func applySystemMutePlatform(_ bool) {
	sendMediaKeyPlatform(0xAD)
}

var (
	volMu     sync.Mutex
	volTarget = -1
	volActive bool
)

func applySystemVolumePlatform(ctx context.Context, vol int) {
	volMu.Lock()
	volTarget = vol
	if volActive {
		volMu.Unlock()
		return
	}
	volActive = true
	volMu.Unlock()

	go func() {
		for {
			select {
			case <-ctx.Done():
				volMu.Lock()
				volActive = false
				volMu.Unlock()
				return
			default:
			}

			volMu.Lock()
			target := volTarget
			volTarget = -1
			if target < 0 {
				volActive = false
				volMu.Unlock()
				return
			}
			volMu.Unlock()

			scalar := float64(target) / 100.0
			psScript := fmt.Sprintf(`
Add-Type -TypeDefinition @"
using System.Runtime.InteropServices;
[Guid("5CDF2C82-841E-4546-9722-0CF74078229A"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IAudioEndpointVolume {
  int f0(); int f1(); int f2(); int f3();
  int SetMasterVolumeLevelScalar(float fLevel, System.Guid pguidEventContext);
}
[Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IMMDevice { int Activate(ref System.Guid id, int clsCtx, int activationParams, out IAudioEndpointVolume aev); }
[Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IMMDeviceEnumerator { int f0(); int GetDefaultAudioEndpoint(int dataFlow, int role, out IMMDevice endpoint); }
[ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")] class MMDeviceEnumerator {}
public class Vol {
  public static void Set(float level) {
    var en = new MMDeviceEnumerator() as IMMDeviceEnumerator;
    IMMDevice dev; en.GetDefaultAudioEndpoint(0, 1, out dev);
    var iid = typeof(IAudioEndpointVolume).GUID;
    IAudioEndpointVolume aev; dev.Activate(ref iid, 23, 0, out aev);
    aev.SetMasterVolumeLevelScalar(level, System.Guid.Empty);
  }
}
"@
[Vol]::Set(%.2f)
`, scalar)
			cmd := exec.CommandContext(ctx, "powershell", "-NoProfile", "-WindowStyle", "Hidden", "-Command", psScript)
			hideSubprocessWindow(cmd)
			_ = cmd.Run()
		}
	}()
}

func openBrowserPlatform(url string) error {
	cmd := exec.Command("rundll32", "url.dll,FileProtocolHandler", url)
	hideSubprocessWindow(cmd)
	return cmd.Start()
}

func launchTerminalPlatform() error {
	if _, err := exec.LookPath("wt.exe"); err == nil {
		return exec.Command("wt.exe").Start()
	}
	cmd := exec.Command("cmd.exe")
	cmd.SysProcAttr = &syscall.SysProcAttr{CreationFlags: 0x00000010}
	return cmd.Start()
}

func launchExplorerPlatform(path string) error {
	return exec.Command("explorer.exe", path).Start()
}

func launchDockerDesktopPlatform() error {
	if p, err := exec.LookPath("Docker Desktop.exe"); err == nil {
		return exec.Command(p).Start()
	}
	defaultExe := `C:\Program Files\Docker\Docker\Docker Desktop.exe`
	return exec.Command(defaultExe).Start()
}

func runShellCommandPlatform(cmdStr string) error {
	cmd := exec.Command("cmd", "/C", cmdStr)
	hideSubprocessWindow(cmd)
	return cmd.Start()
}
