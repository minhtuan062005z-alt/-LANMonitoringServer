package lanmonitoringserver.model;

/** Configurable rule. Duration, hysteresis and cooldown are expressed in milliseconds. */
public record AlertRule(AlertMetric metric, boolean enabled,
        double warningThreshold, double criticalThreshold,
        long durationMillis, double hysteresisPercent, long cooldownMillis) {

    public AlertRule {
        if (metric == null) throw new IllegalArgumentException("metric must not be null");
        percentage(warningThreshold, "warningThreshold");
        percentage(criticalThreshold, "criticalThreshold");
        percentage(hysteresisPercent, "hysteresisPercent");
        if (criticalThreshold < warningThreshold) {
            throw new IllegalArgumentException("criticalThreshold must be >= warningThreshold");
        }
        if (durationMillis < 0 || cooldownMillis < 0) {
            throw new IllegalArgumentException("durations must not be negative");
        }
    }

    public static AlertRule defaults(AlertMetric metric) {
        if (metric == AlertMetric.RAM) {
            return new AlertRule(metric, true, 90d, 90d, 60_000L, 0d, 60_000L);
        }
        if (metric == AlertMetric.SYSTEM) {
            return new AlertRule(metric, false, 100d, 100d, 0L, 0d, 0L);
        }
        return new AlertRule(metric, true, 80d, 90d, 5_000L, 5d, 60_000L);
    }

    private static void percentage(double value, String name) {
        if (!Double.isFinite(value) || value < 0 || value > 100) {
            throw new IllegalArgumentException(name + " must be between 0 and 100");
        }
    }
}
