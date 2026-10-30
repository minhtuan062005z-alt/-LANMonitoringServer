package lanmonitoringserver.security;

import java.util.prefs.Preferences;

public final class PreferencesClientIdentityStore implements ClientIdentityStore {
    private static final String CLIENT_ID = "clientId";
    private static final String CLIENT_SECRET = "clientSecret";
    private final Preferences preferences;

    public PreferencesClientIdentityStore(Class<?> owner) {
        preferences = Preferences.userNodeForPackage(owner).node("security-v1");
    }

    @Override
    public synchronized ClientCredentials loadOrCreate() {
        String id = preferences.get(CLIENT_ID, "").trim();
        String secret = preferences.get(CLIENT_SECRET, "").trim();
        if (!id.isEmpty() && !secret.isEmpty()) return new ClientCredentials(id, secret);
        ClientCredentials credentials = ClientCredentials.generate();
        preferences.put(CLIENT_ID, credentials.clientId());
        preferences.put(CLIENT_SECRET, credentials.secret());
        return credentials;
    }
}
