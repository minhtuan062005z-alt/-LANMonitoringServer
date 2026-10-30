package lanmonitoringserver.monitor.system;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import lanmonitoringserver.model.SystemInformation;
import lanmonitoringserver.util.CommandResult;
import lanmonitoringserver.util.CommandRunner;

public final class WindowsSystemInfoCollector implements SystemInfoCollector {
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(12);
    private final CommandRunner commandRunner;

    public WindowsSystemInfoCollector() {
        this(new CommandRunner());
    }

    WindowsSystemInfoCollector(CommandRunner commandRunner) {
        this.commandRunner = commandRunner;
    }

    @Override
    public SystemInformation collect() {
        SystemInfoBuilder builder = JavaSystemProbe.baseline(collectorName());
        CommandResult result = commandRunner.run(List.of("powershell.exe", "-NoLogo", "-NoProfile",
                "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", cimScript()),
                COMMAND_TIMEOUT, StandardCharsets.UTF_8);
        if (result.timedOut()) {
            builder.unavailable.put("windows.cim", "PowerShell/CIM collection timed out after 12 seconds");
        } else if (!result.successful()) {
            builder.unavailable.put("windows.cim", "PowerShell/CIM unavailable: "
                    + concise(result.standardError()));
        } else {
            try {
                WindowsCimParser.apply(result.standardOutput().trim(), builder);
            } catch (Exception exception) {
                builder.unavailable.put("windows.cim", "Invalid CIM JSON: " + exception.getMessage());
            }
        }
        addMissingReasons(builder);
        return builder.build();
    }

    private void addMissingReasons(SystemInfoBuilder builder) {
        if (builder.manufacturer == null) builder.unavailable.putIfAbsent("system.manufacturer", "CIM did not return Manufacturer");
        if (builder.model == null) builder.unavailable.putIfAbsent("system.model", "CIM did not return Model");
        if (builder.firmwareVersion == null) builder.unavailable.putIfAbsent("system.firmware", "CIM did not return BIOS/UEFI version");
        if (builder.cpuModel == null) builder.unavailable.putIfAbsent("cpu.model", "CIM did not return CPU model");
        if (builder.gpus.isEmpty()) builder.unavailable.putIfAbsent("gpu", "No GPU information available");
    }

    private String concise(String error) {
        if (error == null || error.isBlank()) return "command failed without an error message";
        String oneLine = error.replace('\r', ' ').replace('\n', ' ').trim();
        return oneLine.length() > 300 ? oneLine.substring(0, 300) + "..." : oneLine;
    }

    private String cimScript() {
        return "$ErrorActionPreference='Stop';"
                + "[Console]::OutputEncoding=[Text.UTF8Encoding]::new();"
                + "$cs=Get-CimInstance Win32_ComputerSystem;"
                + "$os=Get-CimInstance Win32_OperatingSystem;"
                + "$bios=Get-CimInstance Win32_BIOS;"
                + "$cpu=Get-CimInstance Win32_Processor|Select-Object -First 1;"
                + "$gpus=@(Get-CimInstance Win32_VideoController|ForEach-Object{[ordered]@{"
                + "Name=$_.Name;Vendor=$_.AdapterCompatibility;Driver=$_.DriverVersion;VRAM=$_.AdapterRAM}});"
                + "$storage=@(Get-CimInstance Win32_LogicalDisk -Filter \"DriveType=3\"|ForEach-Object{[ordered]@{"
                + "Device=$_.DeviceID;Label=$_.VolumeName;FileSystem=$_.FileSystem;Size=$_.Size;Free=$_.FreeSpace}});"
                + "$data=[ordered]@{system=[ordered]@{Manufacturer=$cs.Manufacturer;Model=$cs.Model;"
                + "TotalMemory=$cs.TotalPhysicalMemory;OSName=$os.Caption;OSVersion=$os.Version;"
                + "Architecture=$os.OSArchitecture;FreeMemoryKB=$os.FreePhysicalMemory};"
                + "bios=[ordered]@{Name=$bios.Name;Version=$bios.SMBIOSBIOSVersion};"
                + "cpu=[ordered]@{Name=$cpu.Name;Manufacturer=$cpu.Manufacturer;Cores=$cpu.NumberOfCores;"
                + "Logical=$cpu.NumberOfLogicalProcessors;MaxClockMHz=$cpu.MaxClockSpeed};"
                + "gpus=$gpus;storage=$storage};"
                + "$data|ConvertTo-Json -Depth 6 -Compress";
    }

    @Override
    public String collectorName() {
        return "Windows Java + PowerShell CIM collector";
    }
}
