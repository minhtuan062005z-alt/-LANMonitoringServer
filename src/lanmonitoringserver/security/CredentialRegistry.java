package lanmonitoringserver.security;

public interface CredentialRegistry {
    RegistrationResult register(String clientId, String secret);
    boolean isRegistered(String clientId);
    boolean verify(String clientId, String clientNonce, String serverNonce, String proof);

    enum RegistrationResult {
        CREATED,
        ALREADY_REGISTERED,
        REJECTED
    }
}
