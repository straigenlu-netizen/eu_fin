package dev.eufin.clearing;

import static dev.eufin.domain.Models.*;

import dev.eufin.domain.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "bank.auto-clearing-enabled", havingValue = "true")
public class PaymentWorker {
  private final BankService bank;
  private final JdbcTemplate db;

  public PaymentWorker(BankService bank, JdbcTemplate db) {
    this.bank = bank;
    this.db = db;
  }

  @Scheduled(fixedDelay = 200)
  public void run() {
    Actor actor = new Actor("lab-clearing-worker", true);
    for (String id :
        db.queryForList(
            "select id from payment where status in ('VALIDATED','ROUTED','CLEARED') order by"
                + " created_at fetch first 50 rows only",
            String.class)) {
      try {
        var p = bank.payment(actor, id);
        if (p.status().equals("VALIDATED")) p = bank.transition(actor, id, "SCREEN");
        if (p.status().equals("ROUTED")) p = bank.transition(actor, id, "CLEAR");
        if (p.status().equals("CLEARED")) bank.transition(actor, id, "SETTLE");
      } catch (BankError e) {
        if (e.status != 409) throw e;
      } catch (io.github.resilience4j.circuitbreaker.CallNotPermittedException e) {
        return;
      }
    }
  }
}
