package lanmonitoringserver.model;

/** Scientific provenance of a Server-to-Client physical distance value. */
public enum ServerClientDistanceMethod {
    DIRECT_MEASUREMENT,
    POSITION_BASED_ESTIMATION,
    MANUAL_MEASUREMENT,
    NOT_AVAILABLE
}
