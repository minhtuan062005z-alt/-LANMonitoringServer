package lanmonitoringserver.model;

/** Origin of a dashboard metric. Alert evaluation can use this provenance later. */
public enum MetricSource {
    TELEMETRY,
    HEARTBEAT,
    LEGACY,
    UNKNOWN
}
