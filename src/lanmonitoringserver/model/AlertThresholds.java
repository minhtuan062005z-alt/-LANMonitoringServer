package lanmonitoringserver.model;

/** Configuration seed for Phase 5. This class does not evaluate or dispatch alerts. */
public record AlertThresholds(double cpuPercent, double gpuPercent,
        double diskActivePercent) {
    public static final AlertThresholds DEFAULT = new AlertThresholds(80d, 80d, 80d);

    public AlertThresholds {
        validate(cpuPercent, "cpuPercent");
        validate(gpuPercent, "gpuPercent");
        validate(diskActivePercent, "diskActivePercent");
    }

    public boolean cpuExceeded(double value) { return value > cpuPercent; }
    public boolean gpuExceeded(double value) { return value > gpuPercent; }
    public boolean diskActiveExceeded(double value) { return value > diskActivePercent; }

    private static void validate(double value, String name) {
        if (!Double.isFinite(value) || value < 0 || value > 100) {
            throw new IllegalArgumentException(name + " must be between 0 and 100");
        }
    }
}
