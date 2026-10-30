package lanmonitoringserver.monitor.wireless;

import lanmonitoringserver.model.MeasurementStatus;
import lanmonitoringserver.model.WirelessRttCapability;
import lanmonitoringserver.model.WirelessNetworkType;
import lanmonitoringserver.util.CommandResult;
import lanmonitoringserver.model.WirelessCalibrationPoint;
import lanmonitoringserver.service.WirelessTracker;

public final class WirelessCollectorTest {
    public static void main(String[] args) {
        long now = System.currentTimeMillis();
        testWindowsRealRssi(now);
        var windows = WindowsNetshWirelessCollector.parse("""
                Name                   : Wi-Fi
                State                  : connected
                SSID                   : Lab Network
                BSSID                  : aa:bb:cc:dd:ee:ff
                Signal                 : 76%
                """, 2000, now);
        check(windows.status() == MeasurementStatus.OK, "Windows connected link parsed");
        check(windows.rssiDbm() == null, "Windows link quality must not be fabricated as dBm");
        check(windows.linkQualityPercent() == 76d, "Windows link quality parsed");
        check(!windows.targetId().isBlank(), "AP pseudonymous target ID derived");
        testWindowsInvalidAndDisconnected(now);
        testWindowsLocalizedAndMultipleInterfaces(now);
        testWindowsAccessFailure(now);

        var linux = LinuxIwWirelessCollector.parse("""
                Connected to aa:bb:cc:dd:ee:ff (on wlan0)
                    SSID: Lab Network
                    signal: -61.00 dBm
                """, "wlan0", WirelessRttCapability.UNSUPPORTED, 2000, now);
        check(linux.rssiDbm() == -61d, "Linux RSSI dBm parsed");
        check(linux.linkQualityPercent() == null, "Linux parser does not invent link quality");
        check(linux.targetId().equals(windows.targetId()), "same BSSID maps to same private target ID");
        var hidden = linux.forSharing(false);
        check(hidden.ssid() == null && hidden.bssid() == null && !hidden.targetId().isBlank(),
                "privacy mode hides identifiers while preserving calibration key");
        System.out.println("WirelessCollectorTest: PASS");
    }

    private static void testWindowsRealRssi(long now) {
        var sample = WindowsNetshWirelessCollector.parse("""
                There is 1 interface on the system:

                    Name                   : Wi-Fi
                    Description            : Realtek RTL8852BE WiFi 6
                    GUID                   : 00000000-0000-0000-0000-000000000000
                    Physical address       : 00:11:22:33:44:55
                    Interface type         : Primary
                    State                  : connected
                    SSID                   : Lab Network
                    AP BSSID               : aa:bb:cc:dd:ee:ff
                    Band                   : 5 GHz
                    Channel                : 36
                    Signal                 : 100%
                    Rssi                   : -44
                    Receive rate (Mbps)    : 866.7
                    Transmit rate (Mbps)   : 866.7
                """, 2000, now);
        check(sample.status() == MeasurementStatus.OK, "real Windows fixture connected");
        check(sample.adapter().equals("Realtek RTL8852BE WiFi 6"), "adapter description parsed");
        check(sample.rssiDbm() != null && sample.rssiDbm() == -44d, "RSSI -44 dBm parsed directly");
        check(sample.linkQualityPercent() == 100d, "Signal 100 percent remains separate");
        check(sample.detail().contains("Direct RSSI"), "direct RSSI provenance reported");
        var currentDeviceSample = WindowsNetshWirelessCollector.parse("""
                Name : Wi-Fi
                Description : Realtek RTL8852BE WiFi 6
                State : connected
                AP BSSID : aa:bb:cc:dd:ee:ff
                Signal : 100%
                Rssi : -38 dBm
                """, 2000, now + 2);
        check(currentDeviceSample.rssiDbm() == -38d,
                "current Windows RSSI -38 dBm parsed directly when Signal percent also exists");
        check(currentDeviceSample.linkQualityPercent() == 100d,
                "current Windows Signal 100 percent remains a separate metric");
        WirelessTracker tracker = new WirelessTracker();
        check(tracker.accept(sample), "Windows RSSI enters existing wireless tracker");
        check(tracker.latest().distance().estimatedMeters() == null,
                "Windows RSSI does not produce distance before calibration");
        tracker.calibrate(java.util.List.of(new WirelessCalibrationPoint(1, -40),
                new WirelessCalibrationPoint(2, -46.02), new WirelessCalibrationPoint(4, -52.04)));
        var next = WindowsNetshWirelessCollector.parse("""
                Name : Wi-Fi
                State : connected
                BSSID : aa:bb:cc:dd:ee:ff
                Signal : 100%
                RSSI : -44 dBm
                """, 2000, now + 1);
        check(tracker.accept(next), "new Windows RSSI accepted after calibration");
        check(tracker.latest().distance().estimatedMeters() != null,
                "real Windows RSSI enables calibrated log-distance estimate");
        check(tracker.latest().distance().target()
                == lanmonitoringserver.model.WirelessMeasurementTarget.ACCESS_POINT,
                "Windows estimate remains Client-to-AP only");
    }

    private static void testWindowsInvalidAndDisconnected(long now) {
        var invalid = WindowsNetshWirelessCollector.parse("""
                Name : Wi-Fi
                State : connected
                SSID : Lab
                BSSID : aa:bb:cc:dd:ee:ff
                Signal : 83%
                RSSI : 44 dBm
                """, 2000, now);
        check(invalid.status() == MeasurementStatus.OK, "invalid RSSI does not hide connected link");
        check(invalid.rssiDbm() == null, "positive RSSI is rejected, not clamped or fabricated");
        check(invalid.linkQualityPercent() == 83d, "quality survives invalid RSSI");
        check(invalid.detail().contains("invalid"), "invalid RSSI is diagnosable");

        var malformed = WindowsNetshWirelessCollector.parse("""
                Name : Wi-Fi
                State : connected
                SSID : Lab
                BSSID : aa:bb:cc:dd:ee:ff
                Signal : 75%
                Rssi : unavailable
                """, 2000, now);
        check(malformed.rssiDbm() == null, "non-numeric RSSI is rejected");

        var disconnected = WindowsNetshWirelessCollector.parse("""
                Name : Wi-Fi
                Description : Realtek RTL8852BE WiFi 6
                State : disconnected
                """, 2000, now);
        check(disconnected.status() == MeasurementStatus.UNSUPPORTED, "disconnected is unsupported");
        check(disconnected.networkType() == WirelessNetworkType.NOT_CONNECTED,
                "disconnected network type is explicit");
        check(disconnected.rssiDbm() == null, "disconnected has no RSSI");
    }

    private static void testWindowsLocalizedAndMultipleInterfaces(long now) {
        var sample = WindowsNetshWirelessCollector.parse("""
                \uFEFFTên : Wi-Fi cũ
                Trạng thái : ngắt kết nối

                Tên : Wi-Fi
                Mô tả : Realtek RTL8852BE WiFi 6
                Trạng thái : đã kết nối
                SSID : Mạng thử nghiệm
                BSSID : AA-BB-CC-DD-EE-FF
                Tín hiệu : 99%
                RSSI : −44 dBm
                """, 2000, now);
        check(sample.status() == MeasurementStatus.OK, "Vietnamese normalized keys parsed");
        check(sample.rssiDbm() == -44d, "Unicode minus RSSI parsed");
        check(sample.adapter().contains("Realtek"), "connected adapter selected from multiple interfaces");
    }

    private static void testWindowsAccessFailure(long now) {
        var denied = WindowsNetshWirelessCollector.fromCommandResult(
                new CommandResult(1, "", "Access is denied.", false), 2000, now);
        check(denied.status() == MeasurementStatus.UNSUPPORTED, "netsh access error is unsupported");
        check(denied.rssiDbm() == null, "access error never creates RSSI");
        check(denied.detail().contains("Access is denied"), "access error retained as bounded diagnostic");

        var missing = WindowsNetshWirelessCollector.fromCommandResult(
                new CommandResult(-1, "", "Cannot run program netsh", false), 2000, now);
        check(missing.status() == MeasurementStatus.UNSUPPORTED, "missing netsh is unsupported");
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
