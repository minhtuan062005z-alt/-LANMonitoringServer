package lanmonitoringserver.model;

public record ReceivedFileInfo(String transferId, String fileName, String savedPath,
        long size, String sha256, boolean checksumVerified, long receivedAt) {}
