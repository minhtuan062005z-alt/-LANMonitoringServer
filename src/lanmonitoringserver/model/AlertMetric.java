package lanmonitoringserver.model;

public enum AlertMetric {
    CPU("CPU Utilization"),
    RAM("RAM Utilization"),
    GPU("GPU Utilization"),
    DISK_ACTIVE("Disk Active Time"),
    DISK_CAPACITY("Disk Capacity Usage"),
    SYSTEM("System Notification");

    private final String displayName;
    AlertMetric(String displayName) { this.displayName = displayName; }
    public String displayName() { return displayName; }
}
