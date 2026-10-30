package lanmonitoringserver.service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lanmonitoringserver.model.ReceivedFileInfo;

/** Streaming receiver constrained to one application-owned directory. */
public final class FileReceiver implements AutoCloseable {
    public static final long DEFAULT_MAX_FILE_BYTES = 100L * 1024 * 1024;
    public static final int MAX_CHUNK_BYTES = 64 * 1024;
    private static final int RECEIVED_HISTORY_LIMIT = 1_000;

    private final Path receiveDirectory;
    private final long maxFileBytes;
    private final java.util.function.LongSupplier usableSpaceOverride;
    private final Map<String, Incoming> active = new ConcurrentHashMap<>();
    private final List<ReceivedFileInfo> received = new ArrayList<>();

    public FileReceiver(Path receiveDirectory, long maxFileBytes) {
        this(receiveDirectory, maxFileBytes, null);
    }

    /** Third argument is a deterministic disk-space probe used by tests; production passes null. */
    public FileReceiver(Path receiveDirectory, long maxFileBytes,
            java.util.function.LongSupplier usableSpaceOverride) {
        this.receiveDirectory = receiveDirectory.toAbsolutePath().normalize();
        if (maxFileBytes < 1) throw new IllegalArgumentException("maxFileBytes must be positive");
        this.maxFileBytes = maxFileBytes;
        this.usableSpaceOverride = usableSpaceOverride;
    }

    public static FileReceiver defaultReceiver() {
        String configured = System.getProperty("lanmonitoring.receive.dir", "").trim();
        Path directory = configured.isEmpty()
                ? Path.of(System.getProperty("user.home"), "LANMonitoringServer", "received-files")
                : Path.of(configured);
        long maximum = Long.getLong("lanmonitoring.file.max.bytes", DEFAULT_MAX_FILE_BYTES);
        return new FileReceiver(directory, maximum);
    }

    public Path receiveDirectory() { return receiveDirectory; }

    public synchronized OfferResult begin(String transferId, String fileName, long size,
            String sha256, boolean permitted) {
        if (!permitted) return OfferResult.reject("PERMISSION_DENIED");
        if (!safeIdentifier(transferId) || !safeFileName(fileName)) return OfferResult.reject("INVALID_NAME");
        if (size < 0 || size > maxFileBytes) return OfferResult.reject("SIZE_LIMIT");
        if (sha256 == null || !sha256.matches("[0-9a-fA-F]{64}")) return OfferResult.reject("INVALID_CHECKSUM");
        if (active.containsKey(transferId)) return OfferResult.reject("DUPLICATE_TRANSFER");
        try {
            Files.createDirectories(receiveDirectory);
            if (Files.isSymbolicLink(receiveDirectory)) {
                return OfferResult.reject("SYMLINK_RECEIVE_DIRECTORY");
            }
            Path realDirectory = receiveDirectory.toRealPath();
            Path noFollowDirectory = receiveDirectory.toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (!realDirectory.equals(noFollowDirectory)) {
                return OfferResult.reject("SYMLINK_RECEIVE_DIRECTORY");
            }
            FileStore store = Files.getFileStore(realDirectory);
            long usableSpace = usableSpaceOverride == null
                    ? store.getUsableSpace() : usableSpaceOverride.getAsLong();
            if (usableSpace < size) return OfferResult.reject("INSUFFICIENT_SPACE");
            Path target = uniqueTarget(realDirectory, fileName);
            Path temporary = realDirectory.resolve("." + transferId + ".part").normalize();
            requireInside(realDirectory, temporary);
            OutputStream output = Files.newOutputStream(temporary, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE);
            active.put(transferId, new Incoming(fileName, target, temporary, size,
                    sha256.toLowerCase(java.util.Locale.ROOT), output, digest()));
            return new OfferResult(true, "ACCEPTED", target.toString());
        } catch (IOException | RuntimeException exception) {
            return OfferResult.reject("IO_ERROR: " + safeMessage(exception));
        }
    }

    public synchronized long acceptChunk(String transferId, long offset, String base64) throws IOException {
        Incoming incoming = requireActive(transferId);
        if (offset != incoming.received) throw new IOException("Unexpected chunk offset");
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(base64); }
        catch (IllegalArgumentException exception) { throw new IOException("Invalid Base64 chunk", exception); }
        if (bytes.length == 0 || bytes.length > MAX_CHUNK_BYTES
                || incoming.received + bytes.length > incoming.size) {
            throw new IOException("Invalid chunk size");
        }
        incoming.output.write(bytes);
        incoming.digest.update(bytes);
        incoming.received += bytes.length;
        return incoming.received;
    }

    public synchronized ReceivedFileInfo finish(String transferId) throws IOException {
        Incoming incoming = requireActive(transferId);
        active.remove(transferId);
        closeQuietly(incoming.output);
        if (incoming.received != incoming.size) {
            Files.deleteIfExists(incoming.temporary);
            throw new IOException("Received byte count does not match file size");
        }
        String actual = hex(incoming.digest.digest());
        if (!actual.equals(incoming.sha256)) {
            Files.deleteIfExists(incoming.temporary);
            throw new IOException("SHA-256 checksum mismatch");
        }
        try {
            Files.move(incoming.temporary, incoming.target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(incoming.temporary, incoming.target);
        }
        ReceivedFileInfo result = new ReceivedFileInfo(transferId, incoming.fileName,
                incoming.target.toString(), incoming.size, actual, true, System.currentTimeMillis());
        received.add(result);
        if (received.size() > RECEIVED_HISTORY_LIMIT) {
            received.subList(0, received.size() - RECEIVED_HISTORY_LIMIT).clear();
        }
        return result;
    }

    public synchronized boolean cancel(String transferId) {
        Incoming incoming = active.remove(transferId);
        if (incoming == null) return false;
        closeQuietly(incoming.output);
        try { Files.deleteIfExists(incoming.temporary); } catch (IOException ignored) {}
        return true;
    }

    public synchronized List<ReceivedFileInfo> receivedFiles() {
        return received.stream().sorted(Comparator.comparingLong(ReceivedFileInfo::receivedAt).reversed()).toList();
    }

    private Path uniqueTarget(Path directory, String fileName) throws IOException {
        Path requested = directory.resolve(fileName).normalize();
        requireInside(directory, requested);
        if (!Files.exists(requested)) return requested;
        int dot = fileName.lastIndexOf('.');
        String base = dot > 0 ? fileName.substring(0, dot) : fileName;
        String extension = dot > 0 ? fileName.substring(dot) : "";
        for (int index = 1; index <= 10_000; index++) {
            Path candidate = directory.resolve(base + " (" + index + ")" + extension).normalize();
            requireInside(directory, candidate);
            if (!Files.exists(candidate)) return candidate;
        }
        throw new IOException("Cannot allocate a unique file name");
    }

    private static void requireInside(Path directory, Path candidate) throws IOException {
        if (!candidate.startsWith(directory) || candidate.equals(directory)) {
            throw new IOException("Path escapes receive directory");
        }
    }

    public static boolean safeFileName(String value) {
        if (value == null || value.isBlank() || value.length() > 240 || value.equals(".") || value.equals("..")) {
            return false;
        }
        try {
            if (!Path.of(value).getFileName().toString().equals(value)) return false;
            return value.chars().noneMatch(character -> character < 32
                    || "<>:\"/\\|?*".indexOf(character) >= 0);
        } catch (java.nio.file.InvalidPathException exception) {
            return false;
        }
    }

    private static boolean safeIdentifier(String value) {
        return value != null && value.matches("[A-Za-z0-9-]{1,128}");
    }

    private Incoming requireActive(String transferId) throws IOException {
        Incoming incoming = active.get(transferId);
        if (incoming == null) throw new IOException("Unknown transfer");
        return incoming;
    }

    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public static String sha256(Path path) throws IOException {
        MessageDigest digest = digest();
        byte[] buffer = new byte[MAX_CHUNK_BYTES];
        try (var input = Files.newInputStream(path)) {
            int count;
            while ((count = input.read(buffer)) >= 0) if (count > 0) digest.update(buffer, 0, count);
        }
        return hex(digest.digest());
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format("%02x", value & 0xff));
        return result.toString();
    }

    private static String safeMessage(Throwable exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try { closeable.close(); } catch (Exception ignored) {}
    }

    @Override public synchronized void close() {
        for (String transferId : List.copyOf(active.keySet())) cancel(transferId);
    }

    public record OfferResult(boolean accepted, String reason, String targetPath) {
        static OfferResult reject(String reason) { return new OfferResult(false, reason, null); }
    }

    private static final class Incoming {
        private final String fileName;
        private final Path target;
        private final Path temporary;
        private final long size;
        private final String sha256;
        private final OutputStream output;
        private final MessageDigest digest;
        private long received;

        private Incoming(String fileName, Path target, Path temporary, long size, String sha256,
                OutputStream output, MessageDigest digest) {
            this.fileName = fileName;
            this.target = target;
            this.temporary = temporary;
            this.size = size;
            this.sha256 = sha256;
            this.output = output;
            this.digest = digest;
        }
    }
}
