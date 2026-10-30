package lanmonitoringserver.model;

public record ClientSnapshot(String clientId, String hostname, String ipAddress,
        String operatingSystem, ClientStatus status, Long responseTimeMillis,
        double cpuPercent, double ramPercent, Double gpuPercent,
        Double diskPercent, long lastSeenTime, SystemInformation systemInformation,
        ClientMetrics metrics, AlertSeverity activeAlertSeverity, int activeAlertCount,
        ServerClientDistance serverClientDistance) {

    public ClientSnapshot {
        if (metrics == null) {
            metrics = ClientMetrics.legacy(cpuPercent, ramPercent, gpuPercent,
                    diskPercent, lastSeenTime);
        }
        if (activeAlertCount < 0) throw new IllegalArgumentException("activeAlertCount must not be negative");
        if (serverClientDistance == null) {
            serverClientDistance = ServerClientDistance.unavailable(clientId, null, null);
        } else if (!serverClientDistance.clientId().equals(clientId)) {
            throw new IllegalArgumentException("Distance belongs to another Client ID");
        }
    }

    /** Compatibility constructor for Phase 1-4 callers; diskPercent means capacity usage. */
    public ClientSnapshot(String clientId, String hostname, String ipAddress,
            String operatingSystem, ClientStatus status, Long responseTimeMillis,
            double cpuPercent, double ramPercent, Double gpuPercent,
            Double diskPercent, long lastSeenTime, SystemInformation systemInformation) {
        this(clientId, hostname, ipAddress, operatingSystem, status, responseTimeMillis,
                cpuPercent, ramPercent, gpuPercent, diskPercent, lastSeenTime,
                systemInformation, null, null, 0, null);
    }

    public ClientSnapshot(String clientId, String hostname, String ipAddress,
            String operatingSystem, ClientStatus status, Long responseTimeMillis,
            double cpuPercent, double ramPercent, Double gpuPercent,
            Double diskPercent, long lastSeenTime, SystemInformation systemInformation,
            ClientMetrics metrics) {
        this(clientId, hostname, ipAddress, operatingSystem, status, responseTimeMillis,
                cpuPercent, ramPercent, gpuPercent, diskPercent, lastSeenTime,
                systemInformation, metrics, null, 0, null);
    }

    public ClientSnapshot(String clientId, String hostname, String ipAddress,
            String operatingSystem, ClientStatus status, Long responseTimeMillis,
            double cpuPercent, double ramPercent, Double gpuPercent,
            Double diskPercent, long lastSeenTime, SystemInformation systemInformation,
            ClientMetrics metrics, AlertSeverity activeAlertSeverity, int activeAlertCount) {
        this(clientId, hostname, ipAddress, operatingSystem, status, responseTimeMillis,
                cpuPercent, ramPercent, gpuPercent, diskPercent, lastSeenTime,
                systemInformation, metrics, activeAlertSeverity, activeAlertCount, null);
    }

    public Double diskActivePercent() { return metrics.diskActive().value(); }
    public Double diskCapacityPercent() { return metrics.diskCapacity().value(); }
}
