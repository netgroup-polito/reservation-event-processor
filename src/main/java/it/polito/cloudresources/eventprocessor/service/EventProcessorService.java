package it.polito.cloudresources.eventprocessor.service;

import it.polito.cloudresources.eventprocessor.model.Event;
import it.polito.cloudresources.eventprocessor.model.WebhookEventType;
import it.polito.cloudresources.eventprocessor.repository.EventRepository;
import it.polito.cloudresources.eventprocessor.util.DateTimeUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class EventProcessorService {

    private final EventRepository eventRepository;
    private final DateTimeUtils dateTimeUtils;
    private final WebhookNotifierService webhookNotifierService;

    // Check for events starting soon
    @Scheduled(fixedRateString = "${event.processor.rate}")
    @Transactional
    public void processStartingEvents() {
        ZonedDateTime now = dateTimeUtils.ensureTimeZone(ZonedDateTime.now());
        ZonedDateTime soon = now.plus(5, ChronoUnit.MINUTES); // Define "soon" as 5 minutes from now
        log.debug("Checking for events starting between {} and {}", now, soon);

        List<Event> startingEvents = eventRepository.findUnprocessedEventsStartingBetween(now, soon);
        
        // Process each event individually without grouping by user
        for (Event event : startingEvents) {
            log.info("Processing start event ID: {}, Resource: {}, User: {}, Start: {}",
                    event.getId(),
                    event.getResource().getName(),
                    event.getKeycloakId(),
                    dateTimeUtils.formatDateTime(event.getStart()));
            
            // Notify each event individually
            webhookNotifierService.notify(WebhookEventType.EVENT_START, event);
            
            event.setStartNotifiedAt(now); // Mark event as processed
            log.info("Saving event (start) processed: {}", event);
            eventRepository.save(event);
        }
    }

    // Check for events that have just ended (e.g., ended in the last minute)
    @Scheduled(fixedRateString = "${event.processor.rate}")
    @Transactional
    public void processEndingEvents() {
        ZonedDateTime now = dateTimeUtils.ensureTimeZone(ZonedDateTime.now());
        ZonedDateTime justEndedThreshold = now.minus(1, ChronoUnit.MINUTES); // Define "just ended" as within the last minute
        log.debug("Checking for events ending between {} and {}", justEndedThreshold, now);

        List<Event> endingEvents = eventRepository.findUnprocessedEventsEndingBetween(justEndedThreshold, now);

        if (!endingEvents.isEmpty()) {
            log.info("Found {} events ending recently:", endingEvents.size());
            
            // Process each event individually without grouping by user
            for (Event event : endingEvents) {
                log.info("Processing end event ID: {}, Resource: {}, User: {}, End: {}",
                        event.getId(),
                        event.getResource().getName(),
                        event.getKeycloakId(),
                        dateTimeUtils.formatDateTime(event.getEnd()));

                // Notify each event individually
                webhookNotifierService.notify(WebhookEventType.EVENT_END, event);
                
                event.setEndNotifiedAt(now); // Mark event as processed
                log.info("Saving event (end) processed: {}", event);
                eventRepository.save(event);
            }
        } else {
            log.debug("No events ending recently.");
        }
    }

    // --- NUOVO SCHEDULER: GESTIONE CANCELLAZIONI ---
    // Cerca gli eventi marcati come "deleted" dal Backend, avvisa Python e pialla il DB.
    @Scheduled(fixedRateString = "${event.processor.rate}")
    @Transactional
    public void processDeletedEvents() {
        log.debug("Checking for logically deleted events to process and purge...");
        
        List<Event> deletedEvents = eventRepository.findByDeletedTrue();

        if (!deletedEvents.isEmpty()) {
            log.info("Found {} logically deleted events awaiting cleanup.", deletedEvents.size());

            for (Event event : deletedEvents) {
                // Il richiamo di getName() qui è importante anche per forzare l'inizializzazione 
                // del proxy Lazy della risorsa prima di passarla al Webhook asincrono
                log.info("Processing DELETED event ID: {}, Resource: {}, User: {}",
                        event.getId(),
                        event.getResource().getName(),
                        event.getKeycloakId());

                // 1. Inviamo la notifica al Webhook Server Python.
                // Python valuterà il payload e capirà se la macchina è attualmente in esecuzione.
                // Se lo è, invierà i comandi distruttivi a Kubernetes. Altrimenti non farà nulla.
                webhookNotifierService.notify(WebhookEventType.EVENT_DELETED, event);

                // 2. Pulizia definitiva. 
                // Ora che Python è stato avvisato, possiamo cancellare fisicamente la riga dal DB.
                log.info("Purging event {} permanently from the database.", event.getId());
                eventRepository.delete(event);
            }
        }
    }
}