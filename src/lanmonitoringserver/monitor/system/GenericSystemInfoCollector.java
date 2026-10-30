package lanmonitoringserver.monitor.system;

import lanmonitoringserver.model.SystemInformation;

/** Portable Java-only fallback for operating systems without a specialized collector. */
public final class GenericSystemInfoCollector implements SystemInfoCollector {
    @Override
    public SystemInformation collect() {
        SystemInfoBuilder builder = JavaSystemProbe.baseline(collectorName());
        builder.cpuModel = System.getProperty("os.arch", "Unknown architecture");
        builder.unavailable.put("system.manufacturer", "N/A - Not Supported by portable Java API");
        builder.unavailable.put("system.model", "N/A - Not Supported by portable Java API");
        builder.unavailable.put("system.firmware", "N/A - Not Supported by portable Java API");
        builder.unavailable.put("cpu.vendor", "N/A - Not Supported by portable Java API");
        builder.unavailable.put("cpu.physicalCores", "N/A - Not Supported by portable Java API");
        builder.unavailable.put("cpu.frequency", "N/A - Not Supported by portable Java API");
        builder.unavailable.put("gpu", "N/A - Not Supported by portable Java API");
        return builder.build();
    }

    @Override
    public String collectorName() {
        return "Java portable collector";
    }
}
