package br.com.estudo.webhook;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@RestController
public class OrderController {
    private static final Logger log = LoggerFactory.getLogger(OrderController.class);
    private final Map<UUID, Delivery> deliveries = new ConcurrentHashMap<>();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final ObjectMapper mapper;
    private final String url, secret;

    public OrderController(ObjectMapper mapper, @Value("${webhook.url}") String url,
            @Value("${webhook.secret}") String secret) {
        this.mapper = mapper;
        this.url = url;
        this.secret = secret;
    }

    public record CreateOrder(@NotBlank String customer, @NotNull @DecimalMin("0.01") BigDecimal total) {
    }

    public record Order(UUID id, String customer, BigDecimal total) {
    }

    public record Event(UUID id, String type, Instant occurredAt, Order data) {
    }

    public record Attempt(Instant at, Integer httpStatus, String result) {
    }

    public record Snapshot(Event event, List<Attempt> attempts) {
    }

    private static class Delivery {
        final Event event;
        final String body;
        final List<Attempt> attempts = new ArrayList<>();

        Delivery(Event event, String body) {
            this.event = event;
            this.body = body;
        }

        synchronized Snapshot snapshot() {
            return new Snapshot(event, List.copyOf(attempts));
        }
    }

    @PostMapping("/orders")
    @ResponseStatus(HttpStatus.CREATED)
    public Snapshot create(@Valid @RequestBody CreateOrder request) {
        var event = new Event(UUID.randomUUID(), "order.created", Instant.now(),
                new Order(UUID.randomUUID(), request.customer(), request.total()));
        var delivery = new Delivery(event, mapper.writeValueAsString(event));
        deliveries.put(event.id(), delivery);
        return send(delivery);
    }

    @GetMapping("/deliveries")
    public List<Snapshot> list() {
        return deliveries.values().stream().map(Delivery::snapshot).toList();
    }

    @PostMapping("/deliveries/{id}/retry")
    public Snapshot retry(@PathVariable UUID id) {
        var delivery = deliveries.get(id);
        if (delivery == null)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Entrega inexistente");
        return send(delivery);
    }

    private Snapshot send(Delivery delivery) {
        synchronized (delivery) {
            var timestamp = Long.toString(Instant.now().getEpochSecond());
            var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .header("X-Webhook-Timestamp", timestamp)
                    .header("X-Webhook-Signature", Signature.sign(secret, timestamp, delivery.body))
                    .POST(HttpRequest.BodyPublishers.ofString(delivery.body)).build();
            Attempt attempt;
            try {
                var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                attempt = new Attempt(Instant.now(), response.statusCode(), response.body());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                attempt = new Attempt(Instant.now(), null, "Envio interrompido");
            } catch (java.io.IOException e) {
                attempt = new Attempt(Instant.now(), null, "Falha de transporte: " + e.getClass().getSimpleName());
            }
            delivery.attempts.add(attempt);
            log.info("Webhook event={} tentativa={} status={}", delivery.event.id(), delivery.attempts.size(),
                    attempt.httpStatus());
            return delivery.snapshot();
        }
    }
}
