package it.polito.cloudresources.eventprocessor.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.polito.cloudresources.eventprocessor.model.Event;
import it.polito.cloudresources.eventprocessor.model.Resource;
import it.polito.cloudresources.eventprocessor.model.SshKey;
import it.polito.cloudresources.eventprocessor.model.WebhookConfig;
import it.polito.cloudresources.eventprocessor.model.WebhookEventType;
import it.polito.cloudresources.eventprocessor.model.dto.EventWebhookPayload;
import it.polito.cloudresources.eventprocessor.repository.SshKeyRepository;
import it.polito.cloudresources.eventprocessor.repository.WebhookConfigRepository;
import it.polito.cloudresources.eventprocessor.util.DateTimeUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class WebhookNotifierService {

    private final WebhookConfigRepository webhookConfigRepository;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final DateTimeUtils dateTimeUtils;
    private final KeycloakService keycloakService;
    private final SshKeyRepository sshKeyRepository;

    @Async
    public void notify(WebhookEventType eventType, Event event) {
        if (event == null) {
            log.debug("No event provided for notification");
            return;
        }

        log.debug("Searching webhooks for event type {} and event ID {} for user {}", 
                  eventType, event.getId(), event.getKeycloakId());

        List<WebhookConfig> relevantWebhooks = webhookConfigRepository.findRelevantWebhooksForResourceEvent(
                event.getResource().getId(), eventType);

        if (relevantWebhooks.isEmpty()) {
            log.debug("No relevant webhooks found for event ID {}", event.getId());
            return;
        }

        log.info("Found {} relevant webhooks for event ID {}", relevantWebhooks.size(), event.getId());

        for (WebhookConfig webhook : relevantWebhooks) {
            if (!webhook.isEnabled()) {
                log.debug("Skipping disabled webhook: {}", webhook.getName());
                continue;
            }
            try {
                sendWebhook(webhook, eventType, event);
            } catch (Exception e) {
                log.error("Error sending webhook {} for event ID {}: {}", 
                          webhook.getName(), event.getId(), e.getMessage(), e);
            }
        }
    }

    private void sendWebhook(WebhookConfig webhook, WebhookEventType eventType, Event event) throws JsonProcessingException {
        EventWebhookPayload payload = createPayload(eventType, event, webhook.getId());
        String payloadJson = objectMapper.writeValueAsString(payload);
        
        log.info("Payload JSON for webhook {}: {}", webhook.getName(), payloadJson);
        
        HttpHeaders headers = createHeaders(webhook, payloadJson);
        HttpEntity<String> entity = new HttpEntity<>(payloadJson, headers);

        log.info("Sending webhook '{}' for event type {} to URL: {} with body length: {}", 
                 webhook.getName(), eventType, webhook.getUrl(), payloadJson.length());

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    webhook.getUrl(),
                    HttpMethod.POST,
                    entity,
                    String.class
            );

            if (response.getStatusCode().is2xxSuccessful()) {
                log.info("Webhook '{}' sent successfully for event ID {}. Status: {}", webhook.getName(), event.getId(), response.getStatusCode());
            } else {
                log.warn("Webhook '{}' for event ID {} failed. Status: {}, Response: {}", webhook.getName(), event.getId(), response.getStatusCode(), response.getBody());
            }
        } catch (Exception e) {
            log.error("Failed to send webhook '{}' for event ID {}: {}", webhook.getName(), event.getId(), e.getMessage());
            throw e;
        }
    }

    private EventWebhookPayload createPayload(WebhookEventType eventType, Event event, Long webhookId) {
        List<String> sshKeysList = new ArrayList<>();
        String username = null;
        String email = null;
        String siteName = null;

        // Fetch user details
        try {
            Optional<UserRepresentation> userOpt = keycloakService.getUserById(event.getKeycloakId());
            if (userOpt.isPresent()) {
                UserRepresentation user = userOpt.get();
                username = user.getUsername();
                email = user.getEmail();
                log.debug("Found user details for {}: username={}, email={}", event.getKeycloakId(), username, email);
            } else {
                log.warn("User details not found for Keycloak ID: {}", event.getKeycloakId());
            }
        } catch (Exception e) {
            log.error("Error fetching user details for user {}: {}", event.getKeycloakId(), e.getMessage());
        }

        // Fetch SSH Keys
        try {
            List<SshKey> userKeys = sshKeyRepository.findAllByUserId(event.getKeycloakId());
            if (userKeys != null && !userKeys.isEmpty()) {
                sshKeysList = userKeys.stream()
                    .map(SshKey::getSshKey)
                    .collect(Collectors.toList());
                log.debug("Resolved {} SSH keys for user {}", sshKeysList.size(), event.getKeycloakId());
            } else {
                log.warn("No SSH keys found for user {}.", event.getKeycloakId());
            }
        } catch (Exception e) {
            log.error("Error resolving SSH keys for event {}: {}", event.getId(), e.getMessage());
        }

        // Fetch site name
        Resource resource = event.getResource();
        if (resource != null && resource.getSiteId() != null) {
            try {
                Optional<String> siteNameOpt = keycloakService.getGroupNameById(resource.getSiteId());
                if (siteNameOpt.isPresent()) {
                    siteName = siteNameOpt.get();
                    log.debug("Found site name '{}' for site ID {}", siteName, resource.getSiteId());
                } else {
                    log.warn("Site name not found for site ID: {}", resource.getSiteId());
                }
            } catch (Exception e) {
                log.warn("Could not fetch site name: {}", e.getMessage());
            }
        }

        // --- COSTRUZIONE PAYLOAD ---
        EventWebhookPayload.EventWebhookPayloadBuilder payloadBuilder = EventWebhookPayload.builder()
                .eventType(eventType)
                .timestamp(dateTimeUtils.ensureTimeZone(ZonedDateTime.now()))
                .eventId(event.getId().toString())
                .webhookId(webhookId)
                .userId(event.getKeycloakId())
                .username(username)
                .email(email)
                .sshKeys(sshKeysList)
                .operatingSystem(event.getOperatingSystem())
                
                // --- NUOVI CAMPI ISO & CHECKSUM (VERIFICATI) ---
                .imageUrl(event.getImageUrl())
                .checksumUrl(event.getChecksumUrl())
                .checksumType(event.getChecksumType())
                // -----------------------------------------------
                
                .eventTitle(event.getTitle())
                .eventDescription(event.getDescription())
                .eventStart(event.getStart())
                .eventEnd(event.getEnd())
                .customParameters(event.getCustomParameters());

        if (resource != null) {
            payloadBuilder = payloadBuilder
                    .resourceId(resource.getId())
                    .resourceName(resource.getName())
                    .resourceSpecs(resource.getSpecs())
                    .resourceLocation(resource.getLocation())
                    .siteId(resource.getSiteId())
                    .siteName(siteName);
            if (resource.getType() != null) {
                payloadBuilder = payloadBuilder.resourceType(resource.getType().getName());
            }
        }

        return payloadBuilder.build();
    }

    private HttpHeaders createHeaders(WebhookConfig webhook, String payloadJson) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        if (webhook.getSecret() != null && !webhook.getSecret().isEmpty()) {
            try {
                Mac sha256Hmac = Mac.getInstance("HmacSHA256");
                SecretKeySpec secretKeySpec = new SecretKeySpec(webhook.getSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
                sha256Hmac.init(secretKeySpec);
                byte[] hash = sha256Hmac.doFinal(payloadJson.getBytes(StandardCharsets.UTF_8));
                String signature = new String(Base64.getEncoder().encode(hash), StandardCharsets.UTF_8);
                headers.add("X-Webhook-Signature", signature);
            } catch (Exception e) {
                log.error("Error generating HMAC signature: {}", e.getMessage());
            }
        }
        return headers;
    }
}