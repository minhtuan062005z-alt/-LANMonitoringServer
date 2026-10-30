package lanmonitoringserver.model;

import java.util.List;
import java.util.Map;

/** Static, structured system inventory. Null fields are explained by unavailableReasons. */
public record SystemInformation(
        long collectedAt,
        String collector,
        SystemDetails system,
        CpuDetails cpu,
        List<GpuDetails> gpus,
        MemoryDetails memory,
        List<StorageDetails> storage,
        List<NetworkDetails> network,
        Map<String, String> unavailableReasons) {

    public SystemInformation {
        gpus = List.copyOf(gpus == null ? List.of() : gpus);
        storage = List.copyOf(storage == null ? List.of() : storage);
        network = List.copyOf(network == null ? List.of() : network);
        unavailableReasons = Map.copyOf(unavailableReasons == null ? Map.of() : unavailableReasons);
    }

    public record SystemDetails(String hostname, String osName, String osVersion,
            String architecture, String manufacturer, String model,
            String firmwareName, String firmwareVersion, String javaVersion) {}

    public record CpuDetails(String model, String vendor, Integer physicalCores,
            Integer logicalProcessors, Long maxFrequencyMhz) {}

    public record GpuDetails(String name, String vendor, String driver,
            Long vramBytes, String category) {}

    public record MemoryDetails(Long totalBytes, Long availableBytes) {}

    public record StorageDetails(String device, String model, String mountPoint,
            String fileSystem, Long totalBytes, Long usedBytes, Long freeBytes,
            String mediaType) {}

    public record NetworkDetails(String name, String displayName, String connectionType,
            String macAddress, List<String> ipv4Addresses, List<String> ipv6Addresses) {
        public NetworkDetails {
            ipv4Addresses = List.copyOf(ipv4Addresses == null ? List.of() : ipv4Addresses);
            ipv6Addresses = List.copyOf(ipv6Addresses == null ? List.of() : ipv6Addresses);
        }
    }
}
