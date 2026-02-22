package it.polito.cloudresources.eventprocessor.model.dto;

import it.polito.cloudresources.eventprocessor.model.WebhookEventType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * DTO representing the JSON payload sent to webhooks.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EventWebhookPayload {
    private WebhookEventType eventType;
    private ZonedDateTime timestamp;
    private String eventId;
    private Long webhookId;
    
    // User Info
    private String userId;
    private String username;
    private String email;
    
    // SSH Keys
    private List<String> sshKeys; // New wallet support

    // Event/Resource Details
    private String operatingSystem;

    // --- NUOVI CAMPI (ISO & CHECKSUM) ---
    private String imageUrl;
    private String checksumUrl;
    private String checksumType;
    // ------------------------------------

    private String eventTitle;
    private String eventDescription;
    private ZonedDateTime eventStart;
    private ZonedDateTime eventEnd;
    
    // CORRETTO: Cambiato da Map<String, String> a String per corrispondere all'Entità
    private String customParameters;
    
    // Resource Info
    private Long resourceId;
    private String resourceName;
    private String resourceSpecs;
    private String resourceLocation;
    private String siteId;
    private String siteName;
    private String resourceType;
}