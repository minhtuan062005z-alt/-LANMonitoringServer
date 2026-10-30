package lanmonitoringserver.service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import javax.net.SocketFactory;
import lanmonitoringserver.model.ClientConnectionState;
import lanmonitoringserver.model.ClientSnapshot;
import lanmonitoringserver.model.ClientStatus;
import lanmonitoringserver.model.AlertDeliveryStatus;
import lanmonitoringserver.model.AlertMetric;
import lanmonitoringserver.model.AlertRule;
import lanmonitoringserver.model.SpatialPosition;
import lanmonitoringserver.monitor.LocalSystemMonitor;
import lanmonitoringserver.monitor.system.GenericSystemInfoCollector;
import lanmonitoringserver.security.ClientCredentials;
import lanmonitoringserver.security.InMemoryCredentialRegistry;

public final class NetworkIntegrationTest {
    public static void main(String[] args) throws Exception {
        LanServerService server = new LanServerService(new InMemoryCredentialRegistry(),
                javax.net.ServerSocketFactory.getDefault(), new InMemoryAlertStore());
        for (AlertMetric metric : AlertMetric.values()) {
            server.updateAlertRule(new AlertRule(metric, metric == AlertMetric.DISK_CAPACITY,
                    0, 100, 0, 1, 1_000));
        }
        List<String> acknowledgements = new CopyOnWriteArrayList<>();
        server.setListener(new ServerEventListener() {
            @Override
            public void onAcknowledgement(String clientId, String hostname, String requestId) {
                acknowledgements.add(requestId);
            }
        });

        int port = server.start(0);
        List<LanClientService> clients = new ArrayList<>();
        List<AtomicInteger> receivedMessages = new ArrayList<>();
        List<AtomicInteger> receivedAlerts = new ArrayList<>();
        List<AtomicInteger> serverDisconnectEvents = new ArrayList<>();
        List<AtomicInteger> serverReconnectEvents = new ArrayList<>();
        List<String> messages = List.of(
                "integration-message-large|\n" + "dữ-liệu-".repeat(6_000),
                "integration-message-1",
                "integration-message-2");
        try {
            for (int index = 0; index < 3; index++) {
                final int clientIndex = index;
                LanClientService client = new LanClientService(
                        ClientCredentials.generate(), new LocalSystemMonitor(),
                        SocketFactory.getDefault(), new GenericSystemInfoCollector());
                AtomicInteger received = new AtomicInteger();
                AtomicInteger alertReceived = new AtomicInteger();
                AtomicInteger disconnectedByServer = new AtomicInteger();
                AtomicInteger reconnectedAfterServerDisconnect = new AtomicInteger();
                client.setListener(new ClientEventListener() {
                    @Override
                    public void onMessage(String content, String requestId) {
                        if (content.equals(messages.get(clientIndex))) received.incrementAndGet();
                    }

                    @Override
                    public void onAlert(lanmonitoringserver.model.AlertNotification alert) {
                        if (("TEST-PC-" + (clientIndex + 1)).equals(alert.hostname())) {
                            alertReceived.incrementAndGet();
                        } else {
                            alertReceived.addAndGet(100);
                        }
                    }

                    @Override
                    public void onDisconnectedByServer() {
                        disconnectedByServer.incrementAndGet();
                    }

                    @Override
                    public void onReconnectedAfterServerDisconnect() {
                        reconnectedAfterServerDisconnect.incrementAndGet();
                    }
                });
                clients.add(client);
                client.setWirelessMonitoringEnabled(true);
                receivedMessages.add(received);
                receivedAlerts.add(alertReceived);
                serverDisconnectEvents.add(disconnectedByServer);
                serverReconnectEvents.add(reconnectedAfterServerDisconnect);
                client.connect("127.0.0.1", port, "TEST-PC-" + (index + 1), "127.0.0.1");
            }

            await(Duration.ofSeconds(10), () -> onlineCount(server.clients()) == 3,
                    "three clients online");
            check(server.clients().stream().filter(client -> client.status() == ClientStatus.ONLINE)
                    .allMatch(client -> client.operatingSystem() != null
                            && !client.operatingSystem().isBlank()), "operating system reported");
            await(Duration.ofSeconds(5), () -> server.clients().stream()
                    .filter(client -> client.status() == ClientStatus.ONLINE)
                    .allMatch(client -> client.diskPercent() != null), "real disk usage reported");
            await(Duration.ofSeconds(8), () -> server.clients().stream()
                    .filter(client -> client.status() == ClientStatus.ONLINE)
                    .allMatch(client -> client.systemInformation() != null),
                    "structured SYSTEM_INFO synchronized");
            await(Duration.ofSeconds(10), () -> server.clients().stream()
                    .filter(client -> client.status() == ClientStatus.ONLINE)
                    .allMatch(client -> !server.telemetryHistory(client.clientId()).isEmpty()),
                    "three clients send structured realtime telemetry");
            await(Duration.ofSeconds(10), () -> server.clients().stream()
                    .filter(client -> client.status() == ClientStatus.ONLINE)
                    .allMatch(client -> !server.wirelessHistory(client.clientId()).isEmpty()),
                    "three clients send real wireless capability observations");
            await(Duration.ofSeconds(10), () -> server.alerts().size() == 3
                    && server.alerts().stream().allMatch(alert ->
                            alert.deliveryStatus() == AlertDeliveryStatus.ACKNOWLEDGED),
                    "alerts delivered and acknowledged by exactly three originating clients");
            check(server.alerts().stream().map(alert -> alert.clientId()).distinct().count() == 3,
                    "alerts remain bound to their originating client IDs");
            check(receivedAlerts.stream().allMatch(count -> count.get() == 1),
                    "each client receives only its own alert popup event");
            await(Duration.ofSeconds(8), () -> server.clients().stream()
                    .filter(client -> client.status() == ClientStatus.ONLINE)
                    .allMatch(client -> client.responseTimeMillis() != null), "PING/PONG RTT");

            List<ClientSnapshot> onlineClients = server.clients().stream()
                    .filter(client -> client.status() == ClientStatus.ONLINE).toList();
            for (int index = 0; index < onlineClients.size(); index++) {
                server.sendMessage(onlineClients.get(index).clientId(), messages.get(index));
            }
            await(Duration.ofSeconds(5), () -> receivedMessages.stream()
                    .mapToInt(AtomicInteger::get).sum() == 3, "messages delivered");
            await(Duration.ofSeconds(5), () -> acknowledgements.size() == 3, "ACK received");

            String reconnectingId = clients.get(0).clientId();
            long locationTime = System.currentTimeMillis();
            server.setServerPosition(new SpatialPosition("integration-lab", 0, 0, null, locationTime));
            server.setClientPosition(reconnectingId,
                    new SpatialPosition("integration-lab", 3, 4, null, locationTime));
            check(server.clients().stream().filter(client -> reconnectingId.equals(client.clientId()))
                    .anyMatch(client -> client.serverClientDistance().distanceMeters() != null
                            && Math.abs(client.serverClientDistance().distanceMeters() - 5) < 0.000001),
                    "distance is attached to stable authenticated Client ID");
            clients.get(0).disconnect();
            await(Duration.ofSeconds(5), () -> statusOf(server.clients(), reconnectingId) == ClientStatus.OFFLINE,
                    "client becomes offline");
            int samplesAtDisconnect = server.telemetryHistory(reconnectingId).size();
            int wirelessAtDisconnect = server.wirelessHistory(reconnectingId).size();
            Thread.sleep(1_500);
            check(server.telemetryHistory(reconnectingId).size() == samplesAtDisconnect,
                    "telemetry scheduler stops on disconnect");
            check(server.wirelessHistory(reconnectingId).size() == wirelessAtDisconnect,
                    "wireless scheduler stops on disconnect");
            clients.get(0).connect("127.0.0.1", port, "TEST-PC-1", "127.0.0.1");
            await(Duration.ofSeconds(10), () -> onlineCount(server.clients()) == 3,
                    "client reconnects with same identity");
            check(server.clients().stream().filter(client -> reconnectingId.equals(client.clientId()))
                    .anyMatch(client -> client.serverClientDistance().distanceMeters() != null
                            && Math.abs(client.serverClientDistance().distanceMeters() - 5) < 0.000001),
                    "distance survives offline and reconnect lifecycle");

            String blockedOne = clients.get(0).clientId();
            String blockedTwo = clients.get(1).clientId();
            LanServerService.ClientControlResult disconnected = server.disconnectClients(
                    List.of(blockedOne, blockedTwo));
            check(disconnected.completed() == 2 && disconnected.failures().isEmpty(),
                    "two selected clients are disconnected independently");
            await(Duration.ofSeconds(5), () ->
                    statusOf(server.clients(), blockedOne) == ClientStatus.DISCONNECTED_BY_SERVER
                    && statusOf(server.clients(), blockedTwo) == ClientStatus.DISCONNECTED_BY_SERVER,
                    "blocked state remains after both sockets close");
            check(statusOf(server.clients(), clients.get(2).clientId()) == ClientStatus.ONLINE,
                    "unselected client remains online");
            await(Duration.ofSeconds(3), () -> serverDisconnectEvents.get(0).get() == 1
                    && serverDisconnectEvents.get(1).get() == 1,
                    "both clients receive the explicit Server disconnect command once");
            Thread.sleep(5_500);
            check(statusOf(server.clients(), blockedOne) == ClientStatus.DISCONNECTED_BY_SERVER
                    && statusOf(server.clients(), blockedTwo) == ClientStatus.DISCONNECTED_BY_SERVER,
                    "periodic blocked reconnect attempts are rejected");

            LanServerService.ClientControlResult firstAllowed = server.allowReconnect(List.of(blockedOne));
            check(firstAllowed.completed() == 1, "one selected client is allowed to reconnect");
            await(Duration.ofSeconds(10), () -> statusOf(server.clients(), blockedOne) == ClientStatus.ONLINE,
                    "first allowed client completes real TCP authentication");
            check(statusOf(server.clients(), blockedTwo) == ClientStatus.DISCONNECTED_BY_SERVER,
                    "unselected blocked client remains blocked");
            await(Duration.ofSeconds(3), () -> serverReconnectEvents.get(0).get() == 1,
                    "first client reports successful reconnect only after AUTH_OK");

            LanServerService.ClientControlResult secondAllowed = server.allowReconnect(List.of(blockedTwo));
            check(secondAllowed.completed() == 1, "second selected client is allowed to reconnect");
            await(Duration.ofSeconds(10), () -> onlineCount(server.clients()) == 3,
                    "both allowed clients reconnect without duplicate records");
            check(server.clients().stream().map(ClientSnapshot::clientId).distinct().count()
                    == server.clients().size(), "reconnect does not duplicate table identities");

            long previousCollection = server.clients().stream()
                    .filter(client -> reconnectingId.equals(client.clientId()))
                    .findFirst().orElseThrow().systemInformation().collectedAt();
            Thread.sleep(5);
            server.requestSystemInfo(reconnectingId);
            await(Duration.ofSeconds(8), () -> server.clients().stream()
                    .filter(client -> reconnectingId.equals(client.clientId()))
                    .map(ClientSnapshot::systemInformation)
                    .anyMatch(info -> info != null && info.collectedAt() > previousCollection),
                    "on-demand SYSTEM_INFO refresh");

            testIdentityImpersonationRejected(server, port, reconnectingId);
            testLegacyTransition(server, port);
            testServerRestartAndAutomaticReconnect(server, port);
            System.out.println("NetworkIntegrationTest: PASS");
        } finally {
            for (LanClientService client : clients) client.close();
            server.stop();
        }
    }

    private static void testIdentityImpersonationRejected(LanServerService server, int port,
            String registeredId) throws Exception {
        LanClientService impostor = new LanClientService(
                new ClientCredentials(registeredId, lanmonitoringserver.security.Authentication.newSecret()),
                new LocalSystemMonitor());
        AtomicInteger connected = new AtomicInteger();
        impostor.setListener(new ClientEventListener() {
            @Override
            public void onStateChanged(ClientConnectionState state, String detail) {
                if (state == ClientConnectionState.CONNECTED) connected.incrementAndGet();
            }
        });
        impostor.connect("127.0.0.1", port, "IMPOSTOR", "127.0.0.1");
        Thread.sleep(1_500);
        check(connected.get() == 0, "impostor must not authenticate");
        check(server.clients().stream().noneMatch(client -> "IMPOSTOR".equals(client.hostname())),
                "impostor must not replace registered client");
        impostor.close();
    }

    private static void testLegacyTransition(LanServerService server, int port) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port);
                PrintWriter writer = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);
                BufferedReader reader = new BufferedReader(new InputStreamReader(
                        socket.getInputStream(), StandardCharsets.UTF_8))) {
            writer.println("CONNECT|CLIENT01|LEGACY-PC|127.0.0.1");
            writer.println("HEARTBEAT|CLIENT01|12.5|34.5");
            await(Duration.ofSeconds(5), () -> server.clients().stream()
                    .anyMatch(client -> client.clientId().startsWith("legacy:CLIENT01@")
                            && client.status() == ClientStatus.ONLINE), "legacy client accepted safely");
            ClientSnapshot legacy = server.clients().stream()
                    .filter(client -> client.clientId().startsWith("legacy:CLIENT01@"))
                    .findFirst().orElseThrow();
            server.sendMessage(legacy.clientId(), "legacy-message");
            socket.setSoTimeout(2_000);
            check("MESSAGE|legacy-message".equals(reader.readLine()), "legacy MESSAGE delivery");
            writer.println("ACK|CLIENT01|MESSAGE_RECEIVED");
        }
    }

    private static void testServerRestartAndAutomaticReconnect(LanServerService server, int port)
            throws Exception {
        long startedAt = System.nanoTime();
        server.stop();
        long stopMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        check(stopMillis < 2_000, "server shutdown must not hang");
        Thread.sleep(1_200);
        server.start(port);
        await(Duration.ofSeconds(15), () -> onlineCount(server.clients()) == 3,
                "automatic reconnect after server restart");
    }

    private static int onlineCount(List<ClientSnapshot> clients) {
        return (int) clients.stream().filter(client -> client.status() == ClientStatus.ONLINE).count();
    }

    private static ClientStatus statusOf(List<ClientSnapshot> clients, String clientId) {
        return clients.stream().filter(client -> client.clientId().equals(clientId))
                .map(ClientSnapshot::status).findFirst().orElse(null);
    }

    private static void await(Duration timeout, BooleanSupplier condition, String description)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(50);
        }
        throw new AssertionError("Timed out waiting for: " + description);
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError("Failed assertion: " + description);
    }
}
