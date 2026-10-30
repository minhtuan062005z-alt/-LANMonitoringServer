package lanmonitoringserver.monitor.metrics;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import lanmonitoringserver.model.MeasurementStatus;
import lanmonitoringserver.util.CommandResult;
import lanmonitoringserver.util.CommandRunner;

/** Optional NVIDIA provider. Missing tools are reported as unsupported, never fabricated. */
public final class NvidiaSmiGpuMetricsProvider implements GpuMetricsProvider {
    private static final long MIB = 1024L * 1024L;
    private final CommandRunner runner;

    public NvidiaSmiGpuMetricsProvider() { this(new CommandRunner()); }
    NvidiaSmiGpuMetricsProvider(CommandRunner runner) { this.runner = runner; }

    @Override
    public GpuReading read() {
        CommandResult result = runner.run(List.of("nvidia-smi",
                "--query-gpu=utilization.gpu,memory.used,memory.total,temperature.gpu",
                "--format=csv,noheader,nounits"), Duration.ofSeconds(2), StandardCharsets.UTF_8);
        if (!result.successful()) {
            String reason = result.timedOut() ? "nvidia-smi timed out"
                    : "nvidia-smi is unavailable or no supported NVIDIA GPU was found";
            return new GpuReading(MeasurementStatus.UNSUPPORTED, null, null, null, null, reason);
        }
        try {
            String first = result.standardOutput().lines().filter(line -> !line.isBlank())
                    .findFirst().orElseThrow();
            String[] columns = first.split(",");
            if (columns.length < 4) throw new IllegalArgumentException("missing columns");
            return new GpuReading(MeasurementStatus.OK,
                    bounded(columns[0], 0, 100),
                    Math.round(parse(columns[1]) * MIB),
                    Math.round(parse(columns[2]) * MIB),
                    bounded(columns[3], -50, 200), "NVIDIA NVML via nvidia-smi");
        } catch (RuntimeException exception) {
            return new GpuReading(MeasurementStatus.ERROR, null, null, null, null,
                    "Invalid nvidia-smi response: " + exception.getMessage());
        }
    }

    private static double parse(String value) { return Double.parseDouble(value.trim()); }
    private static double bounded(String value, double min, double max) {
        double parsed = parse(value);
        if (!Double.isFinite(parsed) || parsed < min || parsed > max) {
            throw new IllegalArgumentException("value out of range");
        }
        return parsed;
    }
}
