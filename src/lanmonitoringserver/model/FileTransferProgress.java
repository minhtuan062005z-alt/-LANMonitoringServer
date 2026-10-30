package lanmonitoringserver.model;

/** Immutable per-client transfer state for UI, history and audit reporting. */
public record FileTransferProgress(String transferId, String fileId, String clientId,
        String hostname, String fileName, long fileSize, String sha256,
        long bytesTransferred, double bytesPerSecond, FileTransferStatus status,
        String detail, String savedPath, long startedAt, long updatedAt) {

    public FileTransferProgress {
        if (transferId == null || transferId.isBlank() || fileId == null || fileId.isBlank()
                || clientId == null || clientId.isBlank() || fileName == null || fileName.isBlank()
                || sha256 == null || sha256.length() != 64 || status == null) {
            throw new IllegalArgumentException("Invalid file transfer state");
        }
        if (fileSize < 0 || bytesTransferred < 0 || bytesTransferred > fileSize
                || !Double.isFinite(bytesPerSecond) || bytesPerSecond < 0) {
            throw new IllegalArgumentException("Invalid file transfer measurements");
        }
    }

    public int percent() {
        return fileSize == 0 ? 100 : (int) Math.min(100, bytesTransferred * 100 / fileSize);
    }

    public boolean terminal() {
        return status == FileTransferStatus.SUCCESS || status == FileTransferStatus.FAILED
                || status == FileTransferStatus.CANCELLED;
    }
}
