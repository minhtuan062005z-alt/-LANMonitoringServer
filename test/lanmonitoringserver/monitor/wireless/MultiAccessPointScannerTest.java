package lanmonitoringserver.monitor.wireless;

public final class MultiAccessPointScannerTest {
    public static void main(String[] args) {
        String fixture = """
                BSS aa:bb:cc:dd:ee:01(on wlan0)
                    freq: 2412
                    signal: -44.00 dBm
                    SSID: private-one
                BSS aa:bb:cc:dd:ee:02(on wlan0)
                    freq: 5180
                    signal: -61.50 dBm
                    SSID: private-two
                BSS aa:bb:cc:dd:ee:03(on wlan0)
                    freq: 5200
                    signal: invalid dBm
                """;
        var parsed = LinuxIwMultiApScanner.parse(fixture);
        check(parsed.size() == 2, "Linux parser retains APs with real RSSI only");
        check(parsed.get(0).rssiDbm() == -44 && parsed.get(1).frequencyMhz() == 5180,
                "Linux RSSI and frequency parsing");
        check(parsed.stream().allMatch(value -> value.targetId().matches("[0-9a-f]{64}")),
                "raw BSSID is replaced by a privacy hash");
        int nativeEntrySize = new WindowsNativeWifiMultiApScanner.WlanBssEntry().size();
        check(nativeEntrySize == 360, "Windows WLAN_BSS_ENTRY layout matches the documented ABI");
        System.out.println("MultiAccessPointScannerTest: PASS");
    }
    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError("Failed assertion: " + description);
    }
}
