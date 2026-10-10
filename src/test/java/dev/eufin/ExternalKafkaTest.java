package dev.eufin;

import static dev.eufin.domain.Models.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import dev.eufin.customers.*;
import dev.eufin.domain.*;
import dev.eufin.events.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:kafka-tests;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
      "bank.kafka-enabled=true",
      "spring.kafka.bootstrap-servers=${EUFIN_TEST_KAFKA:127.0.0.1:19092}"
    })
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "EUFIN_EXTERNAL_KAFKA_TEST", matches = "true")
class ExternalKafkaTest {
  @Autowired BankService bank;
  @Autowired CustomerService customers;
  @Autowired OutboxRelay relay;
  @Autowired ProjectionRepository views;

  @Test
  void actualKafkaBrokerReceivesCommittedOutboxAndConsumerUpdatesJpaProjection() {
    String suffix = UUID.randomUUID().toString();
    Actor alice = new Actor("kafka-alice-" + suffix, false),
        bob = new Actor("kafka-bob-" + suffix, false),
        ops = new Actor("ops", true);
    for (String sub : List.of(alice.subject(), bob.subject())) {
      customers.register(
          sub,
          new CustomerModels.Register(
              new CustomerModels.Identity(
                  sub.equals(alice.subject()) ? "Kafka Alice" : "Kafka Bob",
                  LocalDate.of(1990, 1, 1),
                  "PL"),
              new CustomerModels.Contact(
                  "demo@example.test", "+48123456789", "Demo Street", "Warsaw", "PL")));
      customers.verify("agent", sub, "APPROVE");
    }
    // Isolated H2 database for this test class avoids colliding with standard fixtures.
    var a = bank.open(alice, new OpenAccount("DE89370400440532013000", "Kafka Alice"));
    var b = bank.open(bob, new OpenAccount("NL91ABNA0417164300", "Kafka Bob"));
    bank.fund(ops, a.id(), "fund", new BigDecimal("100"));
    var p =
        bank.initiate(
            alice,
            "key",
            new Initiate(a.id(), b.id(), new BigDecimal("10"), "SCT", "BROKER", "Kafka Bob", null));
    for (String action : List.of("SCREEN", "CLEAR", "SETTLE")) bank.transition(ops, p.id(), action);
    assertThat(relay.relay()).isEqualTo(6);
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () -> assertThat(views.findById(p.id()).orElseThrow().status).isEqualTo("SETTLED"));
    assertThat(bank.account(alice, a.id()).balance()).isEqualByComparingTo("90");
  }
}
