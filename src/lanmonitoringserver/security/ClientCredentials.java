package lanmonitoringserver.security;

import java.util.UUID;

public record ClientCredentials(String clientId, String secret) {
    public ClientCredentials {
        if (clientId == null || clientId.isBlank() || clientId.length() > 128) {
            throw new IllegalArgumentException("Invalid clientId");
        }
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("Invalid client secret");
        }
    }

    public static ClientCredentials generate() {
        return new ClientCredentials(UUID.randomUUID().toString(), Authentication.newSecret());
    }
}
