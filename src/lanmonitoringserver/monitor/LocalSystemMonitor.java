package lanmonitoringserver.monitor;

import com.sun.management.OperatingSystemMXBean;
import java.io.File;
import java.lang.management.ManagementFactory;
import lanmonitoringserver.model.SystemStats;

public final class LocalSystemMonitor implements SystemMonitor {
    @Override
    public SystemStats read() {
        try {
            OperatingSystemMXBean bean = ManagementFactory.getPlatformMXBean(OperatingSystemMXBean.class);
            double cpu = clamp(bean.getCpuLoad() * 100.0);
            long total = bean.getTotalMemorySize();
            long free = bean.getFreeMemorySize();
            double ram = total <= 0 ? 0.0 : clamp(((double) (total - free) / total) * 100.0);
            return new SystemStats(cpu, ram, null, readDiskUsage());
        } catch (RuntimeException exception) {
            return new SystemStats(0.0, 0.0, null, readDiskUsage());
        }
    }

    private Double readDiskUsage() {
        long total = 0;
        long free = 0;
        File[] roots = File.listRoots();
        if (roots == null) return null;
        for (File root : roots) {
            long rootTotal = root.getTotalSpace();
            if (rootTotal > 0) {
                total += rootTotal;
                free += root.getUsableSpace();
            }
        }
        return total == 0 ? null : clamp(((double) (total - free) / total) * 100.0);
    }

    private double clamp(double value) {
        if (!Double.isFinite(value)) return 0.0;
        return Math.max(0.0, Math.min(100.0, value));
    }
}
