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
  @Test
  void existingSddCollectionsRestoreSnapshotsAndLastPresentationWithoutInventingUnusedActivity() {
    String url = "jdbc:h2:mem:mandateMigration;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
    Flyway.configure().dataSource(url, "sa", "").target("4").load().migrate();
    var db = new JdbcTemplate(new DriverManagerDataSource(url, "sa", ""));
    db.update("insert into account(id,iban,owner_id,name) values('a','DE89370400440532013000','alice','Alice'),('b','NL91ABNA0417164300','bob','Bob')");
    for (String id : new String[] {"used", "unused"})
      db.update("insert into mandate(id,debtor_id,creditor_id,active,valid_until,max_amount,sequence_type) values(?,'a','b',true,DATE '2030-01-01',20,'RCUR')", id);
    for (String date : new String[] {"2026-09-01", "2026-10-10"})
      db.update("insert into payment(id,actor,idem_key,fingerprint,debtor_id,creditor_id,amount,scheme,end_to_end_id,mandate_id,status,created_at) values(?,'bob',?,'fingerprint','a','b',10,'SDD_CORE','E2E','used','REJECTED',?)",
          date, date, java.time.OffsetDateTime.parse(date + "T12:00:00Z"));
    Flyway.configure().dataSource(url, "sa", "").load().migrate();
    assertThat(db.queryForObject("select last_presented_date from mandate where id='used'", Date.class).toLocalDate()).isEqualTo(java.time.LocalDate.of(2026, 10, 10));
    assertThat(db.queryForObject("select last_presented_date from mandate where id='unused'", Date.class)).isNull();
    assertThat(db.queryForObject("select collection_sequence from payment where id='2026-09-01'", String.class)).isEqualTo("RCUR");
    assertThat(db.queryForObject("select collection_date from payment where id='2026-09-01'", Date.class).toLocalDate()).isEqualTo(java.time.LocalDate.of(2026, 9, 1));
    assertThatThrownBy(() -> db.update("update payment set collection_sequence='INVALID' where id='2026-09-01'"))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
  }

}
