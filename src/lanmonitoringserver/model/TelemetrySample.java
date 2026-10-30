package lanmonitoringserver.model;

/** One bounded, real-time performance sample produced by a client. */
public record TelemetrySample(
        long timestamp,
        long intervalMillis,
        MeasurementStatus cpuStatus,
        double cpuUsagePercent,
        int physicalCores,
        int logicalProcessors,
        Double cpuFrequencyMhz,
        MeasurementStatus memoryStatus,
        double memoryUsagePercent,
        long memoryUsedBytes,
        long memoryFreeBytes,
        long memoryTotalBytes,
        MeasurementStatus gpuStatus,
        Double gpuUsagePercent,
        Long vramUsedBytes,
        Long vramTotalBytes,
        Double gpuTemperatureCelsius,
        String gpuDetail,
        MeasurementStatus diskStatus,
        Double diskActivePercent,
        double diskReadBytesPerSecond,
        double diskWriteBytesPerSecond,
        Double diskCapacityUsagePercent,
        MeasurementStatus networkStatus,
        double networkUploadBytesPerSecond,
        double networkDownloadBytesPerSecond,
        String networkInterface,
        String networkDetail) {

    public boolean isStale(long now) {
        long tolerance = Math.max(3_000L, intervalMillis * 3L);
        return timestamp <= 0 || now - timestamp > tolerance;
    }
}
