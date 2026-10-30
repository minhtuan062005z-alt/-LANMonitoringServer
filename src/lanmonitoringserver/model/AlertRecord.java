package lanmonitoringserver.model;

/** Persisted current state of one alert lifecycle. */
public record AlertRecord(String id, String clientId, String hostname,
        AlertMetric metric, AlertSeverity severity, double value, double threshold,
        AlertStatus status, AlertDeliveryStatus deliveryStatus,
        long createdAt, Long acknowledgedAt, Long resolvedAt, long updatedAt,
        String deliveryRequestId, String templateName, String title, String content) {

    public AlertRecord {
        if (id == null || id.isBlank() || clientId == null || clientId.isBlank()) {
            throw new IllegalArgumentException("alert id and client id are required");
        }
        if (hostname == null || hostname.isBlank() || metric == null || severity == null
                || status == null || deliveryStatus == null) {
            throw new IllegalArgumentException("alert fields are required");
        }
        if (!Double.isFinite(value) || !Double.isFinite(threshold)) {
            throw new IllegalArgumentException("alert values must be finite");
        }
        if ((title == null) != (content == null)) {
            throw new IllegalArgumentException("notification title and content must both be present");
        }
    }

    /** Compatibility constructor for persisted/resource alerts created before templates existed. */
    public AlertRecord(String id, String clientId, String hostname,
            AlertMetric metric, AlertSeverity severity, double value, double threshold,
            AlertStatus status, AlertDeliveryStatus deliveryStatus,
            long createdAt, Long acknowledgedAt, Long resolvedAt, long updatedAt,
            String deliveryRequestId) {
        this(id, clientId, hostname, metric, severity, value, threshold, status,
                deliveryStatus, createdAt, acknowledgedAt, resolvedAt, updatedAt,
                deliveryRequestId, null, null, null);
    }

    public boolean manualNotification() { return templateName != null; }
    public boolean automaticRamAlert() { return metric == AlertMetric.RAM && !manualNotification(); }

    public AlertRecord withDelivery(AlertDeliveryStatus delivery, String requestId, long now) {
        return new AlertRecord(id, clientId, hostname, metric, severity, value, threshold,
                status, delivery, createdAt, acknowledgedAt, resolvedAt, updatedAt, requestId,
                templateName, title, content);
    }

    public AlertRecord acknowledge(long now) {
        if (status == AlertStatus.RESOLVED) return this;
        return new AlertRecord(id, clientId, hostname, metric, severity, value, threshold,
                AlertStatus.ACKNOWLEDGED, deliveryStatus, createdAt, now,
                resolvedAt, now, deliveryRequestId, templateName, title, content);
    }

    public AlertRecord resolve(long now) {
        if (status == AlertStatus.RESOLVED) return this;
        return new AlertRecord(id, clientId, hostname, metric, severity, value, threshold,
                AlertStatus.RESOLVED, deliveryStatus, createdAt, acknowledgedAt,
                now, now, deliveryRequestId, templateName, title, content);
    }

    public AlertRecord updateMeasurement(AlertSeverity newSeverity, double newValue,
            double newThreshold, long now) {
        return new AlertRecord(id, clientId, hostname, metric, newSeverity, newValue,
                newThreshold, status, deliveryStatus, createdAt, acknowledgedAt,
                resolvedAt, now, deliveryRequestId, templateName, title, content);
    }
}
