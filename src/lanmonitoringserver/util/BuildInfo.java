package lanmonitoringserver.util;

import java.net.URI;
import java.nio.file.Path;

/** Stable release marker used to diagnose stale IDE classes or an old distribution JAR. */
public final class BuildInfo {
    public static final String RELEASE_ID = "phase9-wifi-fingerprint-6";

    private BuildInfo() {}

    public static String runtimeDescription(Class<?> anchor) {
        String source = "unknown-code-source";
        try {
            URI location = anchor.getProtectionDomain().getCodeSource().getLocation().toURI();
            Path path = Path.of(location).toAbsolutePath().normalize();
            Path name = path.getFileName();
            source = name == null ? path.toString() : name.toString();
        } catch (Exception ignored) {
            // A restricted runtime may not expose its code source; the release marker remains useful.
        }
        return "release=" + RELEASE_ID + ", protocol=modern-json-v1, source=" + source;
    }
}
