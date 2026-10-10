package dev.eufin;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.sql.Date;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class SddMigrationTest {
  @Test
  void existingLedgerRowsKeepAmountsAndGainExplicitBankAccountAndValueDate() {
    String url = "jdbc:h2:mem:sddMigration;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
    Flyway.configure().dataSource(url, "sa", "").target("3").load().migrate();
    var db = new JdbcTemplate(new DriverManagerDataSource(url, "sa", ""));
    db.update("insert into account(id,iban,owner_id,name,balance) values('a','DE89370400440532013000','owner','Owner',10)");
    db.update("insert into ledger(id,booking_id,account_id,delta,booked_at) values('old-bank','old-booking',null,-10,TIMESTAMP WITH TIME ZONE '2026-10-10 12:00:00+00:00')");
    db.update("insert into ledger(id,booking_id,account_id,delta,booked_at) values('old-customer','old-booking','a',10,TIMESTAMP WITH TIME ZONE '2026-10-10 12:00:00+00:00')");
    Flyway.configure().dataSource(url, "sa", "").load().migrate();
    assertThat(db.queryForObject("select bank_account from ledger where id='old-bank'", String.class)).isEqualTo("TREASURY");
    assertThat(db.queryForObject("select value_date from ledger where id='old-bank'", Date.class).toLocalDate())
        .isEqualTo(java.time.LocalDate.of(2026, 10, 10));
    assertThat(db.queryForObject("select sum(delta) from ledger", BigDecimal.class)).isZero();
    assertThatThrownBy(() -> db.update("update ledger set bank_account='TREASURY' where id='old-customer'"))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
  }
}
