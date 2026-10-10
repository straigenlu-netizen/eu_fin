package dev.eufin;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "bank.requests-per-minute=2")
@ActiveProfiles("test")
class RateLimitE2ETest {
  @LocalServerPort int port;

  @Test
  void requestsAreLimitedByVerifiedSubject() throws Exception {
    var http = new HttpE2ETest();
    http.port = port;
    http.json = new ObjectMapper();
    String token = http.token("rate-limited-user", "bank.pay");
    assertThat(http.request("GET", "/api/v1/accounts", token, null, null).statusCode())
        .isEqualTo(200);
    assertThat(http.request("GET", "/api/v1/accounts", token, null, null).statusCode())
        .isEqualTo(200);
    var third = http.request("GET", "/api/v1/accounts", token, null, null);
    assertThat(third.statusCode()).isEqualTo(429);
    assertThat(third.headers().firstValue("Retry-After").orElseThrow()).isEqualTo("60");
    assertThat(
            http.request(
                    "GET", "/api/v1/accounts", http.token("other-subject", "bank.pay"), null, null)
                .statusCode())
        .isEqualTo(200);
  }
}
