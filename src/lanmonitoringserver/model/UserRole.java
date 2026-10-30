package lanmonitoringserver.model;

/** Server-side operator roles. Clients never choose or change this value. */
public enum UserRole {
    ADMIN,
    OPERATOR,
    VIEWER,
    CLIENT;

    public boolean canSendFiles() { return this == ADMIN || this == OPERATOR; }
    public boolean canManagePermissions() { return this == ADMIN; }
    public boolean canManageAnsible() { return this == ADMIN; }
    public boolean canManageAlerts() { return this == ADMIN; }
}
