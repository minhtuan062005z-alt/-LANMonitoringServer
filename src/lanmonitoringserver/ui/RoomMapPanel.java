package lanmonitoringserver.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseWheelEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import lanmonitoringserver.model.ClientSnapshot;
import lanmonitoringserver.model.ClientStatus;
import lanmonitoringserver.model.SpatialPosition;
import lanmonitoringserver.model.DistanceDataSource;

/** Draws only configured positions in a common coordinate reference frame. */
public final class RoomMapPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private static final int PADDING = 70;
    private List<ClientSnapshot> clients = List.of();
    private String selectedClientId;
    private double zoom = 1d;

    public RoomMapPanel() {
        setBackground(Color.WHITE);
        setPreferredSize(new Dimension(720, 480));
        addMouseWheelListener(this::zoomWithWheel);
    }

    public void setClients(List<ClientSnapshot> values, String selectedId) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> setClients(values, selectedId));
            return;
        }
        clients = List.copyOf(values == null ? List.of() : values);
        selectedClientId = selectedId;
        repaint();
    }

    public void selectClient(String clientId) {
        selectedClientId = clientId;
        repaint();
    }

    public void zoomIn() { setZoom(zoom * 1.25); }
    public void zoomOut() { setZoom(zoom / 1.25); }
    public void fitToView() { zoom = 1d; repaint(); }

    private void setZoom(double value) {
        zoom = Math.max(0.25d, Math.min(8d, value));
        repaint();
    }

    private void zoomWithWheel(MouseWheelEvent event) {
        if (event.getWheelRotation() < 0) zoomIn(); else zoomOut();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            SpatialPosition server = commonServerPosition();
            List<ClientSnapshot> positioned = positionedClients(server);
            if (server == null || positioned.isEmpty()) {
                drawEmpty(g, server == null
                        ? "Server position is not configured"
                        : "No Client position in Server reference frame");
                return;
            }
            Bounds bounds = bounds(server, positioned);
            Transform transform = transform(bounds);
            drawReference(g, server.referencePoint());
            drawGrid(g, bounds, transform);
            Point serverPoint = transform.point(server);
            for (ClientSnapshot client : positioned) {
                SpatialPosition position = client.serverClientDistance().clientPosition();
                Point clientPoint = transform.point(position);
                g.setStroke(new BasicStroke(client.clientId().equals(selectedClientId) ? 2.5f : 1.2f));
                g.setColor(client.clientId().equals(selectedClientId)
                        ? DashboardColors.PRIMARY : new Color(150, 160, 175));
                g.drawLine(serverPoint.x(), serverPoint.y(), clientPoint.x(), clientPoint.y());
                double meters = coordinateDistance(server, position);
                drawDistanceLabel(g, serverPoint, clientPoint, meters,
                        client.serverClientDistance().dataSource());
            }
            drawServer(g, serverPoint);
            for (ClientSnapshot client : positioned) {
                drawClient(g, transform.point(client.serverClientDistance().clientPosition()), client);
            }
        } finally {
            g.dispose();
        }
    }

    private SpatialPosition commonServerPosition() {
        return clients.stream().map(ClientSnapshot::serverClientDistance)
                .map(distance -> distance.serverPosition()).filter(java.util.Objects::nonNull)
                .findFirst().orElse(null);
    }

    private List<ClientSnapshot> positionedClients(SpatialPosition server) {
        if (server == null) return List.of();
        List<ClientSnapshot> result = new ArrayList<>();
        for (ClientSnapshot client : clients) {
            SpatialPosition position = client.serverClientDistance().clientPosition();
            if (position != null && client.serverClientDistance().status()
                    == lanmonitoringserver.model.ServerClientDistanceStatus.OK
                    && server.referencePoint().equals(position.referencePoint())) {
                result.add(client);
            }
        }
        return result;
    }

    private Bounds bounds(SpatialPosition server, List<ClientSnapshot> positioned) {
        double minX = server.xMeters(), maxX = server.xMeters();
        double minY = server.yMeters(), maxY = server.yMeters();
        for (ClientSnapshot client : positioned) {
            SpatialPosition position = client.serverClientDistance().clientPosition();
            minX = Math.min(minX, position.xMeters());
            maxX = Math.max(maxX, position.xMeters());
            minY = Math.min(minY, position.yMeters());
            maxY = Math.max(maxY, position.yMeters());
        }
        return new Bounds(minX, maxX, minY, maxY);
    }

    private Transform transform(Bounds bounds) {
        double width = Math.max(1d, bounds.maxX() - bounds.minX());
        double height = Math.max(1d, bounds.maxY() - bounds.minY());
        double availableWidth = Math.max(100, getWidth() - PADDING * 2d);
        double availableHeight = Math.max(100, getHeight() - PADDING * 2d);
        double fittedScale = Math.min(availableWidth / width, availableHeight / height);
        fittedScale = Math.min(140d, Math.max(8d, fittedScale));
        double scale = fittedScale * zoom;
        double centerX = (bounds.minX() + bounds.maxX()) / 2d;
        double centerY = (bounds.minY() + bounds.maxY()) / 2d;
        return new Transform(scale, getWidth() / 2d - centerX * scale,
                getHeight() / 2d + centerY * scale);
    }

    private void drawReference(Graphics2D g, String referencePoint) {
        g.setColor(DashboardColors.MUTED);
        g.drawString("Reference: " + referencePoint + " | Coordinates in metres", 16, 22);
    }

    private void drawGrid(Graphics2D g, Bounds bounds, Transform transform) {
        int startX = (int) Math.floor(bounds.minX()) - 1;
        int endX = (int) Math.ceil(bounds.maxX()) + 1;
        int startY = (int) Math.floor(bounds.minY()) - 1;
        int endY = (int) Math.ceil(bounds.maxY()) + 1;
        if (endX - startX > 100 || endY - startY > 100) return;
        g.setStroke(new BasicStroke(1f));
        g.setColor(new Color(235, 238, 243));
        for (int x = startX; x <= endX; x++) {
            Point top = transform.point(x, startY);
            Point bottom = transform.point(x, endY);
            g.drawLine(top.x(), top.y(), bottom.x(), bottom.y());
        }
        for (int y = startY; y <= endY; y++) {
            Point left = transform.point(startX, y);
            Point right = transform.point(endX, y);
            g.drawLine(left.x(), left.y(), right.x(), right.y());
        }
    }

    private void drawServer(Graphics2D g, Point point) {
        g.setColor(new Color(180, 45, 45));
        g.fillRoundRect(point.x() - 9, point.y() - 9, 18, 18, 5, 5);
        drawCentered(g, "SERVER", point.x(), point.y() - 15, new Color(120, 25, 25));
    }

    private void drawClient(Graphics2D g, Point point, ClientSnapshot client) {
        boolean selected = client.clientId().equals(selectedClientId);
        g.setColor(client.status() == ClientStatus.ONLINE
                ? DashboardColors.ONLINE : DashboardColors.OFFLINE);
        int radius = selected ? 10 : 8;
        g.fillOval(point.x() - radius, point.y() - radius, radius * 2, radius * 2);
        if (selected) {
            g.setColor(DashboardColors.PRIMARY);
            g.setStroke(new BasicStroke(2f));
            g.drawOval(point.x() - 14, point.y() - 14, 28, 28);
        }
        drawCentered(g, client.hostname(), point.x(), point.y() + 24, DashboardColors.TEXT);
    }

    private void drawDistanceLabel(Graphics2D g, Point server, Point client, double meters,
            DistanceDataSource source) {
        String suffix = source == DistanceDataSource.WIFI_FINGERPRINT
                ? "Wi-Fi fingerprint estimate" : "coords";
        String text = String.format(Locale.ROOT, "%.2f m (%s)", meters, suffix);
        int x = (server.x() + client.x()) / 2;
        int y = (server.y() + client.y()) / 2;
        FontMetrics metrics = g.getFontMetrics();
        int width = metrics.stringWidth(text) + 8;
        g.setColor(new Color(255, 255, 255, 225));
        g.fillRoundRect(x - width / 2, y - 13, width, 18, 7, 7);
        drawCentered(g, text, x, y, DashboardColors.TEXT);
    }

    private void drawEmpty(Graphics2D g, String message) {
        drawCentered(g, message, getWidth() / 2, getHeight() / 2, DashboardColors.MUTED);
    }

    private void drawCentered(Graphics2D g, String text, int x, int baseline, Color color) {
        g.setColor(color);
        g.drawString(text, x - g.getFontMetrics().stringWidth(text) / 2, baseline);
    }

    private static double coordinateDistance(SpatialPosition first, SpatialPosition second) {
        double dx = second.xMeters() - first.xMeters();
        double dy = second.yMeters() - first.yMeters();
        double squared = dx * dx + dy * dy;
        if (first.zMeters() != null && second.zMeters() != null) {
            double dz = second.zMeters() - first.zMeters();
            squared += dz * dz;
        }
        return Math.sqrt(squared);
    }

    int plottedClientCount() { return positionedClients(commonServerPosition()).size(); }
    String selectedClientId() { return selectedClientId; }
    double zoomFactor() { return zoom; }

    private record Bounds(double minX, double maxX, double minY, double maxY) {}
    private record Point(int x, int y) {}
    private record Transform(double scale, double offsetX, double offsetY) {
        Point point(SpatialPosition value) { return point(value.xMeters(), value.yMeters()); }
        Point point(double x, double y) {
            return new Point((int) Math.round(x * scale + offsetX),
                    (int) Math.round(offsetY - y * scale));
        }
    }
}
