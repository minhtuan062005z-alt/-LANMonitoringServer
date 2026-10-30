package lanmonitoringserver.model;

/** Availability of one telemetry measurement group. */
public enum MeasurementStatus {
    OK,
    NOT_AVAILABLE,
    UNSUPPORTED,
    ERROR,
    STALE
}
