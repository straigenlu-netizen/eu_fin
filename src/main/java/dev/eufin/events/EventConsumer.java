package dev.eufin.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EventConsumer {
  private final JdbcTemplate db;
  private final ObjectMapper json;
  private final Clock clock;

  public EventConsumer(JdbcTemplate db, ObjectMapper json, Clock clock) {
    this.db = db;
    this.json = json;
    this.clock = clock;
  }

  @Transactional
  public void accept(String payload) {
    try {
      var e = json.readTree(payload);
      String id = e.required("eventId").asText(), payment = e.required("paymentId").asText();
      long seq = e.required("sequence").asLong();
      String status = e.required("status").asText();
      java.util.UUID.fromString(id);
      java.util.UUID.fromString(payment);
      java.time.Instant.parse(e.required("occurredAt").asText());
      if (seq < 1
          || !java.util.Set.of(
                  "RECEIVED",
                  "VALIDATED",
                  "SCREENED",
                  "ROUTED",
                  "CLEARED",
                  "SETTLED",
                  "REJECTED",
                  "TIMED_OUT",
                  "RECALL_REQUESTED",
                  "RETURNED")
              .contains(status)
          || e.size() != 5) throw new IllegalArgumentException("Invalid event contract");
      // Lock aggregate row: serializes duplicate consumers before testing inbox.
      if (db.queryForList("select id from payment where id=? for update", payment).isEmpty())
        throw new IllegalArgumentException("Unknown payment");
      if (db.queryForObject("select count(*) from inbox where id=?", Long.class, id) > 0) return;
      db.update("insert into inbox values(?,?)", id, clock.instant());
      if (db.queryForObject("select count(*) from payment_view where id=?", Long.class, payment)
          == 0) db.update("insert into payment_view values(?,?,?)", payment, status, seq);
      else
        db.update(
            "update payment_view set status=?,event_seq=? where id=? and event_seq<?",
            status,
            seq,
            payment,
            seq);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalArgumentException("Malformed event", e);
    }
  }
}
