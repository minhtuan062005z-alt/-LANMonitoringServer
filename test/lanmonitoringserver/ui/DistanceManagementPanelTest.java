package lanmonitoringserver.ui;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import lanmonitoringserver.model.ClientSnapshot;
import lanmonitoringserver.model.ClientStatus;
import lanmonitoringserver.model.DistanceDataSource;
import lanmonitoringserver.model.ServerClientDistance;
import lanmonitoringserver.model.ServerClientDistanceMethod;
import lanmonitoringserver.model.ServerClientDistanceStatus;
import lanmonitoringserver.model.SpatialPosition;

public final class DistanceManagementPanelTest {
    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        long now = System.currentTimeMillis();
        SpatialPosition server = new SpatialPosition("lab-map", 1, 1, null, now);
        ClientSnapshot first = positioned("client-1", "ALPHA", ClientStatus.ONLINE,
                server, new SpatialPosition("lab-map", 4, 5, null, now + 1), now + 1);
        ClientSnapshot second = positioned("client-2", "BETA", ClientStatus.OFFLINE,
                server, new SpatialPosition("lab-map", 7, 9, null, now + 2), now + 2);
        ClientSnapshot third = positioned("client-3", "GAMMA", ClientStatus.ONLINE,
                server, new SpatialPosition("lab-map", -2, 2, null, now + 3), now + 3);
        ClientSnapshot unavailable = unavailable("client-4", "NO-POSITION", server, now);
        ClientSnapshot stale = stale("client-5", "STALE-WIFI", server,
                new SpatialPosition("lab-map", 12, 12, null, now - 60_000), now - 60_000);
        List<ClientSnapshot> values = List.of(first, second, third, unavailable, stale);

        AtomicReference<ServerClientDistanceManagementPanel> panelRef = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> panelRef.set(new ServerClientDistanceManagementPanel()));
        ServerClientDistanceManagementPanel panel = panelRef.get();

        Thread producer = new Thread(() -> panel.setClients(values, "client-2"),
                "distance-ui-background-update");
        producer.start();
        producer.join(1_000);
        check(!producer.isAlive(), "background update returns without blocking on Swing");
        SwingUtilities.invokeAndWait(() -> {
            check(panel.rowCount() == 5, "distance table shows all Clients");
            check(panel.plottedClientCount() == 3,
                    "map plots only three Clients with configured common coordinates");
            check("client-2".equals(panel.selectedClientId()), "Card-selected Client is highlighted");
            int offlineRow = rowOf(panel, "client-2");
            check(panel.cell(offlineRow, 5) == ServerClientDistanceStatus.OK,
                    "offline connection does not change position data status");
            int unavailableRow = rowOf(panel, "client-4");
            check("NOT_AVAILABLE".equals(panel.cell(unavailableRow, 3)),
                    "Client without position remains unavailable");
            int staleRow = rowOf(panel, "client-5");
            check("STALE".equals(panel.cell(staleRow, 3))
                            && panel.cell(staleRow, 5) == ServerClientDistanceStatus.STALE,
                    "stale Wi-Fi distance is not rendered as realtime metres");
            double initialZoom = panel.zoomFactor();
            panel.selectClient("client-1");
            check("client-1".equals(panel.selectedClientId()), "table/map selection is synchronized");
            check(initialZoom == 1d, "map starts fit-to-view");
        });

        RoomMapPanel map = new RoomMapPanel();
        SwingUtilities.invokeAndWait(() -> {
            map.setSize(800, 500);
            map.setClients(values, "client-3");
            map.zoomIn();
            check(map.zoomFactor() > 1d, "zoom in changes map scale");
            map.fitToView();
            check(map.zoomFactor() == 1d, "fit-to-view resets scale");
            BufferedImage image = new BufferedImage(800, 500, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = image.createGraphics();
            try { map.paint(graphics); } finally { graphics.dispose(); }
            check(map.plottedClientCount() == 3, "headless map paint preserves real positions");
        });

        AtomicReference<ClientSnapshot> opened = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            ClientCard card = new ClientCard(first);
            card.setOpenDistanceMap(opened::set);
            card.clickMapButton();
        });
        check(opened.get() == first, "Client Card opens map for its own Client");
        SwingUtilities.invokeAndWait(() -> {
            ClientCard staleCard = new ClientCard(stale);
            check(staleCard.distanceDisplayText().contains("STALE")
                            && !staleCard.distanceDisplayText().contains("m ("),
                    "Client Card labels stale fingerprint without realtime metres");
            ServerClientDistancePanel detail = new ServerClientDistancePanel();
            detail.setDistance(stale.serverClientDistance());
            check(detail.distanceText().contains("STALE") && detail.statusText().contains("STALE"),
                    "distance detail exposes stale data status");
        });

        SpatialPosition moved = new SpatialPosition("lab-map", 10, 1, null, now + 10);
        ClientSnapshot movedClient = positioned("client-1", "ALPHA", ClientStatus.ONLINE,
                server, moved, now + 10);
        SwingUtilities.invokeAndWait(() -> panel.setClients(
                List.of(movedClient, second, third, unavailable, stale), "client-1"));
        SwingUtilities.invokeAndWait(() -> {
            int movedRow = rowOf(panel, "client-1");
            check("9.00 m".equals(panel.cell(movedRow, 3)),
                    "manual position update recalculates table distance");
        });
        System.out.println("DistanceManagementPanelTest: PASS");
    }

    private static ClientSnapshot positioned(String id, String hostname, ClientStatus status,
            SpatialPosition server, SpatialPosition client, long updatedAt) {
        double dx = client.xMeters() - server.xMeters();
        double dy = client.yMeters() - server.yMeters();
        ServerClientDistance distance = new ServerClientDistance(id, Math.hypot(dx, dy),
                ServerClientDistanceMethod.POSITION_BASED_ESTIMATION,
                DistanceDataSource.ADMIN_COORDINATES, ServerClientDistanceStatus.OK,
                null, updatedAt, server.referencePoint(), server, client);
        return snapshot(id, hostname, status, updatedAt, distance);
    }

    private static ClientSnapshot unavailable(String id, String hostname,
            SpatialPosition server, long now) {
        return snapshot(id, hostname, ClientStatus.ONLINE, now,
                ServerClientDistance.unavailable(id, server, null));
    }

    private static ClientSnapshot stale(String id, String hostname, SpatialPosition server,
            SpatialPosition historicalClient, long updatedAt) {
        ServerClientDistance distance = new ServerClientDistance(id, null,
                ServerClientDistanceMethod.POSITION_BASED_ESTIMATION,
                DistanceDataSource.WIFI_FINGERPRINT, ServerClientDistanceStatus.STALE,
                2.0, updatedAt, server.referencePoint(), server, historicalClient);
        return snapshot(id, hostname, ClientStatus.OFFLINE, updatedAt, distance);
    }

    private static ClientSnapshot snapshot(String id, String hostname, ClientStatus status,
            long now, ServerClientDistance distance) {
        return new ClientSnapshot(id, hostname, "192.0.2." + id.charAt(id.length() - 1),
                "Test OS", status, null, 0, 0, null, null, now, null,
                null, null, 0, distance);
    }

    private static int rowOf(ServerClientDistanceManagementPanel panel, String clientId) {
        for (int row = 0; row < panel.rowCount(); row++) {
            if (clientId.equals(panel.cell(row, 0))) return row;
        }
        throw new AssertionError("Missing Client row: " + clientId);
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError("Failed assertion: " + description);
    }
}
