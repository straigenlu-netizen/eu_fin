package dev.eufin;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class IsoSchemaIntegrityTest {
  @Test
  void officialSchemaBytesMatchPinnedSourceManifest() throws Exception {
    try (var manifest = getClass().getResourceAsStream("/iso/xsd/sources.json")) {
      var sources = new ObjectMapper().readTree(manifest);
      assertThat(sources.size()).isEqualTo(7);
      var messages = sources.fieldNames();
      while (messages.hasNext()) {
        String message = messages.next();
        var source = sources.get(message);
        assertThat(source.get("source").asText()).startsWith("https://www.iso20022.org/");
        try (var xsd = getClass().getResourceAsStream("/iso/xsd/" + message + ".xsd")) {
          assertThat(xsd).as(message).isNotNull();
          String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(xsd.readAllBytes()));
          assertThat(digest).as(message).isEqualTo(source.get("storedSha256").asText());
        }
      }
    }
  }
}
