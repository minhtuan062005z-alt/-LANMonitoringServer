package lanmonitoringserver.security;

import java.util.Set;
import lanmonitoringserver.model.UserRole;

/** Authenticated Server-console identity used for authorization and audit. */
public record AuthorizationContext(String principal, UserRole role, Set<String> allowedClientIds) {
    public AuthorizationContext {
        if (principal == null || principal.isBlank() || role == null) {
            throw new IllegalArgumentException("principal and role are required");
        }
        allowedClientIds = Set.copyOf(allowedClientIds == null ? Set.of() : allowedClientIds);
    }

    public boolean canAccessClient(String clientId) {
        return role == UserRole.ADMIN || allowedClientIds.contains(clientId);
    }

    public static AuthorizationContext fromSystemProperties() {
        UserRole role;
        try {
            role = UserRole.valueOf(System.getProperty("lanmonitoring.server.role", "ADMIN")
                    .trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            role = UserRole.VIEWER;
        }
        String principal = System.getProperty("user.name", "local-user");
        String configured = System.getProperty("lanmonitoring.operator.clients", "");
        Set<String> clients = java.util.Arrays.stream(configured.split(","))
                .map(String::trim).filter(value -> !value.isEmpty()).collect(java.util.stream.Collectors.toSet());
        return new AuthorizationContext(principal, role, clients);
    }
}
