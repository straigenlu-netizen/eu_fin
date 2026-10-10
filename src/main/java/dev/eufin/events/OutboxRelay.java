package dev.eufin.events;

import dev.eufin.domain.BankService;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class OutboxRelay {
  private final JdbcTemplate db;
  private final EventConsumer consumer;
  private final KafkaTemplate<String, String> kafka;
  private final BankService bank;
  private final boolean enabled, kafkaEnabled;
  private final SchemaRegistry registry;

  public OutboxRelay(
      JdbcTemplate db,
      EventConsumer consumer,
      KafkaTemplate<String, String> kafka,
      BankService bank,
      SchemaRegistry registry,
      @Value("${bank.scheduler-enabled}") boolean enabled,
      @Value("${bank.kafka-enabled}") boolean kafkaEnabled) {
    this.registry = registry;
    this.db = db;
    this.consumer = consumer;
    this.kafka = kafka;
    this.bank = bank;
    this.enabled = enabled;
    this.kafkaEnabled = kafkaEnabled;
  }

  public int relay() {
    int count = 0;
    for (var row :
        db.queryForList(
            "select o.* from outbox o join payment_event e on e.id=o.id where o.published=false"
                + " order by e.payment_id,e.seq fetch first 100 rows only")) {
      try {
        String payload = row.get("payload").toString();
        if (kafkaEnabled) {
          var record =
              new org.apache.kafka.clients.producer.ProducerRecord<String, String>(
                  "bank.payment-events.v1", row.get("payment_id").toString(), payload);
          if (registry.schemaId() != null)
            record
                .headers()
                .add(
                    "schema-id",
                    registry
                        .schemaId()
                        .toString()
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
          kafka.send(record).get(5, TimeUnit.SECONDS);
        } else consumer.accept(payload);
        db.update("update outbox set published=true where id=?", row.get("id"));
        count++;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      } catch (Exception e) {
        break; /* retained for retry; no financial payload logged */
      }
    }
    return count;
  }

  @Scheduled(fixedDelay = 1000)
  public void tick() {
    if (enabled) {
      bank.expire();
      relay();
    }
  }
}
