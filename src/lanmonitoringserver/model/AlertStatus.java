package lanmonitoringserver.model;

public enum AlertStatus {
    ACTIVE,
    ACKNOWLEDGED,
    RESOLVED;

    public boolean isOpen() { return this != RESOLVED; }
}
