package io.lettuce.authx;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class EntraIdTestContext {

    private static final String AZURE_CLIENT_ID = "AZURE_CLIENT_ID";

    private static final String AZURE_CLIENT_SECRET = "AZURE_CLIENT_SECRET";

    private static final String AZURE_AUTHORITY = "AZURE_AUTHORITY";

    private static final String AZURE_REDIS_SCOPES = "AZURE_REDIS_SCOPES";

    private static final String AZURE_USER_ASSIGNED_MANAGED_ID = "AZURE_USER_ASSIGNED_MANAGED_ID";

    private final String clientId;

    private final String authority;

    private final String clientSecret;

    private Set<String> redisScopes;

    private String userAssignedManagedIdentity;

    public static final EntraIdTestContext DEFAULT = new EntraIdTestContext();

    private EntraIdTestContext() {
        clientId = System.getenv(AZURE_CLIENT_ID);
        authority = System.getenv(AZURE_AUTHORITY);
        clientSecret = System.getenv(AZURE_CLIENT_SECRET);
        this.userAssignedManagedIdentity = System.getenv(AZURE_USER_ASSIGNED_MANAGED_ID);
    }

    public EntraIdTestContext(String clientId, String authority, String clientSecret, Set<String> redisScopes,
            String userAssignedManagedIdentity) {
        this.clientId = clientId;
        this.authority = authority;
        this.clientSecret = clientSecret;
        this.redisScopes = redisScopes;
        this.userAssignedManagedIdentity = userAssignedManagedIdentity;
    }

    public String getClientId() {
        return clientId;
    }

    public String getAuthority() {
        return authority;
    }

    public String getClientSecret() {
        return clientSecret;
    }

    public Set<String> getRedisScopes() {
        if (redisScopes == null) {
            String redisScopesEnv = System.getenv(AZURE_REDIS_SCOPES);
            this.redisScopes = new HashSet<>(Arrays.asList(redisScopesEnv.split(";")));
        }
        return redisScopes;
    }

    public String getUserAssignedManagedIdentity() {
        return userAssignedManagedIdentity;
    }

}
