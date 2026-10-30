package lanmonitoringserver.model;

public record SystemStats(double cpuPercent, double ramPercent,
        Double gpuPercent, Double diskPercent) {

    public SystemStats(double cpuPercent, double ramPercent) {
        this(cpuPercent, ramPercent, null, null);
    }
}
