package lanmonitoringserver.model;

/** Validated alert payload delivered from Server to one Client. */
public record AlertNotification(String alertId, String hostname, AlertMetric metric,
        AlertSeverity severity, double value, double threshold, long occurredAt,
        String notificationId, String title, String content, boolean mandatory) {
    public AlertNotification(String alertId, String hostname, AlertMetric metric,
            AlertSeverity severity, double value, double threshold, long occurredAt) {
        this(alertId, hostname, metric, severity, value, threshold, occurredAt,
                alertId, null, null, false);
    }
    public boolean templated() { return title != null && content != null; }
}
