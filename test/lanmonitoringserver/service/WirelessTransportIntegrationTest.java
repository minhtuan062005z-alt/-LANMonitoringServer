package lanmonitoringserver.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import javax.net.SocketFactory;
import lanmonitoringserver.model.MeasurementStatus;
import lanmonitoringserver.model.WirelessConfidence;
import lanmonitoringserver.model.WirelessMeasurementTarget;
import lanmonitoringserver.model.WirelessNetworkType;
import lanmonitoringserver.model.WirelessObservation;
import lanmonitoringserver.model.WirelessRttCapability;
import lanmonitoringserver.model.WirelessSignalSample;
import lanmonitoringserver.monitor.LocalSystemMonitor;
import lanmonitoringserver.monitor.metrics.OshiMetricsCollector;
import lanmonitoringserver.monitor.system.GenericSystemInfoCollector;
import lanmonitoringserver.monitor.wireless.WirelessSignalCollector;
import lanmonitoringserver.monitor.wireless.MultiAccessPointScanner;
import lanmonitoringserver.model.AccessPointSignal;
import lanmonitoringserver.model.ClientStatus;
import lanmonitoringserver.model.ServerClientDistanceStatus;
import lanmonitoringserver.model.SpatialPosition;
import lanmonitoringserver.model.WifiScanSnapshot;
import lanmonitoringserver.security.ClientCredentials;
import lanmonitoringserver.security.InMemoryCredentialRegistry;

/** TCP regression for negotiation, authenticated identity, transport and tracker storage. */
public final class WirelessTransportIntegrationTest {
    public static void main(String[] args) throws Exception {
        System.setProperty("lanmonitoring.fingerprint.requireTls", "false");
        List<String> serverLogs = new CopyOnWriteArrayList<>();
        List<String> clientLogs = new CopyOnWriteArrayList<>();
        List<WirelessObservation> callbacks = new CopyOnWriteArrayList<>();
        LanServerService server = new LanServerService(new InMemoryCredentialRegistry(),
                javax.net.ServerSocketFactory.getDefault(), new InMemoryAlertStore());
        server.setListener(new ServerEventListener() {
            @Override public void onLog(String message) { serverLogs.add(message); }
            @Override public void onWirelessSignal(String clientId, WirelessObservation observation) {
                callbacks.add(observation);
            }
        });
        Path receiveDirectory = Files.createTempDirectory("lan-wireless-transport-");
        ClientCredentials credentials = ClientCredentials.generate();
        WirelessSignalCollector collector = new WirelessSignalCollector() {
            @Override public WirelessSignalSample collect() {
                return new WirelessSignalSample(System.currentTimeMillis(), 2_000,
                        MeasurementStatus.OK, WirelessNetworkType.WIFI,
                        "Realtek RTL8852BE WiFi 6", "not-shared", "aa:bb:cc:dd:ee:ff",
                        WirelessSignalSample.targetIdForBssid("aa:bb:cc:dd:ee:ff"),
                        -38d, 100d, WirelessMeasurementTarget.ACCESS_POINT,
                        WirelessRttCapability.UNKNOWN, WirelessConfidence.LOW,
                        "Windows netsh wlan show interfaces", "direct fixture observation");
            }
            @Override public String collectorName() { return "Windows netsh regression fixture"; }
        };
        MultiAccessPointScanner scanner = new MultiAccessPointScanner() {
            @Override public WifiScanSnapshot scan() {
                return new WifiScanSnapshot(System.currentTimeMillis(), 10_000,
                        MeasurementStatus.OK, "Realtek RTL8852BE WiFi 6", List.of(
                                new AccessPointSignal(WirelessSignalSample.targetIdForBssid(
                                        "aa:bb:cc:dd:ee:01"), -44, 2412),
                                new AccessPointSignal(WirelessSignalSample.targetIdForBssid(
                                        "aa:bb:cc:dd:ee:02"), -57, 5180),
                                new AccessPointSignal(WirelessSignalSample.targetIdForBssid(
                                        "aa:bb:cc:dd:ee:03"), -69, 5200)),
                        "Native Wi-Fi fixture", "hashed AP fixture");
            }
            @Override public String scannerName() { return "multi-AP transport fixture"; }
        };
        LanClientService client = new LanClientService(credentials, new LocalSystemMonitor(),
                SocketFactory.getDefault(), new GenericSystemInfoCollector(),
                new OshiMetricsCollector(), new FileReceiver(receiveDirectory, 1_024_000), collector,
                scanner);
        client.setListener(new ClientEventListener() {
            @Override public void onLog(String message) { clientLogs.add(message); }
        });
        int port = server.start(0);
        try {
            client.probeWirelessCollector();
            await(Duration.ofSeconds(2), () -> clientLogs.stream().anyMatch(value ->
                            value.contains("Wi-Fi collector probe") && value.contains("-38.0 dBm")
                            && value.contains("AP target=available (privacy hash)")),
                    "privacy-safe local Windows collector probe");
            client.setWirelessMonitoringEnabled(true);
            client.setFingerprintingEnabled(true);
            client.connect("127.0.0.1", port, "WINDOWS-RSSI-CLIENT", "127.0.0.1");
            await(Duration.ofSeconds(10), () -> !server.wirelessHistory(credentials.clientId()).isEmpty(),
                    "authenticated WIRELESS_SIGNAL reaches tracker");
            await(Duration.ofSeconds(2), () -> !callbacks.isEmpty(),
                    "wireless observation reaches Server event listener");
            await(Duration.ofSeconds(10), () -> server.latestWifiScan(credentials.clientId()) != null,
                    "authenticated WIFI_SCAN reaches Server");

            var clients = server.clients();
            check(clients.stream().anyMatch(value -> value.clientId().equals(credentials.clientId())),
                    "modern client keeps authenticated client ID");
            check(clients.stream().noneMatch(value -> value.clientId().startsWith("legacy:")),
                    "modern client is not classified as legacy");
            WirelessObservation stored = server.wirelessHistory(credentials.clientId()).get(0);
            check(stored.signal().rssiDbm() != null && stored.signal().rssiDbm() == -38d,
                    "RSSI -38 dBm survives JSON transport and tracker storage");
            check(stored.signal().linkQualityPercent() == 100d,
                    "Signal 100 percent remains independent through transport");
            check(stored.signal().ssid() == null && stored.signal().bssid() == null,
                    "SSID and BSSID remain hidden by default");
            check(clientLogs.stream().anyMatch(value -> value.contains("modern JSON v1")
                            && value.contains("WIRELESS_SIGNAL_V1=true")
                            && value.contains("WIFI_FINGERPRINT_V1=true")
                            && value.contains("transport=plain TCP")),
                    "Client logs negotiated modern wireless capability");
            check(clientLogs.stream().anyMatch(value -> value.contains("WIFI_SCAN sent #1")
                            && value.contains("hashed APs=3")),
                    "Client logs first privacy-safe multi-AP send");
            check(clientLogs.stream().anyMatch(value -> value.contains("WIRELESS_SIGNAL sent #1")
                            && value.contains("-38.0 dBm")),
                    "Client diagnostic confirms first real-valued sample send");
            check(serverLogs.stream().anyMatch(value -> value.contains("modern framed JSON v1")),
                    "Server logs modern protocol detection");
            check(serverLogs.stream().anyMatch(value -> value.contains("authenticated and stored #1")
                            && value.contains("-38.0 dBm")),
                    "Server diagnostic confirms authenticated tracker storage");
            check(serverLogs.stream().anyMatch(value -> value.contains("plain TCP lab override")),
                    "Server logs why fingerprint capability is advertised without TLS in the lab");
            check(serverLogs.stream().anyMatch(value -> value.contains("WIFI_SCAN authenticated")
                            && value.contains("hashed APs=3")),
                    "Server logs authenticated multi-AP receive and tracker update");
            check(server.latestWifiScan(credentials.clientId()).accessPoints().size() == 3,
                    "multi-AP RSSI snapshot survives authenticated transport");
            long surveyedAt = System.currentTimeMillis();
            server.setServerPosition(new SpatialPosition("transport-lab", 0, 0, null, surveyedAt));
            server.addRadioMapSample(credentials.clientId(),
                    new SpatialPosition("transport-lab", 1, 0, null, surveyedAt));
            server.addRadioMapSample(credentials.clientId(),
                    new SpatialPosition("transport-lab", 0, 1, null, surveyedAt));
            server.addRadioMapSample(credentials.clientId(),
                    new SpatialPosition("transport-lab", 1, 1, null, surveyedAt));
            check(server.serverClientDistance(credentials.clientId()).status()
                            == ServerClientDistanceStatus.OK,
                    "fresh fingerprint creates an OK position-based distance");
            long firstScanTimestamp = server.latestWifiScan(credentials.clientId()).timestamp();
            client.disconnect();
            await(Duration.ofSeconds(5), () -> server.clients().stream().anyMatch(value ->
                            value.clientId().equals(credentials.clientId())
                            && value.status() == ClientStatus.OFFLINE),
                    "fingerprint Client becomes offline after disconnect");
            check(server.serverClientDistance(credentials.clientId()).status()
                            == ServerClientDistanceStatus.STALE
                            && server.serverClientDistance(credentials.clientId()).distanceMeters() == null,
                    "disconnect keeps historical position but invalidates realtime distance");
            check(server.clients().stream().filter(value ->
                            value.clientId().equals(credentials.clientId())).count() == 1,
                    "disconnect preserves one stable Client identity");
            client.connect("127.0.0.1", port, "WINDOWS-RSSI-CLIENT", "127.0.0.1");
            await(Duration.ofSeconds(10), () -> server.clients().stream().anyMatch(value ->
                            value.clientId().equals(credentials.clientId())
                            && value.status() == ClientStatus.ONLINE),
                    "same fingerprint Client reconnects");
            await(Duration.ofSeconds(10), () -> server.latestWifiScan(credentials.clientId()) != null
                            && server.latestWifiScan(credentials.clientId()).timestamp() > firstScanTimestamp,
                    "reconnect replaces stale scan with a fresh scan");
            await(Duration.ofSeconds(2), () -> server.serverClientDistance(credentials.clientId()).status()
                            == ServerClientDistanceStatus.OK,
                    "fresh post-reconnect fingerprint restores OK distance");
            check(server.clients().stream().filter(value ->
                            value.clientId().equals(credentials.clientId())).count() == 1,
                    "reconnect does not duplicate the stable Client");
            System.out.println("WirelessTransportIntegrationTest: PASS");
        } finally {
            client.close();
            server.close();
            Files.deleteIfExists(receiveDirectory);
            System.clearProperty("lanmonitoring.fingerprint.requireTls");
        }
    }

    private static void await(Duration timeout, BooleanSupplier condition, String description)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(25);
        }
        throw new AssertionError("Timed out waiting for: " + description);
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError("Failed assertion: " + description);
    }
}
