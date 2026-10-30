package lanmonitoringserver.model;

/** Fixed, non-executable administrative notification templates. */
public enum NotificationTemplate {
    RAM_HIGH("RAM cao", AlertMetric.RAM, AlertSeverity.CRITICAL,
            "Cảnh báo sử dụng RAM cao",
            "Máy %s đang có mức sử dụng RAM %s. Vui lòng đóng ứng dụng không cần thiết và kiểm tra thiết bị."),
    CPU_HIGH("CPU cao", AlertMetric.CPU, AlertSeverity.CRITICAL,
            "Cảnh báo sử dụng CPU cao",
            "Máy %s đang có mức sử dụng CPU %s. Vui lòng kiểm tra các ứng dụng đang chạy."),
    GPU_HIGH("GPU cao", AlertMetric.GPU, AlertSeverity.CRITICAL,
            "Cảnh báo sử dụng GPU cao",
            "Máy %s đang có mức sử dụng GPU %s. Vui lòng kiểm tra tác vụ đồ họa đang chạy."),
    DISK_HIGH("Disk cao", AlertMetric.DISK_CAPACITY, AlertSeverity.WARNING,
            "Cảnh báo dung lượng ổ đĩa cao",
            "Máy %s đang có mức sử dụng ổ đĩa %s. Vui lòng kiểm tra và giải phóng dung lượng nếu cần."),
    SYSTEM_MAINTENANCE("Bảo trì hệ thống", AlertMetric.SYSTEM, AlertSeverity.WARNING,
            "Thông báo bảo trì hệ thống",
            "Máy %s chuẩn bị được bảo trì hệ thống. Vui lòng lưu công việc đang thực hiện."),
    DEVICE_CHECK("Kiểm tra thiết bị", AlertMetric.SYSTEM, AlertSeverity.WARNING,
            "Yêu cầu kiểm tra thiết bị",
            "Vui lòng kiểm tra tình trạng phần cứng và kết nối mạng của máy %s, sau đó báo lại quản trị viên."),
    GENERAL("Thông báo chung", AlertMetric.SYSTEM, AlertSeverity.WARNING,
            "Thông báo từ quản trị viên",
            "Máy %s có thông báo quản trị mới. Vui lòng xác nhận đã nhận thông báo.");

    private final String displayName;
    private final AlertMetric metric;
    private final AlertSeverity severity;
    private final String title;
    private final String bodyPattern;

    NotificationTemplate(String displayName, AlertMetric metric, AlertSeverity severity,
            String title, String bodyPattern) {
        this.displayName = displayName;
        this.metric = metric;
        this.severity = severity;
        this.title = title;
        this.bodyPattern = bodyPattern;
    }

    public String displayName() { return displayName; }
    public AlertMetric metric() { return metric; }
    public AlertSeverity severity() { return severity; }
    public String title() { return title; }

    public String content(String hostname, MetricReading reading) {
        String safeHost = hostname == null || hostname.isBlank() ? "không xác định" : hostname;
        if (metric == AlertMetric.SYSTEM) return String.format(bodyPattern, safeHost);
        String metricText = reading != null && reading.isCurrentValue()
                ? String.format(java.util.Locale.ROOT, "%.1f%%", reading.value())
                : "không khả dụng (Server không tự tạo số liệu)";
        return String.format(bodyPattern, safeHost, metricText);
    }

    @Override public String toString() { return displayName; }
}
