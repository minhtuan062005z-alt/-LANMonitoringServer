package lanmonitoringserver.monitor.wireless;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lanmonitoringserver.model.MeasurementStatus;
import lanmonitoringserver.model.WirelessConfidence;
import lanmonitoringserver.model.WirelessMeasurementTarget;
import lanmonitoringserver.model.WirelessNetworkType;
import lanmonitoringserver.model.WirelessRttCapability;
import lanmonitoringserver.model.WirelessSignalSample;
import lanmonitoringserver.util.CommandResult;
import lanmonitoringserver.util.CommandRunner;

/** Reads nl80211 data through iw; link reads do not trigger an active network scan. */
public final class LinuxIwWirelessCollector implements WirelessSignalCollector {
    private static final Pattern INTERFACE = Pattern.compile("(?m)^\\s*Interface\\s+(\\S+)");
    private static final Pattern CONNECTED = Pattern.compile("(?im)^Connected to\\s+([0-9a-f]{2}(?::[0-9a-f]{2}){5})");
    private static final Pattern SSID = Pattern.compile("(?m)^\\s*SSID:\\s*(.+)$");
    private static final Pattern SIGNAL = Pattern.compile("(?m)^\\s*signal:\\s*(-?\\d+(?:\\.\\d+)?)\\s*dBm");
    private final CommandRunner commands;
    private volatile String interfaceName;
    private volatile WirelessRttCapability capability;

    public LinuxIwWirelessCollector() { this(new CommandRunner()); }
    LinuxIwWirelessCollector(CommandRunner commands) { this.commands = commands; }

    @Override public WirelessSignalSample collect() {
        String adapter = interfaceName;
        if (adapter == null) {
            CommandResult devices = commands.run(List.of("iw", "dev"), Duration.ofMillis(1200), StandardCharsets.UTF_8);
            if (!devices.successful()) return unsupported("iw/nl80211 unavailable");
            Matcher matcher = INTERFACE.matcher(devices.standardOutput());
            if (!matcher.find()) return unsupported("No nl80211 Wi-Fi interface detected");
            adapter = matcher.group(1);
            interfaceName = adapter;
        }
        CommandResult link = commands.run(List.of("iw", "dev", adapter, "link"),
                Duration.ofMillis(1200), StandardCharsets.UTF_8);
        if (!link.successful()) return unsupported("iw link failed");
        WirelessRttCapability currentCapability = capability;
        if (currentCapability == null) {
            CommandResult phy = commands.run(List.of("iw", "phy"), Duration.ofMillis(1500), StandardCharsets.UTF_8);
            currentCapability = phy.successful() && phy.standardOutput().toLowerCase(java.util.Locale.ROOT)
                    .contains("ftm initiator") ? WirelessRttCapability.SUPPORTED
                    : phy.successful() ? WirelessRttCapability.UNSUPPORTED : WirelessRttCapability.UNKNOWN;
            capability = currentCapability;
        }
        return parse(link.standardOutput(), adapter, currentCapability, 2_000, System.currentTimeMillis());
    }

    static WirelessSignalSample parse(String output, String adapter,
            WirelessRttCapability capability, long interval, long timestamp) {
        Matcher connected = CONNECTED.matcher(output);
        if (!connected.find()) return new WirelessSignalSample(timestamp, interval,
                MeasurementStatus.UNSUPPORTED, WirelessNetworkType.NOT_CONNECTED, adapter,
                null, null, "", null, null, WirelessMeasurementTarget.NOT_DETERMINED,
                capability, WirelessConfidence.UNKNOWN, "Linux iw/nl80211", "Wi-Fi is not connected");
        String bssid = connected.group(1);
        Matcher ssidMatcher = SSID.matcher(output);
        Matcher signalMatcher = SIGNAL.matcher(output);
        String ssid = ssidMatcher.find() ? ssidMatcher.group(1).trim() : null;
        Double rssi = signalMatcher.find() ? Double.valueOf(signalMatcher.group(1)) : null;
        MeasurementStatus status = rssi == null ? MeasurementStatus.UNSUPPORTED : MeasurementStatus.OK;
        return new WirelessSignalSample(timestamp, interval, status, WirelessNetworkType.WIFI,
                adapter, ssid, bssid, WirelessSignalSample.targetIdForBssid(bssid), rssi, null,
                WirelessMeasurementTarget.ACCESS_POINT, capability,
                rssi == null ? WirelessConfidence.UNKNOWN : WirelessConfidence.LOW,
                "Linux iw dev <interface> link",
                rssi == null ? "Connected AP found but driver did not expose RSSI dBm"
                        : "RSSI is the Client-to-Access-Point signal, not Client-to-Server ranging.");
    }

    private WirelessSignalSample unsupported(String detail) {
        return new WirelessSignalSample(System.currentTimeMillis(), 2_000,
                MeasurementStatus.UNSUPPORTED, WirelessNetworkType.UNKNOWN,
                interfaceName == null ? "" : interfaceName, null, null, "", null, null,
                WirelessMeasurementTarget.NOT_DETERMINED, WirelessRttCapability.UNKNOWN,
                WirelessConfidence.UNKNOWN, collectorName(), detail);
    }
    @Override public String collectorName() { return "Linux iw/nl80211"; }
}
