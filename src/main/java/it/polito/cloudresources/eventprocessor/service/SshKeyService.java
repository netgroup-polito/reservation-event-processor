package it.polito.cloudresources.eventprocessor.service;

import it.polito.cloudresources.eventprocessor.model.SshKey;
import it.polito.cloudresources.eventprocessor.repository.SshKeyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Service for managing SSH keys in the event processor context.
 * Read-only access to keys for payload construction.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SshKeyService {
    
    private final SshKeyRepository sshKeyRepository;
    
    /**
     * Get ALL SSH keys for a user (Wallet Support)
     * @param userId The Keycloak user ID
     * @return List of SSH public key strings
     */
    public List<String> getAllUserKeys(String userId) {
        return sshKeyRepository.findAllByUserId(userId).stream()
                .map(SshKey::getSshKey)
                .collect(Collectors.toList());
    }

    /**
     * Get single SSH key (Legacy Support)
     * Returns the first available key if any exists.
     * @param userId The Keycloak user ID
     * @return Optional containing one SSH key if found
     */
    public Optional<String> getUserSshKey(String userId) {
        // We use findAll to avoid NonUniqueResultException if multiple keys exist
        return sshKeyRepository.findAllByUserId(userId).stream()
                .findFirst()
                .map(SshKey::getSshKey);
    }

    /**
     * Get a specific SSH key by ID for a user.
     * Used when a specific key was selected during reservation.
     * @param userId The Keycloak user ID
     * @param keyId The specific ID of the SSH key (as String)
     * @return The public key string if found, null otherwise
     */
    public String getKey(String userId, String keyId) {
        if (keyId == null) return null;

        // Fetch all user keys and filter by ID in memory to ensure ownership and avoid parsing errors
        return sshKeyRepository.findAllByUserId(userId).stream()
                .filter(key -> String.valueOf(key.getId()).equals(keyId))
                .findFirst()
                .map(SshKey::getSshKey)
                .orElse(null);
    }

    /**
     * Get the Default SSH key for a user.
     * Used as a fallback when the specific key ID is missing or invalid.
     * Priority: Label "Default" -> First available key -> null.
     * * @param userId The Keycloak user ID
     * @return The public key string if found, null otherwise
     */
    public String getDefaultKey(String userId) {
        List<SshKey> keys = sshKeyRepository.findAllByUserId(userId);
        
        if (keys.isEmpty()) {
            return null;
        }

        // 1. Try to find key labeled "Default"
        return keys.stream()
                .filter(k -> "Default".equalsIgnoreCase(k.getLabel()))
                .map(SshKey::getSshKey)
                .findFirst()
                // 2. Fallback: Return the first available key
                .orElse(keys.get(0).getSshKey());
    }
}