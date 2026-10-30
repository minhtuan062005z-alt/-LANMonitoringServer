package lanmonitoringserver.monitor.system;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lanmonitoringserver.model.SystemInformation;

final class SystemInfoBuilder {
    long collectedAt = System.currentTimeMillis();
    String collector;
    String hostname;
    String osName;
    String osVersion;
    String architecture;
    String manufacturer;
    String model;
    String firmwareName;
    String firmwareVersion;
    String javaVersion;
    String cpuModel;
    String cpuVendor;
    Integer physicalCores;
    Integer logicalProcessors;
    Long maxFrequencyMhz;
    Long totalMemoryBytes;
    Long availableMemoryBytes;
    final List<SystemInformation.GpuDetails> gpus = new ArrayList<>();
    final List<SystemInformation.StorageDetails> storage = new ArrayList<>();
    final List<SystemInformation.NetworkDetails> network = new ArrayList<>();
    final Map<String, String> unavailable = new LinkedHashMap<>();

    SystemInformation build() {
        return new SystemInformation(collectedAt, collector,
                new SystemInformation.SystemDetails(hostname, osName, osVersion,
                        architecture, manufacturer, model, firmwareName,
                        firmwareVersion, javaVersion),
                new SystemInformation.CpuDetails(cpuModel, cpuVendor, physicalCores,
                        logicalProcessors, maxFrequencyMhz),
                gpus, new SystemInformation.MemoryDetails(totalMemoryBytes, availableMemoryBytes),
                storage, network, unavailable);
    }
}
