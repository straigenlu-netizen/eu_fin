package dev.eufin.customers;

import static dev.eufin.customers.CustomerModels.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.eufin.domain.BankError;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CustomerService {
  private final JdbcTemplate db;
  private final ProfileCipher cipher;
  private final ObjectMapper json;
  private final Clock clock;

  public CustomerService(JdbcTemplate db, ProfileCipher cipher, ObjectMapper json, Clock clock) {
    this.db = db;
    this.cipher = cipher;
    this.json = json;
    this.clock = clock;
  }

  private Map<String, Object> row(String subject, boolean lock) {
    return db
        .queryForList(
            "select * from customer where subject=?" + (lock ? " for update" : ""), subject)
        .stream()
        .findFirst()
        .orElseThrow(() -> new BankError("CUSTOMER_NOT_FOUND", 404));
  }

  private Register decode(Map<String, Object> r) {
    try {
      return json.readValue(
          cipher.decrypt(r.get("subject").toString(), r.get("encrypted_profile").toString()),
          Register.class);
    } catch (Exception e) {
      throw new IllegalStateException("Profile unavailable", e);
    }
  }

  private String encode(String subject, Object value) {
    try {
      return cipher.encrypt(subject, json.writeValueAsString(value));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private void audit(String actor, String subject, String action) {
    db.update(
        "insert into audit_log values(?,?,?,?,?)",
        UUID.randomUUID().toString(),
        actor,
        subject,
        action,
        clock.instant());
  }

  @Transactional
  public Object register(String subject, Register request) {
    if (request.identity().dateOfBirth().isAfter(LocalDate.now(clock).minusYears(18)))
      throw BankError.invalid("ADULTS_ONLY_LAB");
    db.update(
        "insert into customer(subject,encrypted_profile,created_at) values(?,?,?)",
        subject,
        encode(subject, request),
        clock.instant());
    audit(subject, subject, "CUSTOMER_REGISTERED");
    return self(subject);
  }

  @Transactional(readOnly = true)
  public Map<String, Object> self(String subject) {
    var r = row(subject, false);
    Register data = decode(r);
    return Map.of(
        "subject",
        subject,
        "identity",
        data.identity(),
        "contact",
        data.contact(),
        "kycStatus",
        r.get("kyc_status"),
        "suspended",
        r.get("suspended"),
        "version",
        r.get("version"));
  }

  private void version(Map<String, Object> r, long version) {
    if (((Number) r.get("version")).longValue() != version)
      throw new BankError("PROFILE_VERSION_CONFLICT", 409);
  }

  @Transactional
  public Object updateContact(String subject, UpdateContact request) {
    var r = row(subject, true);
    version(r, request.expectedVersion());
    if ((Boolean) r.get("suspended")) throw new BankError("CUSTOMER_SUSPENDED", 403);
    Register old = decode(r);
    db.update(
        "update customer set encrypted_profile=?,version=version+1 where subject=?",
        encode(subject, new Register(old.identity(), request.contact())),
        subject);
    audit(subject, subject, "CONTACT_UPDATED");
    return self(subject);
  }

  @Transactional
  public String requestIdentity(String subject, UpdateIdentity request) {
    var r = row(subject, true);
    version(r, request.expectedVersion());
    if ((Boolean) r.get("suspended")) throw new BankError("CUSTOMER_SUSPENDED", 403);
    if (request.identity().dateOfBirth().isAfter(LocalDate.now(clock).minusYears(18)))
      throw BankError.invalid("ADULTS_ONLY_LAB");
    if (db.queryForObject(
            "select count(*) from identity_change where subject=? and status='PENDING'",
            Long.class,
            subject)
        > 0) throw new BankError("PENDING_IDENTITY_CHANGE", 409);
    String id = UUID.randomUUID().toString();
    db.update(
        "insert into identity_change(id,subject,encrypted_identity,created_at) values(?,?,?,?)",
        id,
        subject,
        encode(subject, request.identity()),
        clock.instant());
    audit(subject, subject, "IDENTITY_CHANGE_REQUESTED");
    return id;
  }

  @Transactional
  public List<Map<String, Object>> staffList(String staff, int page) {
    if (page < 0 || page > 10000) throw BankError.invalid("INVALID_PAGE");
    audit(staff, "LIST", "CUSTOMERS_VIEWED");
    var result = new ArrayList<Map<String, Object>>();
    for (var r :
        db.queryForList(
            "select * from customer order by subject offset ? rows fetch next 25 rows only",
            page * 25)) {
      var data = decode(r);
      result.add(
          Map.of(
              "subject",
              r.get("subject"),
              "legalName",
              data.identity().legalName(),
              "emailMasked",
              maskEmail(data.contact().email()),
              "phoneMasked",
              "***" + data.contact().phone().substring(data.contact().phone().length() - 4),
              "country",
              data.contact().country(),
              "kycStatus",
              r.get("kyc_status"),
              "riskTier",
              r.get("risk_tier"),
              "suspended",
              r.get("suspended"),
              "version",
              r.get("version")));
    }
    return result;
  }

  private String maskEmail(String email) {
    int i = email.indexOf('@');
    return email.substring(0, 1) + "***" + email.substring(i);
  }

  @Transactional
  public Object staffUpdate(String staff, String subject, StaffUpdate request) {
    if (staff.equals(subject)) throw new BankError("SELF_REVIEW_FORBIDDEN", 403);
    var r = row(subject, true);
    version(r, request.expectedVersion());
    db.update(
        "update customer set risk_tier=?,suspended=?,version=version+1 where subject=?",
        request.riskTier(),
        request.suspended(),
        subject);
    audit(staff, subject, "STAFF_METADATA_UPDATED");
    return Map.of(
        "subject",
        subject,
        "riskTier",
        request.riskTier(),
        "suspended",
        request.suspended(),
        "version",
        request.expectedVersion() + 1);
  }

  @Transactional
  public void verify(String staff, String subject, String decision) {
    if (staff.equals(subject)) throw new BankError("SELF_REVIEW_FORBIDDEN", 403);
    row(subject, true);
    db.update(
        "update customer set kyc_status=?,version=version+1 where subject=?",
        decision.equals("APPROVE") ? "VERIFIED" : "REJECTED",
        subject);
    audit(staff, subject, "KYC_" + decision);
  }

  @Transactional
  public List<Map<String, Object>> changes(String staff) {
    audit(staff, "LIST", "IDENTITY_QUEUE_VIEWED");
    var result =
        dev.eufin.util.SqlRows.normalized(
            db.queryForList(
                "select id,subject,status,created_at,encrypted_identity from identity_change where"
                    + " status='PENDING' order by created_at fetch first 100 rows only"));
    for (var change : result) {
      try {
        String subject = change.get("subject").toString();
        change.put(
            "proposedIdentity",
            json.readValue(
                cipher.decrypt(subject, change.remove("encrypted_identity").toString()),
                Identity.class));
        change.put("currentIdentity", decode(row(subject, false)).identity());
      } catch (Exception e) {
        throw new IllegalStateException("Identity review data unavailable", e);
      }
    }
    return result;
  }

  @Transactional
  public void reviewIdentity(String staff, String id, String decision) {
    var changes = db.queryForList("select * from identity_change where id=?", id);
    if (changes.isEmpty()) throw new BankError("CHANGE_NOT_FOUND", 404);
    String subject = changes.get(0).get("subject").toString();
    if (staff.equals(subject)) throw new BankError("SELF_REVIEW_FORBIDDEN", 403);
    var r = row(subject, true);
    var change = db.queryForList("select * from identity_change where id=? for update", id).get(0);
    if (!change.get("status").equals("PENDING"))
      throw new BankError("CHANGE_ALREADY_REVIEWED", 409);
    if (decision.equals("APPROVE")) {
      try {
        Identity identity =
            json.readValue(
                cipher.decrypt(subject, change.get("encrypted_identity").toString()),
                Identity.class);
        Register old = decode(r);
        db.update(
            "update customer set encrypted_profile=?,kyc_status='VERIFIED',version=version+1 where"
                + " subject=?",
            encode(subject, new Register(identity, old.contact())),
            subject);
        db.update(
            "update account set name=?,version=version+1 where owner_id=?",
            identity.legalName(),
            subject);
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    }
    db.update(
        "update identity_change set status=?,reviewed_by=? where id=?",
        decision.equals("APPROVE") ? "APPROVED" : "REJECTED",
        staff,
        id);
    audit(staff, subject, "IDENTITY_" + decision);
  }

  @Transactional(readOnly = true)
  public void requireActive(String subject, boolean verified) {
    var r = row(subject, true);
    if ((Boolean) r.get("suspended")) throw new BankError("CUSTOMER_SUSPENDED", 403);
    if (verified && !r.get("kyc_status").equals("VERIFIED"))
      throw new BankError("KYC_REQUIRED", 403);
  }

  @Transactional(readOnly = true)
  public List<Map<String, Object>> audits(String staff) {
    return dev.eufin.util.SqlRows.normalized(
        db.queryForList(
            "select actor,subject,action,occurred_at from audit_log order by occurred_at desc fetch"
                + " first 100 rows only"));
  }

  @Transactional(readOnly = true)
  public String legalName(String subject) {
    return decode(row(subject, false)).identity().legalName();
  }
}
