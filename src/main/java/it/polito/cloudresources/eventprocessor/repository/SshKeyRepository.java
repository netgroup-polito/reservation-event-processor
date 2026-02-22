package it.polito.cloudresources.eventprocessor.repository;

import it.polito.cloudresources.eventprocessor.model.SshKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repository for SSH keys
 */
@Repository
public interface SshKeyRepository extends JpaRepository<SshKey, Long> {
    
    /**
     * Find all SSH keys by user ID
     * Updated to return a List to support 1:N relationship (Wallet)
     * * @param userId The Keycloak user ID
     * @return List of SSH keys found
     */
    List<SshKey> findAllByUserId(String userId);
    
    /**
     * Delete SSH keys by user ID
     * * @param userId The Keycloak user ID
     * @return Number of records deleted
     */
    int deleteByUserId(String userId);
}