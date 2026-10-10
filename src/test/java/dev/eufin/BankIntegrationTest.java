package dev.eufin;

import static dev.eufin.domain.Models.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.eufin.domain.*;
import dev.eufin.events.*;
import dev.eufin.iso.IsoService;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ActiveProfiles("test")
class BankIntegrationTest {
  @Autowired dev.eufin.customers.CustomerService customers;
  @Autowired BankService bank;
  @Autowired JdbcTemplate db;
  @Autowired IsoService iso;
  @Autowired EventConsumer consumer;
  @Autowired OutboxRelay relay;
  @Autowired ProjectionRepository projections;
  @MockitoBean Clock clock;
  final Actor alice = new Actor("alice", false),
      bob = new Actor("bob", false),
      ops = new Actor("ops", true);
  Account a, b;
  final Instant now = Instant.parse("2026-10-10T12:00:00Z");

  @BeforeEach
  void setup() {
    when(clock.instant()).thenReturn(now);
    when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    for (String t :
        List.of(
            "inbox",
            "payment_view",
            "outbox",
            "payment_event",
            "ledger",
            "sdd_dispute",
            "sdd_recovery",
            "sdd_refund",
            "payment",
            "mandate",
            "consent",
            "account",
            "identity_change",
            "customer",
            "audit_log")) db.update("delete from " + t);
    for (String sub : List.of("alice", "bob")) {
      customers.register(
          sub,
          new dev.eufin.customers.CustomerModels.Register(
              new dev.eufin.customers.CustomerModels.Identity(
                  sub.equals("alice") ? "Alice Doe" : "Bob & Co", LocalDate.of(1990, 1, 1), "PL"),
              new dev.eufin.customers.CustomerModels.Contact(
                  sub + "@example.test", "+48123456789", "Demo Street 1", "Warsaw", "PL")));
      customers.verify("agent", sub, "APPROVE");
    }
    a = bank.open(alice, new OpenAccount("DE89370400440532013000", "Alice Doe"));
    b = bank.open(bob, new OpenAccount("NL91ABNA0417164300", "Bob & Co"));
    bank.fund(ops, a.id(), "fund", new BigDecimal("100.00"));
  }

  Initiate request(String scheme, String mandate) {
    return new Initiate(
        a.id(), b.id(), new BigDecimal("10.00"), scheme, "E2E-1", "Bob & Co", mandate);
  }

  Payment pay(String scheme) {
    return bank.initiate(alice, "key", request(scheme, null));
  }

  Payment settle(Payment p) {
    bank.transition(ops, p.id(), "SCREEN");
    bank.transition(ops, p.id(), "CLEAR");
    return bank.transition(ops, p.id(), "SETTLE");
  }

  @Test
  void localPayeeVerificationAndClearingWorkerSettleOnce() {
    assertThat(bank.verifyPayee(b.iban(), "Bob & Co")).isEqualTo(b.id());
    assertThatThrownBy(() -> bank.verifyPayee(b.iban(), "Wrong Person"))
        .isInstanceOf(BankError.class)
        .hasMessage("PAYEE_NOT_MATCHED");
    var p = pay("SCT");
    var worker = new dev.eufin.clearing.PaymentWorker(bank, db);
    worker.run();
    worker.run();
    assertThat(bank.payment(alice, p.id()).status()).isEqualTo("SETTLED");
    assertThat(bank.account(alice, a.id()).balance()).isEqualByComparingTo("90");
    assertThat(
            db.queryForObject(
                "select count(*) from ledger where booking_id=?", Long.class, "PAY:" + p.id()))
        .isEqualTo(2);
  }

  @Test
  void settlementHasBalancedJournalAndOutbox() {
    Payment p = pay("SCT");
    assertThat(bank.account(alice, a.id()).available()).isEqualByComparingTo("90");
    assertThat(settle(p).status()).isEqualTo("SETTLED");
    assertThat(bank.account(alice, a.id()).balance()).isEqualByComparingTo("90");
    assertThat(bank.account(bob, b.id()).balance()).isEqualByComparingTo("10");
    assertThat(db.queryForObject("select sum(delta) from ledger", BigDecimal.class))
        .isEqualByComparingTo("0");
    assertThat(
            db.queryForObject(
                "select count(*) from ledger where booking_id=?", Long.class, "PAY:" + p.id()))
        .isEqualTo(2);
    assertThat(bank.history(alice, p.id())).hasSize(6);
    assertThat(relay.relay()).isEqualTo(6);
    assertThat(projections.findById(p.id()).orElseThrow().status).isEqualTo("SETTLED");
    assertThat(relay.relay()).isZero();
  }

  @Test
  void idempotencyAndConflict() {
    Payment p = pay("SCT");
    assertThat(pay("SCT").id()).isEqualTo(p.id());
    assertThatThrownBy(
            () ->
                bank.initiate(
                    alice,
                    "key",
                    new Initiate(
                        a.id(), b.id(), new BigDecimal("11"), "SCT", "E2E-1", "Bob & Co", null)))
        .isInstanceOf(BankError.class)
        .hasMessage("IDEMPOTENCY_CONFLICT");
    assertThat(db.queryForObject("select count(*) from payment", Long.class)).isEqualTo(1);
    bank.fund(ops, a.id(), "fund", new BigDecimal("100"));
    assertThat(bank.account(alice, a.id()).balance()).isEqualByComparingTo("100");
    assertThatThrownBy(() -> bank.fund(ops, a.id(), "fund", new BigDecimal("101")))
        .hasMessage("IDEMPOTENCY_CONFLICT");
  }

  @Test
  void illegalTransitionDoesNotMoveMoney() {
    Payment p = pay("SCT");
    assertThatThrownBy(() -> bank.transition(ops, p.id(), "SETTLE"))
        .hasMessage("INVALID_TRANSITION");
    assertThat(bank.account(alice, a.id()).balance()).isEqualByComparingTo("100");
    assertThat(bank.history(alice, p.id())).hasSize(2);
  }

  @Test
  void rejectionReleasesOnlyReservation() {
    Payment p = pay("SCT");
    bank.transition(ops, p.id(), "REJECT");
    assertThat(bank.account(alice, a.id()).held()).isZero();
    assertThat(bank.account(alice, a.id()).balance()).isEqualByComparingTo("100");
    assertThatThrownBy(() -> bank.transition(ops, p.id(), "REJECT"))
        .hasMessage("INVALID_TRANSITION");
  }

  @Test
  void sanctionsSimulatorRejects() {
    db.update("update account set name='SIM_BLOCKED Bob' where id=?", b.id());
    Payment p =
        bank.initiate(
            alice,
            "sanctions",
            new Initiate(
                a.id(), b.id(), new BigDecimal("10"), "SCT", "E2E", "SIM_BLOCKED Bob", null));
    assertThat(bank.transition(ops, p.id(), "SCREEN").status()).isEqualTo("REJECTED");
    assertThat(bank.account(alice, a.id()).held()).isZero();
  }

  @Test
  void instantTimeoutCannotBeSettledLate() {
    Payment p = pay("SCT_INST");
    bank.transition(ops, p.id(), "SCREEN");
    bank.transition(ops, p.id(), "CLEAR");
    when(clock.instant()).thenReturn(now.plusSeconds(10));
    assertThat(bank.transition(ops, p.id(), "SETTLE").status()).isEqualTo("TIMED_OUT");
    assertThat(bank.account(alice, a.id()).balance()).isEqualByComparingTo("100");
    assertThat(bank.account(bob, b.id()).balance()).isZero();
  }

  @Test
  void expirationWorkerRestoresAvailability() {
    Payment p = pay("SCT_INST");
    when(clock.instant()).thenReturn(now.plusSeconds(11));
    assertThat(bank.expire()).isEqualTo(1);
    assertThat(bank.payment(alice, p.id()).status()).isEqualTo("TIMED_OUT");
    assertThat(bank.expire()).isZero();
    assertThat(bank.account(alice, a.id()).held()).isZero();
  }

  @Test
  void returnAndRecallNeverAutomaticallyRefund() {
    Payment p = settle(pay("SCT"));
    bank.transition(ops, p.id(), "RECALL");
    assertThat(bank.account(bob, b.id()).balance()).isEqualByComparingTo("10");
    assertThat(bank.transition(ops, p.id(), "RETURN").status()).isEqualTo("RETURNED");
    assertThat(bank.account(alice, a.id()).balance()).isEqualByComparingTo("100");
    assertThatThrownBy(() -> bank.transition(ops, p.id(), "RETURN"))
        .hasMessage("INVALID_TRANSITION");
    iso.validate(iso.export(alice, p.id(), "pacs004"), "pacs.004.001.09");
  }

  @Test
  void returnCannotSpendReservedCreditorFunds() {
    Payment p = settle(pay("SCT"));
    bank.initiate(
        bob,
        "reverse",
        new Initiate(b.id(), a.id(), new BigDecimal("10"), "SCT", "REVERSE", "Alice Doe", null));
    assertThatThrownBy(() -> bank.transition(ops, p.id(), "RETURN"))
        .hasMessage("RETURN_LIQUIDITY_REQUIRED");
    assertThat(bank.payment(alice, p.id()).status()).isEqualTo("SETTLED");
  }

  @Test
  void ownershipAndPayeeNameAreEnforced() {
    assertThatThrownBy(() -> bank.account(bob, a.id())).hasMessage("FORBIDDEN");
    assertThatThrownBy(() -> bank.initiate(bob, "key", request("SCT", null)))
        .hasMessage("FORBIDDEN");
    assertThatThrownBy(
            () ->
                bank.initiate(
                    alice,
                    "key",
                    new Initiate(
                        a.id(), b.id(), new BigDecimal("10"), "SCT", "E2E", "Other", null)))
        .hasMessage("PAYEE_NAME_MISMATCH");
    assertThat(bank.account(alice, a.id()).held()).isZero();
  }

  @Test
  void mandateAndRefund() {
    String m =
        bank.mandate(
            alice, new NewMandate(a.id(), b.id(), LocalDate.of(2027, 1, 1), new BigDecimal("20"),
                "MANDATE-2026-001", "DE98ZZZ09999999999", LocalDate.of(2026, 9, 1), "RCUR"));
    assertThatThrownBy(() -> bank.initiate(alice, "key", request("SDD_CORE", m)))
        .hasMessage("FORBIDDEN");
    Payment p = bank.initiate(bob, "key", request("SDD_CORE", m));
    settle(p);
    iso.validate(iso.export(alice, p.id(), "pacs003"), "pacs.003.001.08");
    iso.validate(iso.export(alice, p.id(), "pain008"), "pain.008.001.08");
    assertThat(bank.refund(alice, p.id()).status()).isEqualTo("RETURNED");
    assertThat(bank.account(alice, a.id()).balance()).isEqualByComparingTo("100");
  }

  @Test
  void directDebitXmlKeepsActualMandateMetadata() {
    String m = bank.mandate(alice, new NewMandate(a.id(), b.id(), LocalDate.of(2027, 1, 1),
        new BigDecimal("20"), "SIGNED-REFERENCE", "DE98ZZZ09999999999", LocalDate.of(2026, 9, 1), "OOFF"));
    Payment p = bank.initiate(bob, "metadata", request("SDD_CORE", m));
    for (String type : List.of("pain008", "pacs003")) {
      assertThat(iso.export(alice, p.id(), type)).contains("<MndtId>SIGNED-REFERENCE</MndtId>",
          "<DtOfSgntr>2026-09-01</DtOfSgntr>", "<Id>DE98ZZZ09999999999</Id>", "<SeqTp>OOFF</SeqTp>");
    }
  }

  String typedMandate(String sequence) {
    return bank.mandate(alice, new NewMandate(a.id(), b.id(), LocalDate.of(2031, 1, 1),
        new BigDecimal("20"), "LIFECYCLE", "DE98ZZZ09999999999", LocalDate.of(2026, 9, 1), sequence));
  }

  @Test
  void oneOffRejectCanBeCorrectedButSettledOrRefundedMandateCannotBeReused() {
    String m = typedMandate("OOFF");
    Payment first = bank.initiate(bob, "first", request("SDD_CORE", m));
    assertThat(bank.initiate(bob, "first", request("SDD_CORE", m)).id()).isEqualTo(first.id());
    assertThatThrownBy(() -> bank.initiate(bob, "second", request("SDD_CORE", m))).hasMessage("MANDATE_ALREADY_USED");
    bank.transition(ops, first.id(), "REJECT");
    Payment corrected = settle(bank.initiate(bob, "corrected", request("SDD_CORE", m)));
    assertThatThrownBy(() -> bank.initiate(bob, "third", request("SDD_CORE", m))).hasMessage("MANDATE_ALREADY_USED");
    bank.refund(alice, corrected.id());
    assertThatThrownBy(() -> bank.initiate(bob, "fourth", request("SDD_CORE", m))).hasMessage("MANDATE_ALREADY_USED");
  }

  @Test
  void concurrentOneOffCollectionsReserveFundsOnlyOnce() throws Exception {
    String m = typedMandate("OOFF");
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      var results = pool.invokeAll(List.<Callable<String>>of(
          () -> { try { return bank.initiate(bob, "race-one", request("SDD_CORE", m)).status(); } catch (BankError e) { return e.code; } },
          () -> { try { return bank.initiate(bob, "race-two", request("SDD_CORE", m)).status(); } catch (BankError e) { return e.code; } }));
      assertThat(List.of(results.get(0).get(), results.get(1).get())).containsExactlyInAnyOrder("VALIDATED", "MANDATE_ALREADY_USED");
      assertThat(bank.account(alice, a.id()).held()).isEqualByComparingTo("10");
      assertThat(db.queryForObject("select count(*) from payment", Integer.class)).isEqualTo(1);
    } finally { pool.shutdownNow(); }
  }

  @Test
  void mandateRevokedAfterClearingRejectsBeforeDebitAndReleasesHold() {
    String m = typedMandate("RCUR");
    Payment p = bank.initiate(bob, "pending", request("SDD_CORE", m));
    bank.transition(ops, p.id(), "SCREEN");
    bank.transition(ops, p.id(), "CLEAR");
    bank.revokeMandate(alice, m);
    Payment rejected = bank.transition(ops, p.id(), "SETTLE");
    assertThat(rejected.status()).isEqualTo("REJECTED");
    assertThat(rejected.reason()).isEqualTo("MD01");
    assertThat(bank.account(alice, a.id()).held()).isZero();
    assertThat(bank.account(alice, a.id()).balance()).isEqualByComparingTo("100");
    assertThat(bank.account(bob, b.id()).balance()).isZero();
    assertThat(db.queryForObject("select count(*) from ledger where booking_id=?", Integer.class, "PAY:" + p.id())).isZero();
    assertThatThrownBy(() -> bank.transition(ops, p.id(), "SETTLE")).hasMessage("INVALID_TRANSITION");
  }

  @Test
  void operatorsCannotCreateOrRevokeSomeoneElsesMandate() {
    assertThatThrownBy(() -> bank.mandate(ops, new NewMandate(a.id(), b.id(), LocalDate.of(2027, 1, 1), new BigDecimal("20"))))
        .hasMessage("FORBIDDEN");
    String m = typedMandate("RCUR");
    assertThatThrownBy(() -> bank.revokeMandate(ops, m)).hasMessage("FORBIDDEN");
    assertThat(bank.initiate(bob, "still-valid", request("SDD_CORE", m)).status()).isEqualTo("VALIDATED");
  }

  @Test
  void recurrentMandateSnapshotsSequenceAndDatesEvenAfterClockAdvances() {
    String m = typedMandate("FRST");
    Payment first = bank.initiate(bob, "first", request("SDD_CORE", m));
    when(clock.instant()).thenReturn(now.plus(Duration.ofDays(1)));
    Payment second = bank.initiate(bob, "second", request("SDD_CORE", m));
    for (String type : List.of("pain008", "pacs003")) {
      assertThat(iso.export(alice, first.id(), type)).contains("<SeqTp>FRST</SeqTp>", "<ReqdColltnDt>2026-10-10</ReqdColltnDt>");
      assertThat(iso.export(alice, second.id(), type)).contains("<SeqTp>RCUR</SeqTp>", "<ReqdColltnDt>2026-10-11</ReqdColltnDt>");
    }
  }

  @Test
  void inactivityUsesCalendarMonthsFromLatestPresentationIncludingRejectedCollection() {
    String m = typedMandate("RCUR");
    Payment p = bank.initiate(bob, "first", request("SDD_CORE", m));
    bank.transition(ops, p.id(), "REJECT");
    when(clock.instant()).thenReturn(Instant.parse("2029-10-10T12:00:00Z"));
    assertThatThrownBy(() -> bank.initiate(bob, "expired", request("SDD_CORE", m))).hasMessage("MANDATE_INACTIVE");
    assertThat(bank.account(alice, a.id()).held()).isZero();
    when(clock.instant()).thenReturn(Instant.parse("2029-10-09T12:00:00Z"));
    Payment next = bank.initiate(bob, "before-expiry", request("SDD_CORE", m));
    bank.transition(ops, next.id(), "REJECT");
    when(clock.instant()).thenReturn(Instant.parse("2029-10-10T12:00:00Z"));
    assertThat(bank.initiate(bob, "renewed", request("SDD_CORE", m)).status()).isEqualTo("VALIDATED");
  }

  @Test
  void incompleteOrFutureDatedMandateMetadataIsRejected() {
    assertThatThrownBy(() -> bank.mandate(alice, new NewMandate(a.id(), b.id(), LocalDate.of(2027, 1, 1),
        new BigDecimal("20"), "REFERENCE", null, null, null))).hasMessage("INVALID_MANDATE_METADATA");
    assertThatThrownBy(() -> bank.mandate(alice, new NewMandate(a.id(), b.id(), LocalDate.of(2027, 1, 1),
        new BigDecimal("20"), "REFERENCE", "DE98ZZZ09999999999", LocalDate.of(2026, 10, 11), "RCUR")))
        .hasMessage("INVALID_MANDATE_METADATA");
  }

  @Test
  void legacyMandatesCannotFabricateSepaMetadata() {
    String m = bank.mandate(alice, new NewMandate(a.id(), b.id(), LocalDate.of(2027, 1, 1), new BigDecimal("20")));
    Payment p = bank.initiate(bob, "legacy", request("SDD_CORE", m));
    for (String type : List.of("pain008", "pacs003"))
      assertThatThrownBy(() -> iso.export(alice, p.id(), type)).hasMessage("SEPA_MANDATE_METADATA_REQUIRED");
  }

  @Test
  void instantXmlRetainsAcceptanceTimestampWithMilliseconds() {
    Payment p = bank.initiate(alice, "timestamp", request("SCT_INST", null));
    String xml = iso.export(alice, p.id(), "pacs008");
    assertThat(xml).contains("<AccptncDtTm>2026-10-10T12:00:00.000Z</AccptncDtTm>");
    when(clock.instant()).thenReturn(Instant.parse("2026-10-10T12:00:01Z"));
    assertThat(iso.export(alice, p.id(), "pacs008")).contains("<AccptncDtTm>2026-10-10T12:00:00.000Z</AccptncDtTm>");
  }

  @Test
  void revokedMandateCannotBeCollected() {
    String m =
        bank.mandate(
            alice, new NewMandate(a.id(), b.id(), LocalDate.of(2027, 1, 1), new BigDecimal("20")));
    bank.revokeMandate(alice, m);
    assertThatThrownBy(() -> bank.initiate(bob, "key", request("SDD_CORE", m)))
        .hasMessage("INVALID_MANDATE");
  }

  @Test
  void directDebitRefundWindowIsEnforced() {
    String m =
        bank.mandate(
            alice, new NewMandate(a.id(), b.id(), LocalDate.of(2027, 1, 1), new BigDecimal("20")));
    Payment p = settle(bank.initiate(bob, "key", request("SDD_CORE", m)));
    when(clock.instant()).thenReturn(now.plus(Duration.ofDays(57)));
    assertThatThrownBy(() -> bank.refund(alice, p.id())).hasMessage("REFUND_WINDOW_EXPIRED");
  }

  Payment directDebit() {
    String mandate = bank.mandate(alice, new NewMandate(a.id(), b.id(), LocalDate.of(2027, 12, 31), new BigDecimal("20")));
    return settle(bank.initiate(bob, "sdd", request("SDD_CORE", mandate)));
  }

  void assertBalancedBooking(String booking) {
    assertThat(db.queryForObject("select sum(delta) from ledger where booking_id=?", BigDecimal.class, booking)).isZero();
    assertThat(db.queryForObject("select count(*) from ledger where booking_id=?", Long.class, booking)).isEqualTo(2);
  }

  @Test
  void sddRefundCreditsPayerEvenWhenCreditorHasSpentEverything() {
    Payment p = directDebit();
    Account merchantOther = bank.open(bob, new OpenAccount("BE68539007547034", "Bob & Co"));
    settle(bank.initiate(bob, "spend", new Initiate(b.id(), merchantOther.id(), new BigDecimal("10"),
        "SCT", "SPEND", "Bob & Co", null)));
    assertThat(bank.account(bob, b.id()).balance()).isZero();
    assertThat(bank.refund(alice, p.id()).reason()).isEqualTo("MD06");
    assertThat(bank.account(alice, a.id()).balance()).isEqualByComparingTo("100");
    assertThat(bank.account(bob, b.id()).balance()).isZero();
    assertThat(bank.sddRefunds(ops, 0).get(0).outstandingAmount()).isEqualByComparingTo("10");
    assertBalancedBooking("SDD_REFUND:" + p.id());
    assertThat(db.queryForObject("select sum(delta) from ledger where bank_account='SDD_RECOVERY'", BigDecimal.class))
        .isEqualByComparingTo("-10");
  }

  @Test
  void sddRefundDoesNotConsumeFundsReservedByCreditor() {
    Payment p = directDebit();
    bank.initiate(bob, "reserve", new Initiate(b.id(), a.id(), new BigDecimal("10"), "SCT", "RESERVE", "Alice Doe", null));
    bank.refund(alice, p.id());
    assertThat(bank.account(bob, b.id()).balance()).isEqualByComparingTo("10");
    assertThat(bank.account(bob, b.id()).held()).isEqualByComparingTo("10");
    assertThatThrownBy(() -> bank.recoverSddRefund(ops, p.id(), "recover", new BigDecimal("1")))
        .hasMessage("RECOVERY_LIQUIDITY_REQUIRED");
    assertThat(bank.account(alice, a.id()).balance()).isEqualByComparingTo("100");
  }

  @Test
  void sddRefundRetriesAndConcurrentRequestsCreditExactlyOnce() throws Exception {
    Payment p = directDebit();
    try (var pool = Executors.newFixedThreadPool(6)) {
      List<Future<Payment>> calls = new ArrayList<>();
      for (int i = 0; i < 6; i++) calls.add(pool.submit(() -> bank.refund(alice, p.id())));
      for (var call : calls) assertThat(call.get(15, TimeUnit.SECONDS).status()).isEqualTo("RETURNED");
    }
    when(clock.instant()).thenReturn(now.plus(Duration.ofDays(500)));
    assertThat(bank.refund(alice, p.id()).status()).isEqualTo("RETURNED");
    assertThat(bank.account(alice, a.id()).balance()).isEqualByComparingTo("100");
    assertBalancedBooking("SDD_REFUND:" + p.id());
    assertThat(db.queryForObject("select count(*) from sdd_refund", Long.class)).isEqualTo(1);
    assertThat(db.queryForObject("select count(*) from payment_event where payment_id=? and reason='MD06'", Long.class, p.id())).isEqualTo(1);
  }

  @Test
  void sddRefundUsesDebitDateAndAllowsTheWholeFinalDay() {
    Payment p = directDebit();
    when(clock.instant()).thenReturn(Instant.parse("2026-12-05T23:59:59Z"));
    bank.refund(alice, p.id());
    assertThat(bank.sddRefunds(ops, 0).get(0).debitDate()).isEqualTo(LocalDate.of(2026, 10, 10));
    assertThat(db.queryForObject("select value_date from ledger where booking_id=? and account_id=?",
        java.sql.Date.class, "SDD_REFUND:" + p.id(), a.id()).toLocalDate()).isEqualTo(LocalDate.of(2026, 10, 10));
    assertThat(iso.statement(alice, a.id())).contains("<ValDt><Dt>2026-10-10</Dt></ValDt>");
    assertThat(iso.export(alice, p.id(), "pacs004")).contains("<Rsn><Cd>MD06</Cd></Rsn>");
  }

  @Test
  void refundIsOnlyForPayerIncludingUsersWithOperationsRole() {
    Payment p = directDebit();
    for (Actor attacker : List.of(bob, ops, new Actor("bob", true)))
      assertThatThrownBy(() -> bank.refund(attacker, p.id())).hasMessage("FORBIDDEN");
    assertThatThrownBy(() -> bank.disputeSdd(bob, p.id(), new SddAllegation("NO_MANDATE"))).hasMessage("FORBIDDEN");
    assertThat(bank.account(alice, a.id()).balance()).isEqualByComparingTo("90");
    assertThat(db.queryForObject("select count(*) from sdd_refund", Long.class)).isZero();
  }

  @Test
  void partialRecoveryIsSeparateIdempotentAndBalanced() {
    Payment p = directDebit();
    bank.refund(alice, p.id());
    var first = bank.recoverSddRefund(ops, p.id(), "first", new BigDecimal("4"));
    assertThat(bank.recoverSddRefund(ops, p.id(), "first", new BigDecimal("4"))).isEqualTo(first);
    assertThat(bank.sddRefunds(ops, 0).get(0).outstandingAmount()).isEqualByComparingTo("6");
    assertThatThrownBy(() -> bank.recoverSddRefund(ops, p.id(), "first", new BigDecimal("5"))).hasMessage("IDEMPOTENCY_CONFLICT");
    assertThatThrownBy(() -> bank.recoverSddRefund(ops, p.id(), "over", new BigDecimal("7"))).hasMessage("RECOVERY_EXCEEDS_OUTSTANDING");
    var last = bank.recoverSddRefund(ops, p.id(), "last", new BigDecimal("6"));
    assertBalancedBooking("SDD_RECOVERY:" + first.id());
    assertBalancedBooking("SDD_RECOVERY:" + last.id());
    assertThat(bank.account(alice, a.id()).balance()).isEqualByComparingTo("100");
    assertThat(bank.account(bob, b.id()).balance()).isZero();
    assertThat(bank.sddRefunds(ops, 0).get(0).outstandingAmount()).isZero();
    assertThat(db.queryForObject("select sum(delta) from ledger where bank_account='SDD_RECOVERY'", BigDecimal.class)).isZero();
  }

  @Test
  void creditorCannotRecoverOwnRefundEvenWithOperationsRole() {
    Payment p = directDebit();
    bank.refund(alice, p.id());
    assertThatThrownBy(() -> bank.recoverSddRefund(new Actor("bob", true), p.id(), "self", new BigDecimal("1")))
        .hasMessage("SELF_RECOVERY_FORBIDDEN");
    assertThatThrownBy(() -> bank.recoverSddRefund(alice, p.id(), "customer", new BigDecimal("1")))
        .hasMessage("FORBIDDEN");
  }

  @Test
  void unauthorisedClaimAfterEightWeeksRefundsImmediatelyAndIsIdempotent() {
    Payment p = directDebit();
    when(clock.instant()).thenReturn(now.plus(Duration.ofDays(100)));
    var claim = bank.disputeSdd(alice, p.id(), new SddAllegation("NO_MANDATE"));
    assertThat(claim.status()).isEqualTo("REFUNDED_UNDER_REVIEW");
    assertThat(bank.disputeSdd(alice, p.id(), new SddAllegation("NO_MANDATE"))).isEqualTo(claim);
    assertThat(bank.account(alice, a.id()).balance()).isEqualByComparingTo("100");
    assertThat(bank.sddRefunds(ops, 0).get(0).kind()).isEqualTo("UNAUTHORISED");
    assertThat(bank.payment(alice, p.id()).reason()).isEqualTo("MD01");
    assertThat(iso.export(alice, p.id(), "pacs004")).contains("<Rsn><Cd>MD01</Cd></Rsn>");
    assertThatThrownBy(() -> bank.disputeSdd(alice, p.id(), new SddAllegation("REVOKED_MANDATE")))
        .hasMessage("IDEMPOTENCY_CONFLICT");
    assertBalancedBooking("SDD_REFUND:" + p.id());
  }

  @Test
  void unauthorisedClaimCannotBeUsedForCreditTransfers() {
    Payment p = settle(pay("SCT"));
    assertThatThrownBy(() -> bank.disputeSdd(alice, p.id(), new SddAllegation("NO_MANDATE"))).hasMessage("SDD_ONLY");
    assertThatThrownBy(() -> bank.refund(alice, p.id())).hasMessage("SDD_ONLY");
  }

  @Test
  void unauthorisedWindowUsesCalendarMonthsIncludingLeapYearEnd() {
    when(clock.instant()).thenReturn(Instant.parse("2024-01-31T12:00:00Z"));
    Payment p = directDebit();
    when(clock.instant()).thenReturn(Instant.parse("2025-02-28T23:59:59Z"));
    assertThat(bank.disputeSdd(alice, p.id(), new SddAllegation("NO_MANDATE")).status()).isEqualTo("REFUNDED_UNDER_REVIEW");
  }

  @Test
  void unauthorisedClaimPastThirteenMonthsHasNoFinancialSideEffects() {
    Payment p = directDebit();
    when(clock.instant()).thenReturn(Instant.parse("2027-11-11T00:00:00Z"));
    assertThatThrownBy(() -> bank.disputeSdd(alice, p.id(), new SddAllegation("NO_MANDATE"))).hasMessage("REFUND_WINDOW_EXPIRED");
    assertThat(bank.account(alice, a.id()).balance()).isEqualByComparingTo("90");
    assertThat(db.queryForObject("select count(*) from sdd_refund", Long.class)).isZero();
    assertThat(db.queryForObject("select count(*) from sdd_dispute", Long.class)).isZero();
    assertThat(bank.payment(alice, p.id()).status()).isEqualTo("SETTLED");
  }

  @Test
  void investigationNeedsEvidenceAndNeverDebitsPayerAutomatically() {
    Payment p = directDebit();
    var claim = bank.disputeSdd(alice, p.id(), new SddAllegation("NO_MANDATE"));
    assertThatThrownBy(() -> bank.decideSddDispute(ops, claim.id(), new SddDecision("AUTHORISATION_EVIDENCE_FOUND", null)))
        .hasMessage("EVIDENCE_REQUIRED");
    var decision = bank.decideSddDispute(ops, claim.id(), new SddDecision("AUTHORISATION_EVIDENCE_FOUND", "a".repeat(64)));
    assertThat(decision.status()).isEqualTo("AUTHORISATION_EVIDENCE_FOUND");
    assertThat(bank.decideSddDispute(ops, claim.id(), new SddDecision("AUTHORISATION_EVIDENCE_FOUND", "a".repeat(64))))
        .isEqualTo(decision);
    assertThatThrownBy(() -> bank.decideSddDispute(ops, claim.id(), new SddDecision("CONFIRMED_UNAUTHORISED", null)))
        .hasMessage("DISPUTE_ALREADY_DECIDED");
    assertThat(bank.account(alice, a.id()).balance()).isEqualByComparingTo("100");
    assertThat(db.queryForObject("select count(*) from audit_log where action='SDD_AUTHORISATION_EVIDENCE_FOUND'", Long.class)).isEqualTo(1);
  }

  @Test
  void disputeReviewAndListsRespectRolesAndSeparationOfDuties() {
    Payment p = directDebit();
    var claim = bank.disputeSdd(alice, p.id(), new SddAllegation("NO_MANDATE"));
    for (Actor self : List.of(new Actor("alice", true), new Actor("bob", true)))
      assertThatThrownBy(() -> bank.decideSddDispute(self, claim.id(), new SddDecision("CONFIRMED_UNAUTHORISED", null)))
          .hasMessage("SELF_REVIEW_FORBIDDEN");
    assertThatThrownBy(() -> bank.sddDisputes(alice, 0)).hasMessage("FORBIDDEN");
    assertThatThrownBy(() -> bank.sddRefunds(bob, 0)).hasMessage("FORBIDDEN");
    assertThatThrownBy(() -> bank.sddDisputes(ops, -1)).hasMessage("INVALID_PAGE");
    assertThat(bank.sddDisputes(ops, 0)).containsExactly(claim);
    assertThat(bank.sddDispute(alice, p.id())).isEqualTo(claim);
    assertThatThrownBy(() -> bank.sddDispute(bob, p.id())).hasMessage("FORBIDDEN");
    assertThat(bank.sddDisputes(ops, 1)).isEmpty();
  }

  @Test
  void failedDisputeAuditRollsBackCreditJournalCaseAndOutbox() {
    Payment p = directDebit();
    long events = db.queryForObject("select count(*) from outbox", Long.class);
    db.execute("alter table audit_log add constraint sdd_test_audit_fail check(action <> 'SDD_DISPUTE_OPENED')");
    try {
      assertThatThrownBy(() -> bank.disputeSdd(alice, p.id(), new SddAllegation("NO_MANDATE")))
          .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    } finally { db.execute("alter table audit_log drop constraint sdd_test_audit_fail"); }
    assertThat(bank.account(alice, a.id()).balance()).isEqualByComparingTo("90");
    assertThat(bank.payment(alice, p.id()).status()).isEqualTo("SETTLED");
    assertThat(db.queryForObject("select count(*) from sdd_refund", Long.class)).isZero();
    assertThat(db.queryForObject("select count(*) from sdd_dispute", Long.class)).isZero();
    assertThat(db.queryForObject("select count(*) from ledger where booking_id=?", Long.class, "SDD_REFUND:" + p.id())).isZero();
    assertThat(db.queryForObject("select count(*) from outbox", Long.class)).isEqualTo(events);
  }

  @Test
  void competingRefundAndDisputeCannotCreditTwice() throws Exception {
    Payment p = directDebit();
    try (var pool = Executors.newFixedThreadPool(2)) {
      var regular = pool.submit(() -> bank.refund(alice, p.id()));
      var disputed = pool.submit(() -> {
        try { return bank.disputeSdd(alice, p.id(), new SddAllegation("NO_MANDATE")); }
        catch (BankError e) { assertThat(e.code).isEqualTo("INVALID_TRANSITION"); return null; }
      });
      regular.get(15, TimeUnit.SECONDS);
      disputed.get(15, TimeUnit.SECONDS);
    }
    assertThat(bank.account(alice, a.id()).balance()).isEqualByComparingTo("100");
    assertBalancedBooking("SDD_REFUND:" + p.id());
    assertThat(db.queryForObject("select count(*) from sdd_refund", Long.class)).isEqualTo(1);
  }

  @Test
  void consentIsBoundToTppExpiresAndCanBeRevoked() {
    String c = bank.consent(alice, new NewConsent(a.id(), "tpp", now.plusSeconds(60)));
    assertThat(bank.openBanking("tpp", c).id()).isEqualTo(a.id());
    assertThatThrownBy(() -> bank.openBanking("other", c)).hasMessage("INVALID_CONSENT");
    when(clock.instant()).thenReturn(now.plusSeconds(60));
    assertThatThrownBy(() -> bank.openBanking("tpp", c)).hasMessage("INVALID_CONSENT");
    when(clock.instant()).thenReturn(now);
    bank.revokeConsent(alice, c);
    assertThatThrownBy(() -> bank.openBanking("tpp", c)).hasMessage("INVALID_CONSENT");
  }

  @Test
  void reconciliationFindsDifferences() {
    Payment p = settle(pay("SCT"));
    var result =
        bank.reconcile(
            ops,
            List.of(
                new ReconcileLine(p.id(), p.amount(), "SETTLED"),
                new ReconcileLine(p.id(), p.amount(), "SETTLED"),
                new ReconcileLine("missing", p.amount(), "SETTLED")));
    assertThat(result)
        .extracting(r -> r.get("outcome"))
        .containsExactly("MATCHED", "DUPLICATE", "MISSING");
    assertThat(
            bank.reconcile(ops, List.of(new ReconcileLine(p.id(), new BigDecimal("11"), "SETTLED")))
                .get(0)
                .get("outcome"))
        .isEqualTo("AMOUNT_MISMATCH");
  }

  @Test
  void duplicateAndOutOfOrderEventsDoNotRegressProjection() {
    Payment p = settle(pay("SCT"));
    var events =
        db.queryForList(
            "select payload from outbox where payment_id=? order by id", String.class, p.id());
    for (String payload : events) consumer.accept(payload);
    for (String payload : events) consumer.accept(payload);
    assertThat(db.queryForObject("select count(*) from inbox", Long.class)).isEqualTo(6);
    assertThat(projections.findById(p.id()).orElseThrow().status).isEqualTo("SETTLED");
  }

  @Test
  void concurrentDuplicateCreatesOnePayment() throws Exception {
    var pool = Executors.newFixedThreadPool(4);
    try {
      List<Future<Payment>> futures = new ArrayList<>();
      for (int i = 0; i < 4; i++) futures.add(pool.submit(() -> pay("SCT")));
      Set<String> ids = new HashSet<>();
      for (var f : futures) ids.add(f.get(15, TimeUnit.SECONDS).id());
      assertThat(ids).hasSize(1);
      assertThat(bank.account(alice, a.id()).held()).isEqualByComparingTo("10");
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void concurrentPaymentsCannotOverspend() throws Exception {
    var pool = Executors.newFixedThreadPool(2);
    try {
      List<Future<Boolean>> futures = new ArrayList<>();
      for (int i = 0; i < 2; i++) {
        String key = "key" + i;
        futures.add(
            pool.submit(
                () -> {
                  try {
                    bank.initiate(
                        alice,
                        key,
                        new Initiate(
                            a.id(), b.id(), new BigDecimal("80"), "SCT", "E2E", "Bob & Co", null));
                    return true;
                  } catch (BankError e) {
                    assertThat(e.code).isEqualTo("INSUFFICIENT_FUNDS");
                    return false;
                  }
                }));
      }
      int accepted = 0;
      for (var f : futures) if (f.get(15, TimeUnit.SECONDS)) accepted++;
      assertThat(accepted).isEqualTo(1);
      assertThat(bank.account(alice, a.id()).held()).isEqualByComparingTo("80");
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void isoRoundtripAndInvalidInput() {
    Payment p = settle(pay("SCT"));
    String xml = iso.export(alice, p.id(), "pain001");
    Payment imported = iso.importPain(alice, "xml", xml);
    assertThat(imported.amount()).isEqualByComparingTo("10");
    for (String type : List.of("pacs008", "pacs002"))
      assertThat(iso.export(alice, p.id(), type)).contains("Document");
    iso.validate(iso.statement(alice, a.id()), "camt.053.001.08");
    assertThatThrownBy(
            () -> iso.importPain(alice, "bad", xml.replace("Ccy=\"EUR\"", "Ccy=\"USD\"")))
        .hasMessage("EUR_ONLY");
    assertThatThrownBy(
            () ->
                iso.importPain(
                    alice,
                    "bad",
                    xml.replace("<CtrlSum>10.00</CtrlSum>", "<CtrlSum>11.00</CtrlSum>")))
        .hasMessage("CONTROL_SUM_MISMATCH");
    assertThatThrownBy(
            () ->
                iso.validate(
                    "<!DOCTYPE x [<!ENTITY e SYSTEM"
                        + " 'file:///etc/passwd'>]><Document>&e;</Document>",
                    "pain.001.001.09"))
        .isInstanceOf(BankError.class);
  }

  @Test
  void mtPreviewRefusesLossyUnknownFields() {
    String mt =
        ":20:TEST\n"
            + ":23B:CRED\n"
            + ":32A:261010EUR10,00\n"
            + ":50K:/DE89370400440532013000\n"
            + "Alice Doe\n"
            + ":59:/NL91ABNA0417164300\n"
            + "Bob & Co\n"
            + ":71A:SHA";
    assertThat(iso.previewMt(mt).get("amount")).isEqualTo("10.00");
    assertThatThrownBy(() -> iso.previewMt(mt + "\n:70:ignored data"))
        .hasMessage("UNSUPPORTED_MT103_PROFILE");
  }
}
