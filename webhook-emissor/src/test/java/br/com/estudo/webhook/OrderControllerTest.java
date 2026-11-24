package br.com.estudo.webhook;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.net.InetSocketAddress;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class OrderControllerTest {
 @Test void recordsFailureAndRetriesSameSignedEvent() throws Exception {
  var mapper = JsonMapper.builder().findAndAddModules().build();
  var calls = new AtomicInteger();
  var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
  server.createContext("/webhook", exchange -> {
   String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
   String timestamp = exchange.getRequestHeaders().getFirst("X-Webhook-Timestamp");
   boolean valid = Signature.matches(Signature.sign("secret", timestamp, body), exchange.getRequestHeaders().getFirst("X-Webhook-Signature"));
   int status = valid ? (calls.incrementAndGet() == 1 ? 503 : 200) : 401;
   exchange.sendResponseHeaders(status, -1);
   exchange.close();
  });
  server.start();
  try {
   var controller = new OrderController(mapper, "http://127.0.0.1:" + server.getAddress().getPort() + "/webhook", "secret");
   var first = controller.create(new OrderController.CreateOrder("Ana", new BigDecimal("99.90")));
   assertEquals(503, first.attempts().getFirst().httpStatus());
   var retry = controller.retry(first.event().id());
   assertEquals(first.event(), retry.event());
   assertEquals(2, retry.attempts().size());
   assertEquals(200, retry.attempts().getLast().httpStatus());
   assertEquals(1, controller.list().size());
   assertEquals(2, calls.get());
  } finally { server.stop(0); }
 }
}
