package lanmonitoringserver.monitor.wireless;

import java.nio.charset.Charset;
import java.text.Normalizer;
import java.util.ArrayList;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
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

/** Reads the connected Windows interface without initiating an active Wi-Fi scan. */
public final class WindowsNetshWirelessCollector implements WirelessSignalCollector {
    private static final Pattern BSSID = Pattern.compile("(?i)\\b([0-9a-f]{2}(?:[:-][0-9a-f]{2}){5})\\b");
    private static final Pattern PERCENT = Pattern.compile("(\\d{1,3}(?:[.,]\\d+)?)\\s*%");
    private static final Pattern RSSI_DBM = Pattern.compile(
            "(?i)^\\s*([+-]?\\d{1,3}(?:[.,]\\d+)?)\\s*(?:dBm)?\\s*$");
    private final CommandRunner commands;

    public WindowsNetshWirelessCollector() { this(new CommandRunner()); }
    WindowsNetshWirelessCollector(CommandRunner commands) { this.commands = commands; }

    @Override public WirelessSignalSample collect() {
        CommandResult result = commands.run(List.of("netsh", "wlan", "show", "interfaces"),
                Duration.ofMillis(1500), netshCharset());
        return fromCommandResult(result, 2_000, System.currentTimeMillis());
    }

    static WirelessSignalSample fromCommandResult(CommandResult result, long interval, long timestamp) {
        if (!result.successful()) return unsupported(timestamp, interval,
                "netsh wlan unavailable: " + safe(result));
        return parse(result.standardOutput(), interval, timestamp);
    }

    static WirelessSignalSample parse(String output, long interval, long timestamp) {
        List<ParsedInterface> interfaces = new ArrayList<>();
        ParsedInterface current = new ParsedInterface();
        for (String raw : output.split("\\R")) {
            String line = raw.replace("\uFEFF", "").trim();
            int separator = line.indexOf(':');
            String key = separator < 0 ? "" : normalize(line.substring(0, separator));
            String value = separator < 0 ? "" : line.substring(separator + 1).trim();
            if (key.equals("name") || key.equals("ten")) {
                if (current.hasData()) interfaces.add(current);
                current = new ParsedInterface();
                current.interfaceName = value;
            }
            if (key.equals("description") || key.equals("mo ta")) current.description = value;
            if (key.equals("state") || key.contains("trang thai")) {
                String state = normalize(value);
                current.connected = state.equals("connected") || state.contains("da ket noi")
                        || state.equals("ket noi");
                current.explicitlyDisconnected = state.contains("disconnected")
                        || state.contains("ngat ket noi") || state.contains("khong ket noi");
            }
            if (key.equals("ssid")) current.ssid = value;
            if (key.equals("bssid") || key.endsWith(" bssid") || key.startsWith("bssid ")) {
                Matcher matcher = BSSID.matcher(value);
                if (matcher.find()) current.bssid = matcher.group(1);
            }
            if (key.equals("signal") || key.contains("tin hieu")) {
                Matcher matcher = PERCENT.matcher(value);
                if (matcher.find()) {
                    double parsed = Double.parseDouble(matcher.group(1).replace(',', '.'));
                    if (parsed >= 0 && parsed <= 100) current.quality = parsed;
                }
            }
            if (key.equals("rssi") || key.contains("rssi dbm")) {
                Matcher matcher = RSSI_DBM.matcher(value.replace('\u2212', '-'));
                if (matcher.matches()) {
                    double parsed = Double.parseDouble(matcher.group(1).replace(',', '.'));
                    if (parsed >= -150 && parsed <= 0) current.rssi = parsed;
                    else current.invalidRssi = true;
                } else current.invalidRssi = true;
            }
        }
        if (current.hasData()) interfaces.add(current);
        ParsedInterface selected = interfaces.stream()
                .filter(ParsedInterface::isConnected)
                .sorted((left, right) -> Boolean.compare(right.rssi != null, left.rssi != null))
                .findFirst().orElse(current);
        if (!selected.isConnected()) {
            return new WirelessSignalSample(timestamp, interval, MeasurementStatus.UNSUPPORTED,
                    WirelessNetworkType.NOT_CONNECTED, selected.adapter(), null, null, "", null, null,
                    WirelessMeasurementTarget.NOT_DETERMINED, WirelessRttCapability.UNKNOWN,
                    WirelessConfidence.UNKNOWN, "Windows netsh wlan",
                    "No connected Wi-Fi interface detected");
        }
        String targetId = WirelessSignalSample.targetIdForBssid(selected.bssid);
        String detail = selected.rssi != null
                ? "Direct RSSI dBm reported by Windows netsh for the Client-to-Access-Point link."
                : selected.invalidRssi
                        ? "Windows netsh RSSI field was invalid; Link Quality remains separate and distance is unavailable."
                        : "Windows netsh did not report RSSI dBm; Link Quality percent is not converted to RSSI.";
        return new WirelessSignalSample(timestamp, interval, MeasurementStatus.OK,
                WirelessNetworkType.WIFI, selected.adapter(), selected.ssid, selected.bssid, targetId,
                selected.rssi, selected.quality, WirelessMeasurementTarget.ACCESS_POINT,
                WirelessRttCapability.UNKNOWN, WirelessConfidence.LOW,
                "Windows netsh wlan show interfaces", detail);
    }

    private static WirelessSignalSample unsupported(long timestamp, long interval, String detail) {
        return new WirelessSignalSample(timestamp, interval,
                MeasurementStatus.UNSUPPORTED, WirelessNetworkType.UNKNOWN, "", null, null, "",
                null, null, WirelessMeasurementTarget.NOT_DETERMINED, WirelessRttCapability.UNKNOWN,
                WirelessConfidence.UNKNOWN, "Windows netsh wlan", detail);
    }
    private static String safe(CommandResult result) {
        String value = result.standardError().isBlank() ? "exit " + result.exitCode() : result.standardError().trim();
        return value.length() > 200 ? value.substring(0, 200) : value;
    }
    private static String normalize(String value) {
        String decomposed = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT).trim();
        return decomposed.replace('đ', 'd').replaceAll("\\s+", " ");
    }
    static Charset netshCharset() {
        String configured = System.getProperty("lanmonitoring.wifi.netsh.charset", "").trim();
        if (configured.isEmpty()) return Charset.defaultCharset();
        try { return Charset.forName(configured); }
        catch (IllegalArgumentException invalid) { return Charset.defaultCharset(); }
    }

    private static final class ParsedInterface {
        private String interfaceName = "";
        private String description = "";
        private String ssid;
        private String bssid;
        private Double rssi;
        private Double quality;
        private boolean connected;
        private boolean explicitlyDisconnected;
        private boolean invalidRssi;
        private boolean hasData() {
            return !interfaceName.isBlank() || !description.isBlank() || ssid != null || bssid != null
                    || rssi != null || quality != null
                    || connected || explicitlyDisconnected || invalidRssi;
        }
        private String adapter() { return description.isBlank() ? interfaceName : description; }
        private boolean isConnected() {
            return !explicitlyDisconnected && (connected || bssid != null);
        }
    }
    @Override public String collectorName() { return "Windows netsh wlan"; }
}
