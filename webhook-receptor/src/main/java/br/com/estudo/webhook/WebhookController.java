package br.com.estudo.webhook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@RestController
public class WebhookController {
    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);
    private final Map<UUID, Received> received = new ConcurrentHashMap<>();
    private final ObjectMapper mapper;
    private final String secret;

    public WebhookController(ObjectMapper mapper, @Value("${webhook.secret}") String secret) {
        this.mapper = mapper;
        this.secret = secret;
    }

    public record Order(UUID id, String customer, BigDecimal total) {
    }

    public record Event(UUID id, String type, Instant occurredAt, Order data) {
    }

    public record Received(Event event, Instant receivedAt) {
    }

    public record Receipt(UUID eventId, String status) {
    }

    @PostMapping("/webhooks/orders")
    public Receipt receive(@RequestBody String body,
            @RequestHeader("X-Webhook-Timestamp") String timestamp,
            @RequestHeader("X-Webhook-Signature") String signature) {
        long sentAt;
        try {
            sentAt = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Timestamp invalido");
        }
        long now = Instant.now().getEpochSecond();
        if (sentAt < now - 300 || sentAt > now + 300
                || !Signature.matches(Signature.sign(secret, timestamp, body), signature))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Assinatura invalida ou expirada");
        Event event;
        try {
            event = mapper.readValue(body, Event.class);
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "JSON invalido");
        }
        if (event == null || event.id() == null || !"order.created".equals(event.type()) || event.occurredAt() == null
                || event.data() == null || event.data().id() == null || event.data().customer() == null
                || event.data().customer().isBlank() || event.data().total() == null
                || event.data().total().signum() <= 0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Evento invalido");
        var previous = received.putIfAbsent(event.id(), new Received(event, Instant.now()));
        String status = previous == null ? "processed" : "duplicate";
        log.info("Webhook event={} status={}", event.id(), status);
        return new Receipt(event.id(), status);
    }

    @GetMapping("/events")
    public List<Received> events() {
        return List.copyOf(received.values());
    }
}
