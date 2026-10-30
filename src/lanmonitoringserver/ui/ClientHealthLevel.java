package lanmonitoringserver.ui;

import java.awt.Color;
import lanmonitoringserver.model.ClientSnapshot;
import lanmonitoringserver.model.ClientStatus;
import lanmonitoringserver.model.MetricReading;

public enum ClientHealthLevel {
    ONLINE("Online", DashboardColors.ONLINE),
    OFFLINE("Offline", DashboardColors.OFFLINE),
    WARNING("Warning", DashboardColors.WARNING),
    CRITICAL("Critical", DashboardColors.CRITICAL);

    private final String displayName;
    private final Color color;

    ClientHealthLevel(String displayName, Color color) {
        this.displayName = displayName;
        this.color = color;
    }

    public String displayName() { return displayName; }
    public Color color() { return color; }

    public static ClientHealthLevel from(ClientSnapshot client) {
        if (client.activeAlertSeverity() == lanmonitoringserver.model.AlertSeverity.CRITICAL) return CRITICAL;
        if (client.activeAlertSeverity() == lanmonitoringserver.model.AlertSeverity.WARNING) return WARNING;
        if (client.status() != ClientStatus.ONLINE) return OFFLINE;
        double maximum = 0;
        maximum = maximum(maximum, client.metrics().cpuUsage());
        maximum = maximum(maximum, client.metrics().ramUsage());
        maximum = maximum(maximum, client.metrics().gpuUsage());
        maximum = maximum(maximum, client.metrics().diskActive());
        // Preserve the Phase 2 health-color behavior for nearly-full disks.
        maximum = maximum(maximum, client.metrics().diskCapacity());
        long rtt = client.responseTimeMillis() == null ? 0 : client.responseTimeMillis();
        if (maximum >= 90 || rtt >= 1_000) return CRITICAL;
        if (maximum >= 75 || rtt >= 500) return WARNING;
        return ONLINE;
    }

    private static double maximum(double current, MetricReading reading) {
        return reading.isCurrentValue() ? Math.max(current, reading.value()) : current;
    }
}
