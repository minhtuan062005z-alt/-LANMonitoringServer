package lanmonitoringserver.security;

import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryCredentialRegistry implements CredentialRegistry {
    private final ConcurrentHashMap<String, String> secrets = new ConcurrentHashMap<>();

    @Override
    public RegistrationResult register(String clientId, String secret) {
        String existing = secrets.putIfAbsent(clientId, secret);
        if (existing == null) return RegistrationResult.CREATED;
        return Authentication.constantTimeEquals(existing, secret)
                ? RegistrationResult.ALREADY_REGISTERED : RegistrationResult.REJECTED;
    }

    @Override
    public boolean isRegistered(String clientId) {
        return secrets.containsKey(clientId);
    }

    @Override
    public boolean verify(String clientId, String clientNonce, String serverNonce, String proof) {
        String secret = secrets.get(clientId);
        if (secret == null) return false;
        return Authentication.constantTimeEquals(
                Authentication.proof(secret, clientId, clientNonce, serverNonce), proof);
    }
}
