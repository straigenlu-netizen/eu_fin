package dev.eufin;

import static dev.eufin.domain.Models.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import dev.eufin.domain.BankService;
import dev.eufin.events.*;
import java.math.BigDecimal;
import java.time.Duration;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.kafka.KafkaContainer;

@Tag("containers")
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class PostgresKafkaTest {
  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.6-alpine");

  @Container static KafkaContainer kafka = new KafkaContainer("apache/kafka:3.9.0")
    // Kafka 3.9 also validates the effective advertised controller address.
    .withEnv("KAFKA_LISTENERS", "PLAINTEXT://0.0.0.0:9092,BROKER://0.0.0.0:9093,CONTROLLER://localhost:9094");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry p) {
    p.add("spring.datasource.url", postgres::getJdbcUrl);
    p.add("spring.datasource.username", postgres::getUsername);
    p.add("spring.datasource.password", postgres::getPassword);
    p.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    p.add("bank.kafka-enabled", () -> "true");
  }

  @Autowired dev.eufin.customers.CustomerService customers;
  @Autowired BankService bank;
  @Autowired OutboxRelay relay;
  @Autowired ProjectionRepository views;

  @Test
  void databaseOutboxBrokerAndConsumerCommitProjection() {
    Actor alice = new Actor("alice", false),
        bob = new Actor("bob", false),
        ops = new Actor("ops", true);
    for (String sub : java.util.List.of("alice", "bob")) {
      customers.register(
          sub,
          new dev.eufin.customers.CustomerModels.Register(
              new dev.eufin.customers.CustomerModels.Identity(
                  sub, java.time.LocalDate.of(1990, 1, 1), "PL"),
              new dev.eufin.customers.CustomerModels.Contact(
                  sub + "@example.test", "+48123456789", "Demo Street 1", "Warsaw", "PL")));
      customers.verify("agent", sub, "APPROVE");
    }
    var a = bank.open(alice, new OpenAccount("DE89370400440532013000", "Alice"));
    var b = bank.open(bob, new OpenAccount("NL91ABNA0417164300", "Bob"));
    bank.fund(ops, a.id(), "fund", new BigDecimal("100"));
    var p =
        bank.initiate(
            alice,
            "key",
            new Initiate(a.id(), b.id(), new BigDecimal("10"), "SCT", "PG-KAFKA", "Bob", null));
    for (String action : new String[] {"SCREEN", "CLEAR", "SETTLE"})
      bank.transition(ops, p.id(), action);
    assertThat(relay.relay()).isEqualTo(6);
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () -> assertThat(views.findById(p.id()).orElseThrow().status).isEqualTo("SETTLED"));
    assertThat(bank.account(alice, a.id()).balance()).isEqualByComparingTo("90");
    String mandate = bank.mandate(alice, new NewMandate(a.id(), b.id(), java.time.LocalDate.of(2030, 1, 1), new BigDecimal("20")));
    var sdd = bank.initiate(bob, "pg-sdd", new Initiate(a.id(), b.id(), new BigDecimal("10"), "SDD_CORE", "PG-SDD", "Bob", mandate));
    for (String action : new String[] {"SCREEN", "CLEAR", "SETTLE"}) bank.transition(ops, sdd.id(), action);
    bank.refund(alice, sdd.id());
    assertThat(bank.account(alice, a.id()).balance()).isEqualByComparingTo("90");
    assertThat(bank.sddRefunds(ops, 0).get(0).outstandingAmount()).isEqualByComparingTo("10");
    assertThat(relay.relay()).isEqualTo(7);
    await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
        assertThat(views.findById(sdd.id()).orElseThrow().status).isEqualTo("RETURNED"));
    bank.recoverSddRefund(ops, sdd.id(), "pg-recover", new BigDecimal("4"));
    assertThat(bank.sddRefunds(ops, 0).get(0).outstandingAmount()).isEqualByComparingTo("6");
    assertThat(relay.relay()).isEqualTo(1);

  }
}
