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
            alice, new NewMandate(a.id(), b.id(), LocalDate.of(2027, 1, 1), new BigDecimal("20")));
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
    assertThatThrownBy(() -> bank.refund(alice, p.id())).hasMessage("REFUND_NOT_SUPPORTED");
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
