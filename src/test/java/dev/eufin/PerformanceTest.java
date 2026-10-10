package dev.eufin;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@Tag("performance")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "bank.requests-per-minute=10000")
@ActiveProfiles("test")
class PerformanceTest {
  @LocalServerPort int port;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate db;

  @Test
  void concurrentHttpProfileReadsAndDuplicatePaymentsRespectLatencyAndIntegrity() throws Exception {
    var http = new HttpE2ETest();
    http.port = port;
    http.json = json;
    http.db = db;
    http.clean();
    String user = http.token("alice", "bank.pay"),
        other = http.token("bob", "bank.pay"),
        ops = http.token("ops", "bank.pay bank.ops");
    http.enroll(user, "alice");
    http.enroll(other, "bob");
    String
        a =
            http.ok(
                    http.request(
                        "POST",
                        "/api/v1/accounts",
                        user,
                        Map.of("iban", "DE89370400440532013000", "name", "Alice"),
                        null))
                .get("id")
                .asText(),
        b =
            http.ok(
                    http.request(
                        "POST",
                        "/api/v1/accounts",
                        other,
                        Map.of("iban", "NL91ABNA0417164300", "name", "Bob"),
                        null))
                .get("id")
                .asText();
    http.ok(
        http.request(
            "POST",
            "/api/v1/ops/accounts/" + a + "/fund",
            ops,
            Map.of("amount", 100),
            "perf-fund"));
    Map<String, Object> pay =
        Map.of(
            "debtorId",
            a,
            "creditorId",
            b,
            "amount",
            10,
            "scheme",
            "SCT",
            "endToEndId",
            "PERF",
            "creditorName",
            "Bob");
    for (int i = 0; i < 20; i++)
      http.ok(http.request("GET", "/api/v1/customers/me", user, null, null));
    int samples = 200, concurrency = 8;
    var pool = Executors.newFixedThreadPool(concurrency);
    List<Long> times = new ArrayList<>();
    long start = System.nanoTime();
    try {
      List<Future<Long>> work = new ArrayList<>();
      for (int i = 0; i < samples; i++) {
        final boolean write = i % 5 == 0;
        work.add(
            pool.submit(
                () -> {
                  long t = System.nanoTime();
                  http.ok(
                      http.request(
                          write ? "POST" : "GET",
                          write ? "/api/v1/payments" : "/api/v1/customers/me",
                          user,
                          write ? pay : null,
                          write ? "perf-duplicate" : null));
                  return (System.nanoTime() - t) / 1_000_000;
                }));
      }
      for (var f : work) times.add(f.get(30, TimeUnit.SECONDS));
    } finally {
      pool.shutdownNow();
    }
    Collections.sort(times);
    double seconds = (System.nanoTime() - start) / 1e9;
    long p95 = times.get((int) Math.ceil(samples * .95) - 1);
    assertThat(p95).isLessThan(2000);
    assertThat(db.queryForObject("select count(*) from payment", Long.class)).isEqualTo(1);
    assertThat(
            db.queryForObject("select held from account where id=?", java.math.BigDecimal.class, a))
        .isEqualByComparingTo("10");
    var report =
        Map.of(
            "samples",
            samples,
            "concurrency",
            concurrency,
            "mix",
            "80% encrypted profile GET, 20% idempotent payment POST",
            "p50Ms",
            times.get(samples / 2),
            "p95Ms",
            p95,
            "p99Ms",
            times.get((int) Math.ceil(samples * .99) - 1),
            "requestsPerSecond",
            samples / seconds,
            "durationSeconds",
            seconds,
            "database",
            "H2",
            "scope",
            "local regression, not a production capacity estimate");
    Files.createDirectories(Path.of("target"));
    Files.writeString(
        Path.of("target/performance-results.json"),
        json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
  }
  @Test
  void concurrentSddRefundsAndUnauthorisedClaimsRemainFastAndCreditOnce() throws Exception {
    var http = new HttpE2ETest(); http.port = port; http.json = json; http.db = db; http.clean();
    String payer = http.token("alice", "bank.pay"), merchant = http.token("bob", "bank.pay"),
        ops = http.token("ops", "bank.pay bank.ops");
    http.enroll(payer, "alice"); http.enroll(merchant, "bob");
    String a = http.ok(http.request("POST", "/api/v1/accounts", payer,
        Map.of("iban", "DE89370400440532013000", "name", "Alice"), null)).get("id").asText();
    String b = http.ok(http.request("POST", "/api/v1/accounts", merchant,
        Map.of("iban", "NL91ABNA0417164300", "name", "Bob"), null)).get("id").asText();
    String c = http.ok(http.request("POST", "/api/v1/accounts", merchant,
        Map.of("iban", "BE68539007547034", "name", "Bob"), null)).get("id").asText();
    http.ok(http.request("POST", "/api/v1/ops/accounts/" + a + "/fund", ops, Map.of("amount", 120), "sdd-load-fund"));
    String mandate = http.ok(http.request("POST", "/api/v1/mandates", payer,
        Map.of("debtorId", a, "creditorId", b, "maxAmount", 10, "validUntil", "2030-01-01"), null)).get("id").asText();
    List<String> payments = new ArrayList<>();
    for (int i = 0; i < 12; i++) {
      String id = http.ok(http.request("POST", "/api/v1/payments", merchant,
          Map.of("debtorId", a, "creditorId", b, "amount", 10, "scheme", "SDD_CORE", "endToEndId", "SDD-LOAD-" + i,
              "creditorName", "Bob", "mandateId", mandate), "sdd-load-" + i)).get("id").asText();
      payments.add(id);
      for (String action : List.of("SCREEN", "CLEAR", "SETTLE"))
        http.ok(http.request("POST", "/api/v1/ops/payments/" + id + "/transitions", ops, Map.of("action", action), null));
    }
    String spent = http.ok(http.request("POST", "/api/v1/payments", merchant,
        Map.of("debtorId", b, "creditorId", c, "amount", 120, "scheme", "SCT", "endToEndId", "MERCHANT-SPENT",
            "creditorName", "Bob"), "spend-all")).get("id").asText();
    for (String action : List.of("SCREEN", "CLEAR", "SETTLE"))
      http.ok(http.request("POST", "/api/v1/ops/payments/" + spent + "/transitions", ops, Map.of("action", action), null));
    int samples = 120, concurrency = 8;
    List<Long> times = new ArrayList<>();
    long start = System.nanoTime();
    try (var pool = Executors.newFixedThreadPool(concurrency)) {
      List<Future<Long>> work = new ArrayList<>();
      for (int i = 0; i < samples; i++) {
        final int index = i % payments.size();
        work.add(pool.submit(() -> {
          long began = System.nanoTime();
          boolean allegation = index % 2 == 1;
          http.ok(http.request("POST", "/api/v1/payments/" + payments.get(index)
              + (allegation ? "/sdd-dispute" : "/refund"), payer,
              allegation ? Map.of("allegation", "NO_MANDATE") : null, null));
          return (System.nanoTime() - began) / 1_000_000;
        }));
      }
      for (var call : work) times.add(call.get(30, TimeUnit.SECONDS));
    }
    double seconds = (System.nanoTime() - start) / 1e9;
    Collections.sort(times);
    long p95 = times.get((int) Math.ceil(samples * .95) - 1);
    assertThat(p95).isLessThan(2000);
    assertThat(db.queryForObject("select count(*) from sdd_refund", Long.class)).isEqualTo(12);
    assertThat(db.queryForObject("select count(*) from sdd_dispute", Long.class)).isEqualTo(6);
    assertThat(db.queryForObject("select count(*) from ledger where booking_id like 'SDD_REFUND:%'", Long.class)).isEqualTo(24);
    assertThat(db.queryForObject("select sum(delta) from ledger", java.math.BigDecimal.class)).isZero();
    assertThat(db.queryForObject("select sum(amount-recovered_amount) from sdd_refund", java.math.BigDecimal.class))
        .isEqualByComparingTo("120");
    assertThat(db.queryForObject("select balance from account where id=?", java.math.BigDecimal.class, a)).isEqualByComparingTo("120");
    assertThat(db.queryForObject("select balance from account where id=?", java.math.BigDecimal.class, b)).isZero();
    assertThat(db.queryForObject("select balance from account where id=?", java.math.BigDecimal.class, c)).isEqualByComparingTo("120");
    var report = Map.of("samples", samples, "concurrency", concurrency,
        "mix", "50% eight-week SDD refunds, 50% unauthorised SDD claims; 12 payments, 10 attempts each, empty creditor account",
        "p50Ms", times.get(samples / 2), "p95Ms", p95,
        "p99Ms", times.get((int) Math.ceil(samples * .99) - 1), "requestsPerSecond", samples / seconds,
        "durationSeconds", seconds, "database", "H2", "scope", "financial integrity and latency regression, not production capacity");
    Files.createDirectories(Path.of("target"));
    Files.writeString(Path.of("target/sdd-performance-results.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
    System.out.println("SDD performance regression: " + json.writeValueAsString(report));
  }

}
