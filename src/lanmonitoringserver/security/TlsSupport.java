package lanmonitoringserver.security;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import javax.net.ServerSocketFactory;
import javax.net.SocketFactory;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/** Builds TLS 1.3 socket factories from explicit JVM properties; never trusts all certificates. */
public final class TlsSupport {
    private TlsSupport() {}

    public static boolean configured() {
        return !setting("lanmonitoring.tls.keyStore").isBlank()
                || !setting("lanmonitoring.tls.trustStore").isBlank();
    }

    public static ServerSocketFactory serverSocketFactory() {
        if (!configured()) return ServerSocketFactory.getDefault();
        try { return context(true).getServerSocketFactory(); }
        catch (IOException | GeneralSecurityException exception) {
            throw new IllegalStateException("Cannot initialize Server TLS", exception);
        }
    }

    public static SocketFactory clientSocketFactory() {
        if (!configured()) return SocketFactory.getDefault();
        serverIdentity(); // Fail fast on an invalid configured identity.
        try { return context(false).getSocketFactory(); }
        catch (IOException | GeneralSecurityException exception) {
            throw new IllegalStateException("Cannot initialize Client TLS", exception);
        }
    }

    /**
     * Stable DNS identity used for certificate/SAN verification independently of the TCP address.
     * An empty value preserves the legacy behavior of verifying the hostname/IP entered by the user.
     */
    public static String serverIdentity() {
        String identity = setting("lanmonitoring.tls.serverIdentity");
        if (identity.isEmpty()) return "";
        if (identity.indexOf(':') >= 0 || identity.matches("[0-9.]+")) {
            throw new IllegalStateException("lanmonitoring.tls.serverIdentity must be a stable DNS name, not an IP address");
        }
        try {
            return new SNIHostName(identity).getAsciiName().toLowerCase(java.util.Locale.ROOT);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Invalid lanmonitoring.tls.serverIdentity DNS name", exception);
        }
    }

    private static SSLContext context(boolean server) throws IOException, GeneralSecurityException {
        KeyManagerFactory keyManagers = null;
        String keyStorePath = setting("lanmonitoring.tls.keyStore");
        if (!keyStorePath.isEmpty()) {
            char[] password = required("lanmonitoring.tls.keyStorePassword").toCharArray();
            KeyStore keyStore = load(Path.of(keyStorePath), password);
            keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keyManagers.init(keyStore, password);
        } else if (server) {
            throw new GeneralSecurityException("Server TLS requires lanmonitoring.tls.keyStore");
        }

        String trustStorePath = required("lanmonitoring.tls.trustStore");
        char[] trustPassword = required("lanmonitoring.tls.trustStorePassword").toCharArray();
        TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        trustManagers.init(load(Path.of(trustStorePath), trustPassword));

        SSLContext context = SSLContext.getInstance("TLSv1.3");
        context.init(keyManagers == null ? null : keyManagers.getKeyManagers(),
                trustManagers.getTrustManagers(), null);
        return context;
    }

    private static KeyStore load(Path path, char[] password) throws IOException, GeneralSecurityException {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream input = Files.newInputStream(path.toAbsolutePath().normalize())) {
            store.load(input, password);
        }
        return store;
    }

    private static String required(String property) {
        String value = setting(property);
        if (value.isEmpty()) throw new IllegalStateException("Missing JVM property: " + property);
        return value;
    }

    private static String setting(String property) {
        String value = System.getProperty(property, "").trim();
        if (!value.isEmpty()) return value;
        String environment = property.replaceAll("([a-z])([A-Z])", "$1_$2")
                .toUpperCase(java.util.Locale.ROOT).replace('.', '_');
        return System.getenv().getOrDefault(environment, "").trim();
    }
}
