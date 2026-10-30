package lanmonitoringserver.model;

/** Authoritative per-client permissions assigned by the Server. */
public record ClientPermissions(boolean receiveFiles, boolean receiveAlerts,
        boolean exportSystemInformation) {
    public static ClientPermissions defaults() { return new ClientPermissions(false, true, true); }
}
