package dev.eufin;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.net.URI;
import java.net.http.*;
import java.time.Instant;
import java.util.*;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class HttpE2ETest {
  @LocalServerPort int port;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate db;
  @Autowired org.springframework.web.context.WebApplicationContext webContext;
  final HttpClient client = HttpClient.newHttpClient();

  String token(String subject, String scopes, Instant expires, String audience) {
    return token(subject, scopes, expires, audience, true);
  }

  String token(String subject, String scopes, Instant expires, String audience, boolean mfa) {
    var encoder =
        new NimbusJwtEncoder(
            new ImmutableSecret<>(
                new SecretKeySpec(
                    "test-only-32-byte-secret-never-production"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    "HmacSHA256")));
    var claims =
        JwtClaimsSet.builder()
            .issuer("eu-fin-lab")
            .subject(subject)
            .audience(List.of(audience))
            .issuedAt(expires.minusSeconds(600))
            .expiresAt(expires)
            .claim("scope", scopes)
            .claim("amr", mfa ? java.util.List.of("mfa") : java.util.List.of("pwd"))
            .build();
    return encoder
        .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
        .getTokenValue();
  }

  String token(String sub, String scope) {
    return token(sub, scope, Instant.now().plusSeconds(300), "eu-fin");
  }

  HttpResponse<String> request(String method, String path, String token, Object body, String key)
      throws Exception {
    var b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    if (token != null) b.header("Authorization", "Bearer " + token);
    if (key != null) b.header("Idempotency-Key", key);
    b.header("Content-Type", "application/json");
    b.method(
        method,
        body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
    return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
  }

  JsonNode ok(HttpResponse<String> r) throws Exception {
    assertThat(r.statusCode()).withFailMessage(r.body()).isEqualTo(200);
    return json.readTree(r.body());
  }

  @BeforeEach
  void clean() {
    for (String t :
        List.of(
            "audit_log",
            "identity_change",
            "customer",
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
  }

  @Test
  void realHttpTransferThroughJwtDbAndIso() throws Exception {
    String user = token("alice", "bank.pay"),
        other = token("bob", "bank.pay"),
        ops = token("ops", "bank.pay bank.ops");
    enroll(user, "alice");
    enroll(other, "bob");
    String a =
        ok(request(
                "POST",
                "/api/v1/accounts",
                user,
                Map.of("iban", "DE89370400440532013000", "name", "Alice"),
                null))
            .get("id")
            .asText();
    String b =
        ok(request(
                "POST",
                "/api/v1/accounts",
                other,
                Map.of("iban", "NL91ABNA0417164300", "name", "Bob"),
                null))
            .get("id")
            .asText();
    ok(request("POST", "/api/v1/ops/accounts/" + a + "/fund", ops, Map.of("amount", 100), "fund"));
    Map<String, Object> body =
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
            "HTTP-1",
            "creditorName",
            "Bob");
    String p = ok(request("POST", "/api/v1/payments", user, body, "http")).get("id").asText();
    assertThat(ok(request("POST", "/api/v1/payments", user, body, "http")).get("id").asText())
        .isEqualTo(p);
    assertThat(
            request(
                    "POST",
                    "/api/v1/ops/payments/" + p + "/transitions",
                    user,
                    Map.of("action", "SETTLE"),
                    null)
                .statusCode())
        .isEqualTo(403);
    for (String action : List.of("SCREEN", "CLEAR", "SETTLE"))
      ok(
          request(
              "POST",
              "/api/v1/ops/payments/" + p + "/transitions",
              ops,
              Map.of("action", action),
              null));
    assertThat(
            ok(request("GET", "/api/v1/accounts/" + a, user, null, null))
                .get("balance")
                .decimalValue())
        .isEqualByComparingTo("90");
    assertThat(
            ok(request("GET", "/api/v1/accounts/" + b, other, null, null))
                .get("balance")
                .decimalValue())
        .isEqualByComparingTo("10");
    assertThat(request("GET", "/api/v1/payments/" + p + "/iso/pacs008", user, null, null).body())
        .contains("pacs.008.001.08");
    assertThat(request("GET", "/api/v1/accounts/" + a, other, null, null).statusCode())
        .isEqualTo(403);
  }

  @Test
  void csrfRejectsAmbientCredentialsAndPreservesExplicitBearerRequests() throws Exception {
    String jwt = token("csrf-customer", "bank.pay");
    for (String method : List.of("POST", "PUT", "PATCH", "DELETE")) {
      assertThat(request(method, "/api/v1/accounts", null, Map.of(), null).statusCode())
          .as("Anonymous %s must require CSRF protection", method)
          .isEqualTo(401);
      var cookieRequest =
          HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/accounts"))
              .header("Cookie", "access_token=" + jwt + "; JSESSIONID=attacker-session")
              .header("Origin", "https://attacker.example")
              .header("Content-Type", "application/json")
              .method(method, HttpRequest.BodyPublishers.ofString("{}"))
              .build();
      assertThat(client.send(cookieRequest, HttpResponse.BodyHandlers.ofString()).statusCode())
          .as("Cookie-only %s must not bypass CSRF protection", method)
          .isEqualTo(401);
    }
    assertThat(request("POST", "/api/v1/accounts?access_token=" + jwt, null, Map.of(), null)
            .statusCode())
        .isEqualTo(401);
    assertThat(request("POST", "/api/v1/accounts", "malformed", Map.of(), null).statusCode())
        .isEqualTo(401);
    // Exercise the real decoder and HTTP filter chain, without mock authentication or CSRF tokens.
    enroll(jwt, "csrf-customer");
    assertThat(request("GET", "/api/v1/customers/me", jwt, null, null).statusCode())
        .isEqualTo(200);
    assertThat(db.queryForObject("select count(*) from account", Integer.class)).isZero();
  }

  @Test
  void authenticatedRequestsWithoutBearerHeadersRequireCsrfTokens() throws Exception {
    var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
        .webAppContextSetup(webContext)
        .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
            .springSecurity())
        .build();
    var authentication = org.springframework.security.test.web.servlet.request
        .SecurityMockMvcRequestPostProcessors.jwt();
    var post = org.springframework.test.web.servlet.request.MockMvcRequestBuilders
        .post("/api/public/auth-config");
    mvc.perform(post.with(authentication))
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
            .isForbidden());
    mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
            .post("/api/public/auth-config").with(authentication)
            .with(org.springframework.security.test.web.servlet.request
                .SecurityMockMvcRequestPostProcessors.csrf().useInvalidToken()))
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
            .isForbidden());
    // A valid CSRF token passes the filter and reaches MVC (this endpoint supports GET only).
    mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
            .post("/api/public/auth-config").with(authentication)
            .with(org.springframework.security.test.web.servlet.request
                .SecurityMockMvcRequestPostProcessors.csrf()))
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
            .isMethodNotAllowed());
  }

  @Test
  void tokenAndInputBoundaries() throws Exception {
    assertThat(request("GET", "/api/v1/accounts", null, null, null).statusCode()).isEqualTo(401);
    assertThat(
            request(
                    "GET",
                    "/api/v1/accounts",
                    token("x", "bank.pay", Instant.now().minusSeconds(120), "eu-fin"),
                    null,
                    null)
                .statusCode())
        .isEqualTo(401);
    assertThat(
            request(
                    "GET",
                    "/api/v1/accounts",
                    token("x", "bank.pay", Instant.now().plusSeconds(300), "wrong"),
                    null,
                    null)
                .statusCode())
        .isEqualTo(401);
    assertThat(request("GET", "/api/v1/accounts", token("x", "bank.read"), null, null).statusCode())
        .isEqualTo(403);
    enroll(token("x", "bank.pay"), "x");
    assertThat(
            request(
                    "POST",
                    "/api/v1/accounts",
                    token("x", "bank.pay"),
                    Map.of("iban", "bad", "name", "X"),
                    null)
                .statusCode())
        .isEqualTo(422);
    assertThat(
            request("POST", "/api/v1/payments", token("x", "bank.pay"), Map.of(), "id")
                .statusCode())
        .isEqualTo(400);
    assertThat(request("GET", "/actuator/health", null, null, null).statusCode()).isEqualTo(200);
  }

  @Test
  void sddDisputeHttpWorkflowEnforcesPayerScopesMfaAndEvidence() throws Exception {
    String payer = token("alice", "bank.pay"), merchant = token("bob", "bank.pay"),
        ops = token("ops", "bank.ops"), funder = token("ops", "bank.pay bank.ops");
    enroll(payer, "alice"); enroll(merchant, "bob");
    String a = ok(request("POST", "/api/v1/accounts", payer,
        Map.of("iban", "DE89370400440532013000", "name", "Alice"), null)).get("id").asText();
    String b = ok(request("POST", "/api/v1/accounts", merchant,
        Map.of("iban", "NL91ABNA0417164300", "name", "Bob"), null)).get("id").asText();
    ok(request("POST", "/api/v1/ops/accounts/" + a + "/fund", funder, Map.of("amount", 100), "sdd-fund"));
    String mandate = ok(request("POST", "/api/v1/mandates", payer,
        Map.of("debtorId", a, "creditorId", b, "maxAmount", 20, "validUntil", "2030-01-01"), null)).get("id").asText();
    String p = ok(request("POST", "/api/v1/payments", merchant,
        Map.of("debtorId", a, "creditorId", b, "amount", 10, "scheme", "SDD_CORE", "endToEndId", "HTTP-SDD",
            "creditorName", "Bob", "mandateId", mandate), "http-sdd")).get("id").asText();
    for (String action : List.of("SCREEN", "CLEAR", "SETTLE"))
      ok(request("POST", "/api/v1/ops/payments/" + p + "/transitions", ops, Map.of("action", action), null));
    String path = "/api/v1/payments/" + p + "/sdd-dispute";
    assertThat(request("POST", path, null, Map.of("allegation", "NO_MANDATE"), null).statusCode()).isEqualTo(401);
    assertThat(request("POST", path, merchant, Map.of("allegation", "NO_MANDATE"), null).statusCode()).isEqualTo(403);
    assertThat(request("POST", path, payer, Map.of("allegation", "INVALID"), null).statusCode()).isEqualTo(400);
    JsonNode claim = ok(request("POST", path, payer, Map.of("allegation", "NO_MANDATE"), null));
    assertThat(claim.get("status").asText()).isEqualTo("REFUNDED_UNDER_REVIEW");
    assertThat(ok(request("POST", path, payer, Map.of("allegation", "NO_MANDATE"), null)).get("id").asText())
        .isEqualTo(claim.get("id").asText());
    assertThat(request("GET", path, merchant, null, null).statusCode()).isEqualTo(403);
    assertThat(request("GET", "/api/v1/ops/sdd-disputes", payer, null, null).statusCode()).isEqualTo(403);
    String noMfa = token("ops", "bank.ops", Instant.now().plusSeconds(300), "eu-fin", false);
    assertThat(request("GET", "/api/v1/ops/sdd-disputes", noMfa, null, null).statusCode()).isEqualTo(403);
    assertThat(ok(request("GET", "/api/v1/ops/sdd-disputes", ops, null, null)).size()).isEqualTo(1);
    String review = "/api/v1/ops/sdd-disputes/" + claim.get("id").asText() + "/decision";
    assertThat(request("POST", review, token("alice", "bank.ops"),
        Map.of("decision", "CONFIRMED_UNAUTHORISED"), null).statusCode()).isEqualTo(403);
    assertThat(request("POST", review, ops, Map.of("decision", "AUTHORISATION_EVIDENCE_FOUND"), null).statusCode()).isEqualTo(422);
    assertThat(ok(request("POST", review, ops, Map.of("decision", "AUTHORISATION_EVIDENCE_FOUND", "evidenceHash", "a".repeat(64)), null))
        .get("status").asText()).isEqualTo("AUTHORISATION_EVIDENCE_FOUND");
    String recover = "/api/v1/ops/sdd-refunds/" + p + "/recoveries";
    assertThat(request("POST", recover, ops, Map.of("amount", 4), null).statusCode()).isEqualTo(400);
    JsonNode recovery = ok(request("POST", recover, ops, Map.of("amount", 4), "recover"));
    assertThat(ok(request("POST", recover, ops, Map.of("amount", 4), "recover")).get("id").asText())
        .isEqualTo(recovery.get("id").asText());
    assertThat(ok(request("GET", "/api/v1/ops/sdd-refunds", ops, null, null)).get(0).get("outstandingAmount").decimalValue())
        .isEqualByComparingTo("6");
    assertThat(ok(request("GET", "/api/v1/accounts/" + a, payer, null, null)).get("balance").decimalValue())
        .isEqualByComparingTo("100");
  }

  void enroll(String token, String subject) throws Exception {
    ok(
        request(
            "POST",
            "/api/v1/customers/me",
            token,
            java.util.Map.of(
                "identity",
                java.util.Map.of(
                    "legalName", subject, "dateOfBirth", "1990-01-01", "nationality", "PL"),
                "contact",
                java.util.Map.of(
                    "email",
                    subject + "@example.test",
                    "phone",
                    "+48123456789",
                    "address",
                    "Demo Street 1",
                    "city",
                    "Warsaw",
                    "country",
                    "PL")),
            null));
    ok(
        request(
            "POST",
            "/api/v1/staff/customers/" + subject + "/kyc",
            token("agent", "bank.agent"),
            java.util.Map.of("decision", "APPROVE"),
            null));
  }
}
