package dev.eufin.domain;

import static dev.eufin.domain.Models.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Command side: locks, balances, journal and outbox commit together. */
@Service
public class BankService {
  private final dev.eufin.customers.CustomerService customers;
  private final JdbcTemplate db;
  private final Clock clock;
  private final ObjectMapper json;
  private final dev.eufin.clearing.ClearingGateway clearing;

  public BankService(
      JdbcTemplate db,
      Clock clock,
      ObjectMapper json,
      dev.eufin.clearing.ClearingGateway clearing,
      dev.eufin.customers.CustomerService customers) {
    this.customers = customers;
    this.db = db;
    this.clock = clock;
    this.json = json;
    this.clearing = clearing;
  }

  private String id() {
    return UUID.randomUUID().toString();
  }

  private static final RowMapper<Account> ACCOUNT =
      (r, n) ->
          new Account(
              r.getString("id"),
              r.getString("iban"),
              r.getString("owner_id"),
              r.getString("name"),
              r.getBigDecimal("balance"),
              r.getBigDecimal("held"));

  private static Instant instant(java.sql.ResultSet r, String key) throws java.sql.SQLException {
    var t = r.getTimestamp(key);
    return t == null ? null : t.toInstant();
  }

  private static final RowMapper<Payment> PAYMENT =
      (r, n) ->
          new Payment(
              r.getString("id"),
              r.getString("actor"),
              r.getString("debtor_id"),
              r.getString("creditor_id"),
              r.getBigDecimal("amount"),
              r.getString("scheme"),
              r.getString("end_to_end_id"),
              r.getString("mandate_id"),
              r.getString("status"),
              r.getString("route"),
              r.getString("reason"),
              instant(r, "created_at"),
              instant(r, "deadline"),
              instant(r, "settled_at"));

  private Account loadAccount(String id, boolean lock) {
    return db
        .query("select * from account where id=?" + (lock ? " for update" : ""), ACCOUNT, id)
        .stream()
        .findFirst()
        .orElseThrow(() -> new BankError("ACCOUNT_NOT_FOUND", 404));
  }

  public String verifyPayee(String iban, String name) {
    iban = dev.eufin.domain.Rules.iban(iban);
    var matches = db.query("select * from account where iban=?", ACCOUNT, iban);
    if (matches.isEmpty() || !matches.get(0).name().equalsIgnoreCase(name.strip()))
      throw new BankError("PAYEE_NOT_MATCHED", 422);
    return matches.get(0).id();
  }

  private Payment loadPayment(String id, boolean lock) {
    return db
        .query("select * from payment where id=?" + (lock ? " for update" : ""), PAYMENT, id)
        .stream()
        .findFirst()
        .orElseThrow(() -> new BankError("PAYMENT_NOT_FOUND", 404));
  }

  private void own(Actor actor, Account account) {
    if (!actor.subject().equals(account.ownerId()) && !actor.operator())
      throw new BankError("FORBIDDEN", 403);
  }

  private void operator(Actor actor) {
    if (!actor.operator()) throw new BankError("FORBIDDEN", 403);
  }

  private void lockAccounts(String a, String b) {
    for (String key : new TreeSet<>(List.of(a, b))) loadAccount(key, true);
  }

  @Transactional
  public Account open(Actor actor, OpenAccount request) {
    customers.requireActive(actor.subject(), false);
    if (!Rules.nameMatches(request.name(), customers.legalName(actor.subject())))
      throw BankError.invalid("ACCOUNT_HOLDER_NAME_MISMATCH");
    String iban = Rules.iban(request.iban());
    String id = id();
    if (request.name() == null || request.name().isBlank()) throw BankError.invalid("INVALID_NAME");
    db.update(
        "insert into account(id,iban,owner_id,name) values(?,?,?,?)",
        id,
        iban,
        actor.subject(),
        request.name());
    return loadAccount(id, false);
  }

  @Transactional(readOnly = true)
  public Account account(Actor actor, String id) {
    Account a = loadAccount(id, false);
    own(actor, a);
    return a;
  }

  @Transactional(readOnly = true)
  public List<Account> accounts(Actor actor) {
    return db.query("select * from account where owner_id=? order by id", ACCOUNT, actor.subject());
  }

  @Transactional
  public Account fund(Actor actor, String id, String key, BigDecimal raw) {
    operator(actor);
    validKey(key);
    BigDecimal amount = Rules.money(raw);
    Account a = loadAccount(id, true);
    String booking = "FUND:" + id + ":" + Rules.fingerprint(key);
    var old =
        db.queryForList(
            "select delta from ledger where booking_id=? and account_id=?", booking, id);
    if (!old.isEmpty()) {
      if (((BigDecimal) old.get(0).get("delta")).compareTo(amount) != 0)
        throw new BankError("IDEMPOTENCY_CONFLICT", 409);
      return a;
    }
    db.update("update account set balance=balance+?,version=version+1 where id=?", amount, id);
    journal(booking, null, id, amount);
    return loadAccount(id, false);
  }

  private void validKey(String key) {
    if (key == null || !key.matches("[A-Za-z0-9._:-]{1,100}"))
      throw BankError.invalid("INVALID_IDEMPOTENCY_KEY");
  }

  @Transactional
  public Payment initiate(Actor actor, String key, Initiate raw) {
    validKey(key);
    BigDecimal amount = Rules.money(raw.amount());
    if (raw.debtorId().equals(raw.creditorId())) throw BankError.invalid("SAME_ACCOUNT");
    Account beforeDebtor = loadAccount(raw.debtorId(), false),
        beforeCreditor = loadAccount(raw.creditorId(), false);
    own(actor, "SDD_CORE".equals(raw.scheme()) ? beforeCreditor : beforeDebtor);
    for (String subject : new TreeSet<>(List.of(beforeDebtor.ownerId(), beforeCreditor.ownerId())))
      customers.requireActive(subject, true);
    lockAccounts(raw.debtorId(), raw.creditorId());
    Account debtor = loadAccount(raw.debtorId(), false),
        creditor = loadAccount(raw.creditorId(), false);
    boolean directDebit = "SDD_CORE".equals(raw.scheme());
    own(actor, directDebit ? creditor : debtor);

    Initiate req =
        new Initiate(
            raw.debtorId(),
            raw.creditorId(),
            amount,
            raw.scheme(),
            raw.endToEndId(),
            raw.creditorName(),
            raw.mandateId());
    String fingerprint = Rules.fingerprint(req);
    var old =
        db.query(
            "select * from payment where actor=? and idem_key=?", PAYMENT, actor.subject(), key);
    if (!old.isEmpty()) {
      String prev =
          db.queryForObject(
              "select fingerprint from payment where id=?", String.class, old.get(0).id());
      if (!fingerprint.equals(prev)) throw new BankError("IDEMPOTENCY_CONFLICT", 409);
      return old.get(0);
    }
    String route = Rules.route(req.scheme(), creditor.iban());
    Rules.sepaReference(req.endToEndId());
    Rules.sepaName(debtor.name());
    Rules.sepaName(creditor.name());
    if (req.creditorName() == null || !Rules.nameMatches(req.creditorName(), creditor.name()))
      throw BankError.invalid("PAYEE_NAME_MISMATCH");
    if (directDebit) {
      if (req.mandateId() == null) throw BankError.invalid("MANDATE_REQUIRED");
      var mandates =
          db.queryForList(
              "select * from mandate where id=? and debtor_id=? and creditor_id=? and active=true"
                  + " and valid_until>=? for update",
              req.mandateId(),
              debtor.id(),
              creditor.id(),
              java.sql.Date.valueOf(LocalDate.now(clock)));
      if (mandates.isEmpty()
          || ((BigDecimal) mandates.get(0).get("max_amount")).compareTo(amount) < 0)
        throw BankError.invalid("INVALID_MANDATE");
      var mandate = mandates.get(0);
      Object last = mandate.get("last_presented_date");
      if (last != null && !LocalDate.now(clock).isBefore(((java.sql.Date) last).toLocalDate().plusMonths(36)))
        throw BankError.invalid("MANDATE_INACTIVE");
      String sequence = (String) mandate.get("sequence_type");
      if (Set.of("OOFF", "FNAL").contains(sequence == null ? "" : sequence)) {
        Integer used = db.queryForObject("select count(*) from payment where mandate_id=? and status<>'REJECTED'", Integer.class, req.mandateId());
        if (used != null && used > 0) throw new BankError("MANDATE_ALREADY_USED", 409);
      }
    } else if (req.mandateId() != null) throw BankError.invalid("UNEXPECTED_MANDATE");
    if (debtor.available().compareTo(amount) < 0) throw BankError.invalid("INSUFFICIENT_FUNDS");
    Instant now = clock.instant();
    String id = id();
    Instant deadline = "SCT_INST".equals(req.scheme()) ? now.plusSeconds(10) : null;
    db.update(
        "insert into"
            + " payment(id,actor,idem_key,fingerprint,debtor_id,creditor_id,amount,scheme,end_to_end_id,mandate_id,status,route,created_at,deadline)"
            + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        id,
        actor.subject(),
        key,
        fingerprint,
        debtor.id(),
        creditor.id(),
        amount,
        req.scheme(),
        req.endToEndId(),
        req.mandateId(),
        "RECEIVED",
        route,
        now.atOffset(ZoneOffset.UTC),
        deadline == null ? null : deadline.atOffset(ZoneOffset.UTC));
    if (directDebit) {
      db.update("update payment set collection_date=?,collection_sequence=(select sequence_type from mandate where id=?) where id=?",
          java.sql.Date.valueOf(LocalDate.now(clock)), req.mandateId(), id);
      db.update("update mandate set last_presented_date=?,sequence_type=case when sequence_type='FRST' then 'RCUR' else sequence_type end where id=?",
          java.sql.Date.valueOf(LocalDate.now(clock)), req.mandateId());
    }
    db.update("update account set held=held+?,version=version+1 where id=?", amount, debtor.id());
    event(id, "RECEIVED", null);
    change(id, "VALIDATED", null);
    return loadPayment(id, false);
  }

  @Transactional(readOnly = true)
  public Payment payment(Actor actor, String id) {
    Payment p = loadPayment(id, false);
    if (!actor.operator()
        && !loadAccount(p.debtorId(), false).ownerId().equals(actor.subject())
        && !loadAccount(p.creditorId(), false).ownerId().equals(actor.subject()))
      throw new BankError("FORBIDDEN", 403);
    return p;
  }

  private void require(Payment p, String... states) {
    if (Arrays.stream(states).noneMatch(p.status()::equals))
      throw new BankError("INVALID_TRANSITION", 409);
  }

  @Transactional
  public Payment transition(Actor actor, String id, String action) {
    operator(actor);
    Payment p = loadPayment(id, true);
    lockAccounts(p.debtorId(), p.creditorId());
    if (p.deadline() != null
        && !clock.instant().isBefore(p.deadline())
        && Set.of("VALIDATED", "ROUTED", "CLEARED").contains(p.status())) {
      release(p);
      change(id, "TIMED_OUT", "AB05");
      return loadPayment(id, false);
    }
    if (p.scheme().equals("SDD_CORE") && Set.of("SCREEN", "CLEAR", "SETTLE").contains(action)
        && Set.of("VALIDATED", "ROUTED", "CLEARED").contains(p.status())) {
      var mandates = db.queryForList("select * from mandate where id=? for update", p.mandateId());
      if (mandates.isEmpty() || !Boolean.TRUE.equals(mandates.get(0).get("active"))
          || ((java.sql.Date) mandates.get(0).get("valid_until")).toLocalDate().isBefore(LocalDate.now(clock))) {
        release(p);
        change(id, "REJECTED", "MD01");
        return loadPayment(id, false);
      }
    }
    switch (action) {
      case "SCREEN" -> {
        require(p, "VALIDATED");
        Account d = loadAccount(p.debtorId(), false), c = loadAccount(p.creditorId(), false);
        if (d.name().startsWith("SIM_BLOCKED") || c.name().startsWith("SIM_BLOCKED")) {
          release(p);
          change(id, "REJECTED", "SIM_SANCTIONS");
        } else {
          event(id, "SCREENED", null);
          change(id, "ROUTED", null);
        }
      }
      case "CLEAR" -> {
        require(p, "ROUTED");
        clearing.clear(p.route());
        change(id, "CLEARED", null);
      }
      case "SETTLE" -> {
        require(p, "CLEARED");
        release(p);
        db.update(
            "update account set balance=balance-?,version=version+1 where id=?",
            p.amount(),
            p.debtorId());
        db.update(
            "update account set balance=balance+?,version=version+1 where id=?",
            p.amount(),
            p.creditorId());
        journal("PAY:" + id, p.debtorId(), p.creditorId(), p.amount());
        db.update("update payment set settled_at=? where id=?", clock.instant().atOffset(ZoneOffset.UTC), id);
        change(id, "SETTLED", null);
      }
      case "REJECT" -> {
        require(p, "VALIDATED", "ROUTED", "CLEARED");
        release(p);
        change(id, "REJECTED", "SIM_REJECT");
      }
      case "RETURN" -> {
        require(p, "SETTLED", "RECALL_REQUESTED");
        reverse(p);
      }
      case "RECALL" -> {
        require(p, "SETTLED");
        change(id, "RECALL_REQUESTED", "SIM_RECALL");
      }
      case "TIMEOUT" -> {
        require(p, "VALIDATED", "ROUTED", "CLEARED");
        if (p.deadline() == null || clock.instant().isBefore(p.deadline()))
          throw new BankError("NOT_EXPIRED", 409);
        release(p);
        change(id, "TIMED_OUT", "AB05");
      }
      default -> throw BankError.invalid("UNKNOWN_ACTION");
    }
    return loadPayment(id, false);
  }

  /** Payer reimbursement and recovery from the creditor are independent transactions. */
  @Transactional
  public Payment refund(Actor actor, String id) {
    Payment p = loadPayment(id, true);
    requireSddPayer(actor, p);
    if (db.queryForObject("select count(*) from sdd_refund where payment_id=?", Long.class, id) > 0)
      return p; // Replay remains safe even after the refund window expires.
    creditSddRefund(p, "EIGHT_WEEK");
    return loadPayment(id, false);
  }

  private void requireSddPayer(Actor actor, Payment p) {
    // An operations role must not bypass ownership on a payer request.
    if (!loadAccount(p.debtorId(), false).ownerId().equals(actor.subject()))
      throw new BankError("FORBIDDEN", 403);
    if (!"SDD_CORE".equals(p.scheme())) throw BankError.invalid("SDD_ONLY");
  }

  private void creditSddRefund(Payment p, String kind) {
    require(p, "SETTLED");
    LocalDate debitDate = LocalDate.ofInstant(p.settledAt(), ZoneOffset.UTC);
    LocalDate deadline = kind.equals("EIGHT_WEEK") ? debitDate.plusWeeks(8) : debitDate.plusMonths(13);
    if (LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).isAfter(deadline))
      throw BankError.invalid("REFUND_WINDOW_EXPIRED");
    lockAccounts(p.debtorId(), p.creditorId());
    db.update("insert into sdd_refund(payment_id,creditor_id,amount,kind,debit_date,created_at) values(?,?,?,?,?,?)",
        p.id(), p.creditorId(), p.amount(), kind, java.sql.Date.valueOf(debitDate), clock.instant().atOffset(ZoneOffset.UTC));
    db.update("update account set balance=balance+?,version=version+1 where id=?", p.amount(), p.debtorId());
    journal("SDD_REFUND:" + p.id(), null, p.debtorId(), p.amount(), debitDate, "SDD_RECOVERY");
    change(p.id(), "RETURNED", kind.equals("EIGHT_WEEK") ? "MD06" : "MD01");
  }

  private static final RowMapper<SddDispute> SDD_DISPUTE = (r, n) -> new SddDispute(
      r.getString("id"), r.getString("payment_id"), r.getString("claimant"), r.getString("allegation"),
      r.getString("status"), instant(r, "created_at"), r.getString("reviewed_by"),
      instant(r, "reviewed_at"), r.getString("evidence_hash"));

  private static final RowMapper<SddRefund> SDD_REFUND = (r, n) -> new SddRefund(
      r.getString("payment_id"), r.getString("creditor_id"), r.getBigDecimal("amount"),
      r.getBigDecimal("recovered_amount"), r.getBigDecimal("amount").subtract(r.getBigDecimal("recovered_amount")),
      r.getString("kind"), r.getDate("debit_date").toLocalDate(), instant(r, "created_at"));

  private static final RowMapper<SddRecovery> SDD_RECOVERY = (r, n) -> new SddRecovery(
      r.getString("id"), r.getString("payment_id"), r.getBigDecimal("amount"), instant(r, "created_at"));

  @Transactional
  public SddDispute disputeSdd(Actor actor, String id, SddAllegation req) {
    Payment p = loadPayment(id, true);
    requireSddPayer(actor, p);
    if (req == null || !Set.of("NO_MANDATE", "REVOKED_MANDATE", "OTHER_UNAUTHORISED")
        .contains(req.allegation() == null ? "" : req.allegation())) throw BankError.invalid("INVALID_ALLEGATION");
    var previous = db.query("select * from sdd_dispute where payment_id=?", SDD_DISPUTE, id);
    if (!previous.isEmpty()) {
      if (!previous.get(0).allegation().equals(req.allegation())) throw new BankError("IDEMPOTENCY_CONFLICT", 409);
      return previous.get(0);
    }
    creditSddRefund(p, "UNAUTHORISED"); // Credit now; investigation must not delay reimbursement.
    String caseId = id();
    db.update("insert into sdd_dispute(id,payment_id,claimant,allegation,status,created_at) values(?,?,?,?,?,?)",
        caseId, id, actor.subject(), req.allegation(), "REFUNDED_UNDER_REVIEW", clock.instant().atOffset(ZoneOffset.UTC));
    auditSdd(actor.subject(), actor.subject(), "SDD_DISPUTE_OPENED");
    return db.queryForObject("select * from sdd_dispute where id=?", SDD_DISPUTE, caseId);
  }

  @Transactional(readOnly = true)
  public SddDispute sddDispute(Actor actor, String id) {
    requireSddPayer(actor, loadPayment(id, false));
    return db.query("select * from sdd_dispute where payment_id=?", SDD_DISPUTE, id).stream().findFirst()
        .orElseThrow(() -> new BankError("DISPUTE_NOT_FOUND", 404));
  }

  @Transactional
  public SddDispute decideSddDispute(Actor actor, String caseId, SddDecision req) {
    operator(actor);
    SddDispute claim = db.query("select * from sdd_dispute where id=? for update", SDD_DISPUTE, caseId)
        .stream().findFirst().orElseThrow(() -> new BankError("DISPUTE_NOT_FOUND", 404));
    Payment p = loadPayment(claim.paymentId(), false);
    if (actor.subject().equals(claim.claimant()) || actor.subject().equals(loadAccount(p.creditorId(), false).ownerId()))
      throw new BankError("SELF_REVIEW_FORBIDDEN", 403);
    if (req == null || !Set.of("CONFIRMED_UNAUTHORISED", "AUTHORISATION_EVIDENCE_FOUND")
        .contains(req.decision() == null ? "" : req.decision())) throw BankError.invalid("INVALID_DISPUTE_DECISION");
    String evidence = req.evidenceHash() == null ? null : req.evidenceHash().toLowerCase(Locale.ROOT);
    if ((evidence != null && !evidence.matches("[a-f0-9]{64}"))
        || (req.decision().equals("AUTHORISATION_EVIDENCE_FOUND") && evidence == null))
      throw BankError.invalid("EVIDENCE_REQUIRED");
    if (!claim.status().equals("REFUNDED_UNDER_REVIEW")) {
      if (claim.status().equals(req.decision()) && Objects.equals(claim.evidenceHash(), evidence)) return claim;
      throw new BankError("DISPUTE_ALREADY_DECIDED", 409);
    }
    db.update("update sdd_dispute set status=?,reviewed_by=?,reviewed_at=?,evidence_hash=? where id=?",
        req.decision(), actor.subject(), clock.instant().atOffset(ZoneOffset.UTC), evidence, caseId);
    auditSdd(actor.subject(), claim.claimant(), "SDD_" + req.decision());
    // Finding evidence is not permission to debit the payer again.
    return db.queryForObject("select * from sdd_dispute where id=?", SDD_DISPUTE, caseId);
  }

  private void auditSdd(String actor, String subject, String action) {
    db.update("insert into audit_log(id,actor,subject,action,occurred_at) values(?,?,?,?,?)",
        id(), actor, subject, action, clock.instant().atOffset(ZoneOffset.UTC));
  }

  @Transactional(readOnly = true)
  public List<SddDispute> sddDisputes(Actor actor, int page) {
    operator(actor);
    if (page < 0 || page > 10000) throw BankError.invalid("INVALID_PAGE");
    return db.query("select * from sdd_dispute order by created_at,id limit 50 offset ?", SDD_DISPUTE, page * 50);
  }

  @Transactional(readOnly = true)
  public List<SddRefund> sddRefunds(Actor actor, int page) {
    operator(actor);
    if (page < 0 || page > 10000) throw BankError.invalid("INVALID_PAGE");
    return db.query("select * from sdd_refund order by created_at,payment_id limit 50 offset ?", SDD_REFUND, page * 50);
  }

  @Transactional
  public SddRecovery recoverSddRefund(Actor actor, String paymentId, String key, BigDecimal rawAmount) {
    operator(actor);
    if (key == null || key.isBlank() || key.length() > 100) throw BankError.invalid("INVALID_IDEMPOTENCY_KEY");
    BigDecimal amount = Rules.money(rawAmount);
    Payment p = loadPayment(paymentId, true);
    lockAccounts(p.debtorId(), p.creditorId());
    if (actor.subject().equals(loadAccount(p.debtorId(), false).ownerId())
        || actor.subject().equals(loadAccount(p.creditorId(), false).ownerId())) throw new BankError("SELF_RECOVERY_FORBIDDEN", 403);
    var previous = db.query("select * from sdd_recovery where actor=? and idem_key=?",
        SDD_RECOVERY,
        actor.subject(), key);
    if (!previous.isEmpty()) {
      SddRecovery old = previous.get(0);
      if (!old.paymentId().equals(paymentId) || old.amount().compareTo(amount) != 0)
        throw new BankError("IDEMPOTENCY_CONFLICT", 409);
      return old;
    }
    SddRefund refund = db.query("select * from sdd_refund where payment_id=? for update", SDD_REFUND, paymentId)
        .stream().findFirst().orElseThrow(() -> new BankError("REFUND_NOT_FOUND", 404));
    if (amount.compareTo(refund.outstandingAmount()) > 0) throw BankError.invalid("RECOVERY_EXCEEDS_OUTSTANDING");
    if (loadAccount(p.creditorId(), false).available().compareTo(amount) < 0)
      throw BankError.invalid("RECOVERY_LIQUIDITY_REQUIRED");
    String recoveryId = id();
    db.update("insert into sdd_recovery(id,payment_id,actor,idem_key,amount,created_at) values(?,?,?,?,?,?)",
        recoveryId, paymentId, actor.subject(), key, amount, clock.instant().atOffset(ZoneOffset.UTC));
    db.update("update account set balance=balance-?,version=version+1 where id=?", amount, p.creditorId());
    db.update("update sdd_refund set recovered_amount=recovered_amount+? where payment_id=?", amount, paymentId);
    journal("SDD_RECOVERY:" + recoveryId, p.creditorId(), null, amount,
        LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC), "SDD_RECOVERY");
    event(paymentId, "RETURNED", "REFUND_RECOVERY");
    auditSdd(actor.subject(), loadAccount(p.creditorId(), false).ownerId(), "SDD_REFUND_RECOVERED");
    return db.queryForObject("select * from sdd_recovery where id=?", SDD_RECOVERY, recoveryId);
  }

  private void reverse(Payment p) {
    if (loadAccount(p.creditorId(), false).available().compareTo(p.amount()) < 0)
      throw BankError.invalid("RETURN_LIQUIDITY_REQUIRED");
    db.update(
        "update account set balance=balance-?,version=version+1 where id=?",
        p.amount(),
        p.creditorId());
    db.update(
        "update account set balance=balance+?,version=version+1 where id=?",
        p.amount(),
        p.debtorId());
    journal("RETURN:" + p.id(), p.creditorId(), p.debtorId(), p.amount());
    change(p.id(), "RETURNED", "SIM_RETURN");
  }

  private void release(Payment p) {
    db.update(
        "update account set held=held-?,version=version+1 where id=?", p.amount(), p.debtorId());
  }

  private void journal(String booking, String from, String to, BigDecimal amount) {
    journal(booking, from, to, amount, LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC), "TREASURY");
  }

  private void journal(String booking, String from, String to, BigDecimal amount,
      LocalDate valueDate, String bankAccount) {
    Instant now = clock.instant();
    db.update(
        "insert into ledger(id,booking_id,account_id,delta,booked_at,value_date,bank_account) values(?,?,?,?,?,?,?)",
        id(),
        booking,
        from,
        amount.negate(),
        now.atOffset(ZoneOffset.UTC), java.sql.Date.valueOf(valueDate), from == null ? bankAccount : null);
    db.update(
        "insert into ledger(id,booking_id,account_id,delta,booked_at,value_date,bank_account) values(?,?,?,?,?,?,?)",
        id(),
        booking,
        to,
        amount,
        now.atOffset(ZoneOffset.UTC), java.sql.Date.valueOf(valueDate), to == null ? bankAccount : null);
  }

  private void change(String id, String status, String reason) {
    db.update("update payment set status=?,reason=? where id=?", status, reason, id);
    event(id, status, reason);
  }

  private void event(String payment, String status, String reason) {
    String id = id();
    Long seq =
        db.queryForObject(
            "select coalesce(max(seq),0)+1 from payment_event where payment_id=?",
            Long.class,
            payment);
    Instant now = clock.instant();
    db.update(
        "insert into payment_event(id,payment_id,seq,status,reason,occurred_at)"
            + " values(?,?,?,?,?,?)",
        id,
        payment,
        seq,
        status,
        reason,
        now.atOffset(ZoneOffset.UTC));
    try {
      String payload =
          json.writeValueAsString(
              Map.of(
                  "eventId",
                  id,
                  "paymentId",
                  payment,
                  "sequence",
                  seq,
                  "status",
                  status,
                  "occurredAt",
                  now.toString()));
      db.update("insert into outbox(id,payment_id,payload) values(?,?,?)", id, payment, payload);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @Transactional(readOnly = true)
  public List<Map<String, Object>> history(Actor actor, String id) {
    payment(actor, id);
    return dev.eufin.util.SqlRows.normalized(
        db.queryForList(
            "select seq,status,reason,occurred_at from payment_event where payment_id=? order by"
                + " seq",
            id));
  }

  @Transactional(readOnly = true)
  public List<Map<String, Object>> statement(Actor actor, String id) {
    account(actor, id);
    return dev.eufin.util.SqlRows.normalized(
        db.queryForList(
            "select booking_id,delta,booked_at,value_date from ledger where account_id=? order by"
                + " booked_at,id",
            id));
  }

  @Transactional
  public String mandate(Actor actor, NewMandate req) {
    lockAccounts(req.debtorId(), req.creditorId());
    if (!loadAccount(req.debtorId(), false).ownerId().equals(actor.subject())) throw new BankError("FORBIDDEN", 403);
    if (req.validUntil().isBefore(LocalDate.now(clock))) throw BankError.invalid("MANDATE_EXPIRED");
    boolean metadata = req.mandateReference() != null || req.creditorIdentifier() != null
        || req.signatureDate() != null || req.sequenceType() != null;
    if (metadata) {
      Rules.sepaReference(req.mandateReference());
      if (req.creditorIdentifier() == null || !req.creditorIdentifier().matches("[A-Z]{2}[0-9]{2}[A-Za-z0-9]{4,31}")
          || req.signatureDate() == null || req.signatureDate().isAfter(LocalDate.now(clock))
          || req.signatureDate().isAfter(req.validUntil())
          || !Set.of("FRST", "RCUR", "OOFF", "FNAL").contains(req.sequenceType() == null ? "" : req.sequenceType()))
        throw BankError.invalid("INVALID_MANDATE_METADATA");
    }
    String id = id();
    db.update(
        "insert into mandate(id,debtor_id,creditor_id,active,valid_until,max_amount,mandate_reference,creditor_identifier,signature_date,sequence_type) values(?,?,?,?,?,?,?,?,?,?)",
        id,
        req.debtorId(),
        req.creditorId(),
        true,
        java.sql.Date.valueOf(req.validUntil()),
        Rules.money(req.maxAmount()), req.mandateReference(), req.creditorIdentifier(),
        req.signatureDate() == null ? null : java.sql.Date.valueOf(req.signatureDate()), req.sequenceType());
    return id;
  }

  @Transactional
  public void revokeMandate(Actor actor, String id) {
    var rows = db.queryForList("select * from mandate where id=?", id);
    if (rows.isEmpty()) throw new BankError("MANDATE_NOT_FOUND", 404);
    String debtor = rows.get(0).get("debtor_id").toString();
    loadAccount(debtor, true);
    if (!loadAccount(debtor, false).ownerId().equals(actor.subject())) throw new BankError("FORBIDDEN", 403);
    db.update("update mandate set active=false where id=?", id);
  }

  @Transactional
  public String consent(Actor actor, NewConsent req) {
    Account a = loadAccount(req.accountId(), true);
    own(actor, a);
    if (!req.expiresAt().isAfter(clock.instant())
        || req.expiresAt().isAfter(clock.instant().plus(Duration.ofDays(90))))
      throw BankError.invalid("INVALID_CONSENT_EXPIRY");
    String id = id();
    db.update(
        "insert into consent values(?,?,?,?,?)", id, a.id(), req.tpp(), req.expiresAt().atOffset(ZoneOffset.UTC), true);
    return id;
  }

  @Transactional
  public void revokeConsent(Actor actor, String id) {
    var rows = db.queryForList("select * from consent where id=?", id);
    if (rows.isEmpty()) throw new BankError("CONSENT_NOT_FOUND", 404);
    own(actor, loadAccount(rows.get(0).get("account_id").toString(), true));
    db.update("update consent set active=false where id=?", id);
  }

  @Transactional(readOnly = true)
  public Account openBanking(String tpp, String consent) {
    var rows =
        db.queryForList(
            "select account_id from consent where id=? and tpp=? and active=true and expires_at>?",
            consent,
            tpp,
            clock.instant().atOffset(ZoneOffset.UTC));
    if (rows.isEmpty()) throw new BankError("INVALID_CONSENT", 403);
    return loadAccount(rows.get(0).get("account_id").toString(), false);
  }

  @Transactional(readOnly = true)
  public List<Map<String, Object>> reconcile(Actor actor, List<ReconcileLine> lines) {
    operator(actor);
    if (lines.size() > 1000) throw BankError.invalid("BATCH_TOO_LARGE");
    List<Map<String, Object>> result = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (var l : lines) {
      String outcome;
      if (!seen.add(l.paymentId())) outcome = "DUPLICATE";
      else {
        var found = db.query("select * from payment where id=?", PAYMENT, l.paymentId());
        outcome =
            found.isEmpty()
                ? "MISSING"
                : found.get(0).amount().compareTo(l.amount()) != 0
                    ? "AMOUNT_MISMATCH"
                    : !found.get(0).status().equals(l.status()) ? "STATUS_MISMATCH" : "MATCHED";
      }
      result.add(Map.of("paymentId", l.paymentId(), "outcome", outcome));
    }
    return result;
  }

  @Transactional
  public int expire() {
    var ids =
        db.queryForList(
            "select id from payment where deadline<=? and status in"
                + " ('VALIDATED','ROUTED','CLEARED')",
            String.class,
            clock.instant().atOffset(ZoneOffset.UTC));
    for (String id : ids) transition(new Actor("scheduler", true), id, "TIMEOUT");
    return ids.size();
  }
}
