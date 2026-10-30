package lanmonitoringserver.model;

import java.util.regex.Pattern;

/** Explicit Ansible allowlist entry; this is separate from a Java Client connection. */
public record AnsibleManagedHost(String inventoryName, String displayName,
        String applicationClientId, AnsiblePlatform platform, String address, int port,
        AnsibleConnectionType connection, String destinationPath, boolean enabled) {
    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_-]{0,63}");
    private static final Pattern ADDRESS = Pattern.compile("[A-Za-z0-9._:-]{1,255}");
    private static final Pattern WINDOWS_PATH = Pattern.compile("[A-Za-z]:\\\\[^\\r\\n]*");

    public AnsibleManagedHost {
        if (inventoryName == null || !NAME.matcher(inventoryName).matches()) {
            throw new IllegalArgumentException("Invalid Ansible inventory name");
        }
        if (displayName == null || displayName.isBlank() || displayName.length() > 128) {
            throw new IllegalArgumentException("Invalid Ansible display name");
        }
        applicationClientId = applicationClientId == null ? "" : applicationClientId.trim();
        if (applicationClientId.length() > 128) throw new IllegalArgumentException("Client ID too long");
        if (platform == null || connection == null || !connection.supports(platform)) {
            throw new IllegalArgumentException("Unsupported Ansible platform/connection combination");
        }
        if (address == null || !ADDRESS.matcher(address).matches()) {
            throw new IllegalArgumentException("Invalid managed-host address");
        }
        if (port < 1 || port > 65_535) throw new IllegalArgumentException("Invalid managed-host port");
        if (!safeDestination(platform, destinationPath)) {
            throw new IllegalArgumentException("Unsafe Ansible destination path");
        }
    }

    private static boolean safeDestination(AnsiblePlatform platform, String value) {
        if (value == null || value.isBlank() || value.length() > 1024
                || value.indexOf('\0') >= 0 || value.contains("..")) return false;
        String normalized = value.replace('\\', '/');
        if (!normalized.endsWith("/lan_monitoring_sync.txt")) return false;
        return platform == AnsiblePlatform.LINUX
                ? value.startsWith("/") && !value.contains("\\")
                : WINDOWS_PATH.matcher(value).matches() && !value.contains("/");
    }
}
