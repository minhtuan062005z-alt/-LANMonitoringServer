package lanmonitoringserver.monitor.system;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import lanmonitoringserver.model.SystemInformation;
import lanmonitoringserver.util.CommandResult;
import lanmonitoringserver.util.CommandRunner;

public final class LinuxSystemInfoCollector implements SystemInfoCollector {
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(5);
    private final CommandRunner commandRunner;

    public LinuxSystemInfoCollector() {
        this(new CommandRunner());
    }

    LinuxSystemInfoCollector(CommandRunner commandRunner) {
        this.commandRunner = commandRunner;
    }

    @Override
    public SystemInformation collect() {
        SystemInfoBuilder builder = JavaSystemProbe.baseline(collectorName());
        readOsRelease(builder);
        readDmi(builder);
        readCpu(builder);
        readStorage(builder);
        readGpu(builder);
        addMissingReasons(builder);
        return builder.build();
    }

    private void readOsRelease(SystemInfoBuilder builder) {
        String content = readText(Path.of("/etc/os-release"), builder, "linux.osRelease");
        if (content == null) return;
        Map<String, String> values = LinuxParsers.parseOsRelease(content);
        builder.osName = values.getOrDefault("NAME", builder.osName);
        builder.osVersion = values.getOrDefault("VERSION", values.getOrDefault("VERSION_ID", builder.osVersion));
    }

    private void readDmi(SystemInfoBuilder builder) {
        builder.manufacturer = readFirst(Path.of("/sys/class/dmi/id/sys_vendor"));
        builder.model = readFirst(Path.of("/sys/class/dmi/id/product_name"));
        builder.firmwareName = readFirst(Path.of("/sys/class/dmi/id/bios_vendor"));
        builder.firmwareVersion = readFirst(Path.of("/sys/class/dmi/id/bios_version"));
    }

    private void readCpu(SystemInfoBuilder builder) {
        String content = readText(Path.of("/proc/cpuinfo"), builder, "linux.cpuinfo");
        if (content != null) LinuxParsers.parseCpuInfo(content, builder);
        String maximumKhz = readFirst(Path.of("/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq"));
        try {
            if (maximumKhz != null) builder.maxFrequencyMhz = Long.parseLong(maximumKhz) / 1000L;
        } catch (NumberFormatException ignored) {}
    }

    private void readStorage(SystemInfoBuilder builder) {
        CommandResult result = commandRunner.run(List.of("lsblk", "-P", "-b", "-o",
                "NAME,MODEL,TYPE,SIZE,ROTA,FSTYPE,MOUNTPOINT,PKNAME"),
                COMMAND_TIMEOUT, StandardCharsets.UTF_8);
        if (result.successful()) LinuxParsers.parseLsblk(result.standardOutput(), builder);
        else builder.unavailable.put("linux.lsblk", commandReason("lsblk", result));
    }

    private void readGpu(SystemInfoBuilder builder) {
        CommandResult result = commandRunner.run(List.of("lspci", "-mm"),
                COMMAND_TIMEOUT, StandardCharsets.UTF_8);
        if (result.successful()) LinuxParsers.parseLspci(result.standardOutput(), builder);
        else builder.unavailable.put("linux.lspci", commandReason("lspci", result));
        if (!builder.gpus.isEmpty()) {
            builder.unavailable.put("gpu.driver", "Driver and VRAM are not reliably exposed by lspci");
        }
    }

    private String readText(Path path, SystemInfoBuilder builder, String reasonKey) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (Exception exception) {
            builder.unavailable.put(reasonKey, "Cannot read " + path + ": "
                    + exception.getClass().getSimpleName());
            return null;
        }
    }

    private String readFirst(Path path) {
        try {
            String value = Files.readString(path, StandardCharsets.UTF_8).trim();
            return value.isEmpty() ? null : value;
        } catch (Exception exception) {
            return null;
        }
    }

    private String commandReason(String command, CommandResult result) {
        if (result.timedOut()) return command + " timed out after 5 seconds";
        String error = result.standardError();
        if (error == null || error.isBlank()) return command + " is unavailable or returned exit " + result.exitCode();
        String oneLine = error.replace('\r', ' ').replace('\n', ' ').trim();
        return oneLine.length() > 250 ? oneLine.substring(0, 250) + "..." : oneLine;
    }

    private void addMissingReasons(SystemInfoBuilder builder) {
        if (builder.manufacturer == null) builder.unavailable.put("system.manufacturer",
                "DMI sysfs value is unavailable or not readable without additional permission");
        if (builder.model == null) builder.unavailable.put("system.model", "DMI product name is unavailable");
        if (builder.firmwareVersion == null) builder.unavailable.put("system.firmware", "DMI BIOS/UEFI data is unavailable");
        if (builder.cpuModel == null) builder.unavailable.put("cpu.model", "/proc/cpuinfo did not expose a model name");
        if (builder.gpus.isEmpty()) builder.unavailable.put("gpu", "No GPU found; lspci may be unavailable");
    }

    @Override
    public String collectorName() {
        return "Linux Java + procfs/sysfs collector";
    }
}
