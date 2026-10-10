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
}
