package dev.eufin;

import static org.assertj.core.api.Assertions.*;

import dev.eufin.events.PaymentTopology;
import java.util.Properties;
import org.apache.kafka.common.serialization.*;
import org.apache.kafka.streams.*;
import org.junit.jupiter.api.Test;

class TopologyTest {
  @Test
  void duplicateAndOlderEventsCannotRegressState() {
    Properties p = new Properties();
    p.put(StreamsConfig.APPLICATION_ID_CONFIG, "test");
    p.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "unused:9092");
    try (var driver = new TopologyTestDriver(PaymentTopology.build(), p)) {
      var input =
          driver.createInputTopic(
              "bank.payment-events.v1", new StringSerializer(), new StringSerializer());
      input.pipeInput("p1", "{\"sequence\":1,\"status\":\"VALIDATED\"}");
      input.pipeInput("p1", "{\"sequence\":6,\"status\":\"SETTLED\"}");
      input.pipeInput("p1", "{\"sequence\":6,\"status\":\"SETTLED\"}");
      input.pipeInput("p1", "{\"sequence\":2,\"status\":\"ROUTED\"}");
      assertThat(driver.<String, String>getKeyValueStore("payments-latest").get("p1"))
          .contains("SETTLED");
      assertThat(driver.<String, Long>getKeyValueStore("payments-by-status").get("SETTLED"))
          .isEqualTo(1L);
    }
  }
}
