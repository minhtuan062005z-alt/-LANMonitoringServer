package lanmonitoringserver.security;

public interface ClientIdentityStore {
    ClientCredentials loadOrCreate();
}
