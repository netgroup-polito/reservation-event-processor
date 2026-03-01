package it.polito.cloudresources.eventprocessor.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.keycloak.OAuth2Constants;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.ws.rs.ClientErrorException; // <--- IL FIX PIÙ IMPORTANTE: da javax a jakarta
import java.util.Optional;

/**
 * Service for interacting with Keycloak to retrieve user information.
 * UPDATED VERSION: Uses native modern Keycloak Client (Jakarta EE)
 * which handles unknown properties natively without custom Resteasy hacks.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KeycloakService {

    public static final String ATTR_SSH_KEY = "ssh_key";

    @Value("${keycloak.auth-server-url}")
    private String authServerUrl;

    @Value("${keycloak.realm}")
    private String realm;

    @Value("${keycloak.resource}")
    private String clientId;

    @Value("${keycloak.credentials.secret}")
    private String clientSecret;

    // Cache locale del client per evitare di ricrearlo (che è costoso)
    private Keycloak keycloakInstance;

    /**
     * Creates an admin Keycloak client.
     * The modern Keycloak client natively handles JSON parsing securely and gracefully.
     */
    protected synchronized Keycloak getKeycloakClient() {
        if (keycloakInstance == null) {
            keycloakInstance = KeycloakBuilder.builder()
                    .serverUrl(authServerUrl)
                    .realm(realm)
                    .clientId(clientId)
                    .clientSecret(clientSecret)
                    .grantType(OAuth2Constants.CLIENT_CREDENTIALS)
                    .build();
        }
        return keycloakInstance;
    }

    protected RealmResource getRealmResource() {
        return getKeycloakClient().realm(realm);
    }

    public Optional<UserRepresentation> getUserById(String userId) {
        try {
            // log.debug("Fetching user representation for user ID '{}'", userId);
            UserRepresentation user = getRealmResource().users().get(userId).toRepresentation();
            return Optional.ofNullable(user);
        } catch (ClientErrorException e) {
            if (e.getResponse().getStatus() == 403) {
                log.warn("Permission denied fetching User ID {}. Check Keycloak 'view-users' role. Returning Empty.", userId);
            } else if (e.getResponse().getStatus() == 404) {
                log.warn("User ID {} not found in Keycloak.", userId);
            } else {
                log.error("Error fetching user {}: {}", userId, e.getMessage());
            }
            return Optional.empty();
        } catch (Exception e) {
            log.error("Unexpected error fetching user {}: {}", userId, e.getMessage());
            return Optional.empty();
        }
    }

    public Optional<String> getGroupNameById(String groupId) {
        try {
            // log.debug("Fetching group name for group ID '{}'", groupId);
            return Optional.ofNullable(getRealmResource().groups().group(groupId).toRepresentation().getName());
        } catch (ClientErrorException e) {
            if (e.getResponse().getStatus() == 403) {
                log.warn("Permission denied fetching Group ID {}. Check Keycloak 'query-groups' role. Returning Empty.", groupId);
            } else if (e.getResponse().getStatus() == 404) {
                log.warn("Group ID {} not found.", groupId);
            } else {
                log.error("Error fetching group {}: {}", groupId, e.getMessage());
            }
            return Optional.empty();
        } catch (Exception e) {
            log.error("Unexpected error fetching group {}: {}", groupId, e.getMessage());
            return Optional.empty();
        }
    }
}