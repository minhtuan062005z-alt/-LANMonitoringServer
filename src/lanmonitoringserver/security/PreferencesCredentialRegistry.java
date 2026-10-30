package lanmonitoringserver.security;

import java.util.prefs.Preferences;

public final class PreferencesCredentialRegistry implements CredentialRegistry {
    private final Preferences preferences;

    public PreferencesCredentialRegistry(Class<?> owner) {
        preferences = Preferences.userNodeForPackage(owner).node("registered-clients-v1");
    }

    @Override
    public synchronized RegistrationResult register(String clientId, String secret) {
        String key = Authentication.stableKey(clientId);
        String currentSecret = preferences.get(key, null);
        if (currentSecret == null) {
            preferences.put(key, secret);
            return RegistrationResult.CREATED;
        }
        return Authentication.constantTimeEquals(currentSecret, secret)
                ? RegistrationResult.ALREADY_REGISTERED : RegistrationResult.REJECTED;
    }

    @Override
    public synchronized boolean isRegistered(String clientId) {
        return preferences.get(Authentication.stableKey(clientId), null) != null;
    }

    @Override
    public synchronized boolean verify(String clientId, String clientNonce,
            String serverNonce, String proof) {
        String secret = preferences.get(Authentication.stableKey(clientId), null);
        if (secret == null) return false;
        String expected = Authentication.proof(secret, clientId, clientNonce, serverNonce);
        return Authentication.constantTimeEquals(expected, proof);
    }
}
