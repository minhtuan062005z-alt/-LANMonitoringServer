package lanmonitoringserver.monitor.metrics;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lanmonitoringserver.model.MeasurementStatus;
import lanmonitoringserver.model.TelemetrySample;
import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.GlobalMemory;
import oshi.hardware.HWDiskStore;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.hardware.NetworkIF;

/** Cross-platform realtime collector backed by OSHI and an optional GPU provider. */
public final class OshiMetricsCollector implements MetricsCollector {
    private final HardwareAbstractionLayer hardware;
    private final CentralProcessor processor;
    private final GpuMetricsProvider gpuProvider;
    private long[] previousCpuTicks;
    private long previousNanos;
    private final Map<String, DiskCounter> disks = new HashMap<>();
    private final Map<String, NetworkCounter> networks = new HashMap<>();

    public OshiMetricsCollector() {
        this(new SystemInfo().getHardware(), new NvidiaSmiGpuMetricsProvider());
    }

    OshiMetricsCollector(HardwareAbstractionLayer hardware, GpuMetricsProvider gpuProvider) {
        this.hardware = hardware;
        this.processor = hardware.getProcessor();
        this.gpuProvider = gpuProvider;
        this.previousCpuTicks = processor.getSystemCpuLoadTicks();
        this.previousNanos = System.nanoTime();
        seedCounters();
    }

    @Override
    public synchronized TelemetrySample collect() {
        long nowNanos = System.nanoTime();
        long elapsedMillis = Math.max(1L, (nowNanos - previousNanos) / 1_000_000L);
        previousNanos = nowNanos;

        long[] currentTicks = processor.getSystemCpuLoadTicks();
        double cpuUsage = percent(processor.getSystemCpuLoadBetweenTicks(previousCpuTicks, currentTicks));
        previousCpuTicks = currentTicks;
        Double frequency = averageFrequencyMhz(processor.getCurrentFreq());

        GlobalMemory memory = hardware.getMemory();
        long totalMemory = Math.max(0L, memory.getTotal());
        long freeMemory = Math.max(0L, Math.min(totalMemory, memory.getAvailable()));
        long usedMemory = Math.max(0L, totalMemory - freeMemory);
        double memoryUsage = totalMemory == 0 ? 0 : usedMemory * 100d / totalMemory;

        DiskReading disk = readDisks(elapsedMillis);
        NetworkReading network = readNetworks(elapsedMillis);
        GpuMetricsProvider.GpuReading gpu = gpuProvider.read();

        return new TelemetrySample(System.currentTimeMillis(), elapsedMillis,
                MeasurementStatus.OK, cpuUsage, processor.getPhysicalProcessorCount(),
                processor.getLogicalProcessorCount(), frequency,
                MeasurementStatus.OK, boundedPercent(memoryUsage), usedMemory, freeMemory, totalMemory,
                gpu.status(), gpu.utilizationPercent(), gpu.vramUsedBytes(), gpu.vramTotalBytes(),
                gpu.temperatureCelsius(), gpu.detail(),
                disk.status, disk.activePercent, disk.readBytesPerSecond,
                disk.writeBytesPerSecond, disk.capacityUsagePercent,
                network.status, network.uploadBytesPerSecond,
                network.downloadBytesPerSecond, network.interfaceName, network.detail);
    }

    private void seedCounters() {
        for (HWDiskStore disk : hardware.getDiskStores()) {
            disks.put(disk.getName(), new DiskCounter(disk.getReadBytes(), disk.getWriteBytes(),
                    disk.getTransferTime()));
        }
        for (NetworkIF network : hardware.getNetworkIFs(false)) {
            networks.put(network.getName(), new NetworkCounter(network.getBytesSent(), network.getBytesRecv()));
        }
    }

    private DiskReading readDisks(long elapsedMillis) {
        double readPerSecond = 0;
        double writePerSecond = 0;
        double maximumActive = 0;
        boolean activeSupported = false;
        List<HWDiskStore> stores = hardware.getDiskStores();
        for (HWDiskStore disk : stores) {
            disk.updateAttributes();
            DiskCounter old = disks.put(disk.getName(), new DiskCounter(disk.getReadBytes(),
                    disk.getWriteBytes(), disk.getTransferTime()));
            if (old == null) continue;
            readPerSecond += rate(disk.getReadBytes(), old.readBytes, elapsedMillis);
            writePerSecond += rate(disk.getWriteBytes(), old.writeBytes, elapsedMillis);
            long transferMillis = disk.getTransferTime();
            long busyDelta = delta(transferMillis, old.transferMillis);
            // OSHI uses zero when a platform/driver does not expose transfer time.
            // A non-zero cumulative counter is required before Active Time is claimed.
            if (transferMillis > 0 || old.transferMillis > 0) {
                maximumActive = Math.max(maximumActive, busyDelta * 100d / elapsedMillis);
                activeSupported = true;
            }
        }
        Double capacity = diskCapacityUsage();
        MeasurementStatus status = stores.isEmpty() || !activeSupported
                ? MeasurementStatus.UNSUPPORTED : MeasurementStatus.OK;
        return new DiskReading(status, activeSupported ? boundedPercent(maximumActive) : null,
                readPerSecond, writePerSecond, capacity);
    }

    private NetworkReading readNetworks(long elapsedMillis) {
        NetworkReading best = null;
        for (NetworkIF network : hardware.getNetworkIFs(false)) {
            network.updateAttributes();
            NetworkCounter old = networks.put(network.getName(),
                    new NetworkCounter(network.getBytesSent(), network.getBytesRecv()));
            if (old == null) continue;
            if (network.getIfOperStatus() != NetworkIF.IfOperStatus.UP) continue;
            double upload = rate(network.getBytesSent(), old.sentBytes, elapsedMillis);
            double download = rate(network.getBytesRecv(), old.receivedBytes, elapsedMillis);
            String name = network.getDisplayName() == null || network.getDisplayName().isBlank()
                    ? network.getName() : network.getDisplayName();
            NetworkReading candidate = new NetworkReading(MeasurementStatus.OK, upload, download,
                    name, network.getName());
            if (best == null || upload + download > best.uploadBytesPerSecond + best.downloadBytesPerSecond) {
                best = candidate;
            }
        }
        return best == null ? new NetworkReading(MeasurementStatus.UNSUPPORTED, 0, 0,
                "N/A", "No active network interface") : best;
    }

    private static Double diskCapacityUsage() {
        long total = 0;
        long free = 0;
        File[] roots = File.listRoots();
        if (roots == null) return null;
        for (File root : roots) {
            long rootTotal = root.getTotalSpace();
            if (rootTotal <= 0) continue;
            total += rootTotal;
            free += Math.max(0L, root.getUsableSpace());
        }
        return total <= 0 ? null : boundedPercent((total - free) * 100d / total);
    }

    private static Double averageFrequencyMhz(long[] frequencies) {
        if (frequencies == null) return null;
        long sum = 0;
        int count = 0;
        for (long frequency : frequencies) {
            if (frequency > 0) { sum += frequency; count++; }
        }
        return count == 0 ? null : sum / (double) count / 1_000_000d;
    }

    private static double rate(long current, long previous, long elapsedMillis) {
        return delta(current, previous) * 1000d / Math.max(1L, elapsedMillis);
    }

    private static long delta(long current, long previous) {
        return current >= previous ? current - previous : 0;
    }

    private static double percent(double fraction) { return boundedPercent(fraction * 100d); }
    private static double boundedPercent(double value) {
        return Double.isFinite(value) ? Math.max(0d, Math.min(100d, value)) : 0d;
    }

    @Override public String collectorName() { return "OSHI 7.7.2"; }

    private record DiskCounter(long readBytes, long writeBytes, long transferMillis) {}
    private record NetworkCounter(long sentBytes, long receivedBytes) {}
    private record DiskReading(MeasurementStatus status, Double activePercent,
            double readBytesPerSecond, double writeBytesPerSecond, Double capacityUsagePercent) {}
    private record NetworkReading(MeasurementStatus status, double uploadBytesPerSecond,
            double downloadBytesPerSecond, String interfaceName, String detail) {}
}
