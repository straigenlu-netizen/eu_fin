package dev.eufin.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Registers a JSON contract. Kafka carries plain JSON + schema-id header (lab wire format). */
@Component
public class SchemaRegistry {
  private final String url;
  private final ObjectMapper json;
  private Integer schemaId;

  public SchemaRegistry(@Value("${bank.schema-registry-url}") String url, ObjectMapper json) {
    this.url = url;
    this.json = json;
  }

  @PostConstruct
  void register() throws Exception {
    if (url.isBlank()) return;
    String schema;
    try (var stream = getClass().getResourceAsStream("/contracts/payment-event-v1.json")) {
      schema = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
    var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    var request =
        HttpRequest.newBuilder(URI.create(url + "/subjects/bank.payment-events.v1-value/versions"))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/vnd.schemaregistry.v1+json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    json.writeValueAsString(Map.of("schemaType", "JSON", "schema", schema))))
            .build();
    var response = client.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200) throw new IllegalStateException("Schema registration failed");
    schemaId = json.readTree(response.body()).required("id").asInt();
  }

  public Integer schemaId() {
    return schemaId;
  }
}
