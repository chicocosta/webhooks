package br.com.estudo.webhook;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class WebhookControllerTest {
 private final WebhookController controller = new WebhookController(JsonMapper.builder().findAndAddModules().build(), "secret");
 private final String body = """
  {"id":"cfa8bff5-bbc5-4f85-99f6-25c0bccbeaad","type":"order.created",
   "occurredAt":"2026-11-24T12:00:00Z",
   "data":{"id":"f27eab2d-2bc7-48e9-adbc-be2d3560ec51","customer":"Ana","total":99.90}}
  """;
 @Test void processesOnceAndAcknowledgesDuplicates() {
  String timestamp = Long.toString(Instant.now().getEpochSecond());
  String signature = Signature.sign("secret", timestamp, body);
  assertEquals("processed", controller.receive(body, timestamp, signature).status());
  assertEquals("duplicate", controller.receive(body, timestamp, signature).status());
  assertEquals(1, controller.events().size());
 }
 @Test void rejectsTamperedPayload() {
  String timestamp = Long.toString(Instant.now().getEpochSecond());
  var exception = assertThrows(ResponseStatusException.class, () -> controller.receive(body.replace("Ana", "Eva"), timestamp, Signature.sign("secret", timestamp, body)));
  assertEquals(401, exception.getStatusCode().value());
  assertTrue(controller.events().isEmpty());
 }
 @Test void rejectsExpiredSignature() {
  String timestamp = Long.toString(Instant.now().minusSeconds(600).getEpochSecond());
  var exception = assertThrows(ResponseStatusException.class, () -> controller.receive(body, timestamp, Signature.sign("secret", timestamp, body)));
  assertEquals(401, exception.getStatusCode().value());
 }
 @Test void rejectsInvalidEvent() {
  String timestamp = Long.toString(Instant.now().getEpochSecond());
  var exception = assertThrows(ResponseStatusException.class, () -> controller.receive("{}", timestamp, Signature.sign("secret", timestamp, "{}")));
  assertEquals(400, exception.getStatusCode().value());
 }
}
