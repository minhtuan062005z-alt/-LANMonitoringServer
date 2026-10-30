package lanmonitoringserver.monitor.wireless;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lanmonitoringserver.model.AccessPointSignal;
import lanmonitoringserver.model.MeasurementStatus;
import lanmonitoringserver.model.WifiScanSnapshot;
import lanmonitoringserver.model.WirelessSignalSample;
import lanmonitoringserver.util.CommandResult;
import lanmonitoringserver.util.CommandRunner;

/** Active nl80211 scan through iw. Permission/driver failures remain explicit. */
public final class LinuxIwMultiApScanner implements MultiAccessPointScanner {
    private static final Pattern INTERFACE = Pattern.compile("(?m)^\\s*Interface\\s+(\\S+)");
    private static final Pattern BSS = Pattern.compile("(?im)^BSS\\s+([0-9a-f]{2}(?::[0-9a-f]{2}){5})");
    private static final Pattern SIGNAL = Pattern.compile("(?im)^\\s*signal:\\s*(-?\\d+(?:\\.\\d+)?)\\s*dBm");
    private static final Pattern FREQUENCY = Pattern.compile("(?im)^\\s*freq:\\s*(\\d+)");
    private final CommandRunner commands;
    private volatile String interfaceName;

    public LinuxIwMultiApScanner() { this(new CommandRunner()); }
    LinuxIwMultiApScanner(CommandRunner commands) { this.commands = commands; }

    @Override public WifiScanSnapshot scan() {
        long now = System.currentTimeMillis();
        String adapter = interfaceName;
        if (adapter == null) {
            CommandResult devices = commands.run(List.of("iw", "dev"), Duration.ofMillis(1500),
                    StandardCharsets.UTF_8);
            if (!devices.successful()) return unavailable(now, "iw/nl80211 is unavailable or access was denied");
            Matcher match = INTERFACE.matcher(devices.standardOutput());
            if (!match.find()) return unavailable(now, "No nl80211 Wi-Fi interface detected");
            adapter = match.group(1);
            interfaceName = adapter;
        }
        CommandResult result = commands.run(List.of("iw", "dev", adapter, "scan"),
                Duration.ofSeconds(6), StandardCharsets.UTF_8);
        if (!result.successful()) {
            String error = result.standardError().toLowerCase(Locale.ROOT);
            String reason = error.contains("operation not permitted") || error.contains("permission")
                    ? "Linux Wi-Fi scan permission denied; no elevation is attempted"
                    : "Linux iw scan failed (driver may not support active scan while connected)";
            return unavailable(now, reason);
        }
        List<AccessPointSignal> signals = parse(result.standardOutput());
        return new WifiScanSnapshot(now, interval(), signals.isEmpty()
                ? MeasurementStatus.UNSUPPORTED : MeasurementStatus.OK, adapter, signals,
                scannerName(), signals.isEmpty() ? "Scan returned no AP with RSSI dBm"
                        : "Active scan; AP identifiers are SHA-256 hashes and SSIDs are not collected");
    }

    static List<AccessPointSignal> parse(String output) {
        LinkedHashMap<String, AccessPointSignal> result = new LinkedHashMap<>();
        Matcher bss = BSS.matcher(output == null ? "" : output);
        List<Integer> starts = new ArrayList<>();
        List<String> addresses = new ArrayList<>();
        while (bss.find()) { starts.add(bss.start()); addresses.add(bss.group(1)); }
        for (int i = 0; i < starts.size(); i++) {
            int end = i + 1 < starts.size() ? starts.get(i + 1) : output.length();
            String block = output.substring(starts.get(i), end);
            Matcher signal = SIGNAL.matcher(block);
            if (!signal.find()) continue;
            double rssi = Double.parseDouble(signal.group(1));
            if (rssi < -150 || rssi > 0) continue;
            Matcher frequency = FREQUENCY.matcher(block);
            Integer mhz = frequency.find() ? Integer.valueOf(frequency.group(1)) : null;
            String target = WirelessSignalSample.targetIdForBssid(addresses.get(i));
            AccessPointSignal candidate = new AccessPointSignal(target, rssi, mhz);
            result.merge(target, candidate, (oldValue, newValue) ->
                    newValue.rssiDbm() > oldValue.rssiDbm() ? newValue : oldValue);
        }
        return List.copyOf(result.values());
    }

    private WifiScanSnapshot unavailable(long now, String detail) {
        return new WifiScanSnapshot(now, interval(), MeasurementStatus.UNSUPPORTED,
                interfaceName == null ? "" : interfaceName, List.of(), scannerName(), detail);
    }
    private static long interval() {
        return Math.max(5_000L, Math.min(300_000L,
                Long.getLong("lanmonitoring.fingerprint.interval.ms", 10_000L)));
    }
    @Override public String scannerName() { return "Linux iw/nl80211 multi-AP scan"; }
}
