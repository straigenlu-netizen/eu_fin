package dev.eufin;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class CustomerSecurityE2ETest {
  @LocalServerPort int port;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate db;
  HttpE2ETest http;
  String user, staff;

  @BeforeEach
  void setup() throws Exception {
    http = new HttpE2ETest();
    http.port = port;
    http.json = json;
    http.db = db;
    http.clean();
    user = http.token("alice", "bank.pay");
    staff = http.token("agent", "bank.agent");
    http.enroll(user, "alice");
  }

  Map<String, Object> contact(String email) {
    return Map.of(
        "email",
        email,
        "phone",
        "+48123456789",
        "address",
        "New Demo Street 2",
        "city",
        "Warsaw",
        "country",
        "PL");
  }

  @Test
  void customerCanEditContactsButCannotMassAssignIdentityOrPrivileges() throws Exception {
    var old = http.ok(http.request("GET", "/api/v1/customers/me", user, null, null));
    long version = old.get("version").asLong();
    var changed =
        http.ok(
            http.request(
                "PUT",
                "/api/v1/customers/me/contact",
                user,
                Map.of("contact", contact("new@example.test"), "expectedVersion", version),
                null));
    assertThat(changed.get("contact").get("email").asText()).isEqualTo("new@example.test");
    assertThat(changed.get("identity")).isEqualTo(old.get("identity"));
    assertThat(
            http.request(
                    "PUT",
                    "/api/v1/customers/me/contact",
                    user,
                    Map.of(
                        "contact",
                        contact("attack@example.test"),
                        "expectedVersion",
                        version + 1,
                        "kycStatus",
                        "VERIFIED"),
                    null)
                .statusCode())
        .isEqualTo(400);
    assertThat(http.request("GET", "/api/v1/staff/customers", user, null, null).statusCode())
        .isEqualTo(403);
    assertThat(
            http.request(
                    "PATCH",
                    "/api/v1/staff/customers/alice",
                    staff,
                    Map.of(
                        "riskTier",
                        "LOW",
                        "suspended",
                        false,
                        "expectedVersion",
                        version + 1,
                        "legalName",
                        "Injected"),
                    null)
                .statusCode())
        .isEqualTo(400);
  }

  @Test
  void staffSeesMaskedContactsAndCanOnlyUpdateMetadata() throws Exception {
    var list = http.ok(http.request("GET", "/api/v1/staff/customers", staff, null, null));
    assertThat(list.get(0).get("emailMasked").asText()).isEqualTo("a***@example.test");
    assertThat(list.get(0).has("dateOfBirth")).isFalse();
    assertThat(list.get(0).has("address")).isFalse();
    long version = list.get(0).get("version").asLong();
    http.ok(
        http.request(
            "PATCH",
            "/api/v1/staff/customers/alice",
            staff,
            Map.of("riskTier", "HIGH", "suspended", true, "expectedVersion", version),
            null));
    assertThat(
            http.request(
                    "POST",
                    "/api/v1/accounts",
                    user,
                    Map.of("iban", "DE89370400440532013000", "name", "alice"),
                    null)
                .statusCode())
        .isEqualTo(403);
    assertThat(http.request("GET", "/api/v1/ops/accounts/x/fund", staff, null, null).statusCode())
        .isEqualTo(403);
  }

  @Test
  void personalDataIsEncryptedAndAccessIsAudited() throws Exception {
    String cipher =
        db.queryForObject(
            "select encrypted_profile from customer where subject='alice'", String.class);
    assertThat(cipher)
        .startsWith("v1:")
        .doesNotContain("alice@example.test", "1990-01-01", "Demo Street");
    http.ok(http.request("GET", "/api/v1/staff/customers", staff, null, null));
    assertThat(
            db.queryForObject(
                "select count(*) from audit_log where action='CUSTOMERS_VIEWED'", Long.class))
        .isEqualTo(1);
  }

  @Test
  void identityChangeNeedsIndependentReviewAndCannotBeApprovedTwice() throws Exception {
    long version =
        http.ok(http.request("GET", "/api/v1/customers/me", user, null, null))
            .get("version")
            .asLong();
    String id =
        http.ok(
                http.request(
                    "POST",
                    "/api/v1/customers/me/identity-changes",
                    user,
                    Map.of(
                        "identity",
                        Map.of(
                            "legalName",
                            "Alice Corrected",
                            "dateOfBirth",
                            "1990-01-01",
                            "nationality",
                            "PL"),
                        "expectedVersion",
                        version),
                    null))
            .get("id")
            .asText();
    assertThat(
            http.request(
                    "POST",
                    "/api/v1/staff/identity-changes/" + id + "/review",
                    http.token("alice", "bank.agent"),
                    Map.of("decision", "APPROVE"),
                    null)
                .statusCode())
        .isEqualTo(403);
    var queue = http.ok(http.request("GET", "/api/v1/staff/identity-changes", staff, null, null));
    assertThat(queue.get(0).get("proposedIdentity").get("legalName").asText())
        .isEqualTo("Alice Corrected");
    assertThat(queue.get(0).has("encrypted_identity")).isFalse();
    assertThat(http.request("GET", "/api/v1/staff/identity-changes", user, null, null).statusCode())
        .isEqualTo(403);
    http.ok(
        http.request(
            "POST",
            "/api/v1/staff/identity-changes/" + id + "/review",
            staff,
            Map.of("decision", "APPROVE"),
            null));
    assertThat(
            http.ok(http.request("GET", "/api/v1/customers/me", user, null, null))
                .get("identity")
                .get("legalName")
                .asText())
        .isEqualTo("Alice Corrected");
    assertThat(
            http.request(
                    "POST",
                    "/api/v1/staff/identity-changes/" + id + "/review",
                    staff,
                    Map.of("decision", "APPROVE"),
                    null)
                .statusCode())
        .isEqualTo(409);
  }

  @Test
  void staleUpdatesAndUnknownPropertiesAreRejected() throws Exception {
    long version =
        http.ok(http.request("GET", "/api/v1/customers/me", user, null, null))
            .get("version")
            .asLong();
    http.ok(
        http.request(
            "PUT",
            "/api/v1/customers/me/contact",
            user,
            Map.of("contact", contact("one@example.test"), "expectedVersion", version),
            null));
    assertThat(
            http.request(
                    "PUT",
                    "/api/v1/customers/me/contact",
                    user,
                    Map.of("contact", contact("two@example.test"), "expectedVersion", version),
                    null)
                .statusCode())
        .isEqualTo(409);
    assertThat(
            http.request(
                    "POST",
                    "/api/v1/customers/me",
                    user,
                    Map.of(
                        "identity",
                        Map.of(
                            "legalName", "Fake", "dateOfBirth", "1990-01-01", "nationality", "PL"),
                        "contact",
                        contact("x@example.test"),
                        "subject",
                        "bob"),
                    null)
                .statusCode())
        .isEqualTo(400);
  }

  @Test
  void unsignedOrTamperedTokensAndStaffWithoutMfaAreRejected() throws Exception {
    String token = http.token("alice", "bank.pay");
    String[] parts = token.split("\\.");
    String tampered =
        parts[0]
            + "."
            + parts[1]
            + "."
            + (parts[2].charAt(0) == 'A' ? "B" : "A")
            + parts[2].substring(1);
    assertThat(http.request("GET", "/api/v1/customers/me", tampered, null, null).statusCode())
        .isEqualTo(401);
    var encoder =
        new org.springframework.security.oauth2.jwt.NimbusJwtEncoder(
            new com.nimbusds.jose.jwk.source.ImmutableSecret<>(
                new javax.crypto.spec.SecretKeySpec(
                    "test-only-32-byte-secret-never-production".getBytes(), "HmacSHA256")));
    var claims =
        org.springframework.security.oauth2.jwt.JwtClaimsSet.builder()
            .issuer("eu-fin-lab")
            .subject("agent")
            .audience(List.of("eu-fin"))
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(300))
            .claim("scope", "bank.agent")
            .claim("amr", List.of("pwd"))
            .build();
    String noMfa =
        encoder
            .encode(
                org.springframework.security.oauth2.jwt.JwtEncoderParameters.from(
                    org.springframework.security.oauth2.jwt.JwsHeader.with(
                            org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256)
                        .build(),
                    claims))
            .getTokenValue();
    var r = http.request("GET", "/api/v1/staff/customers", noMfa, null, null);
    assertThat(r.statusCode()).isEqualTo(403);
    assertThat(r.body()).contains("MFA_REQUIRED");
  }

  @Test
  void securityHeadersAndPublicUiArePresent() throws Exception {
    var r = http.request("GET", "/bank/", null, null, null);
    assertThat(r.statusCode()).isEqualTo(200);
    assertThat(r.headers().firstValue("Content-Security-Policy").orElseThrow())
        .contains("script-src 'self'", "frame-ancestors 'none'");
    assertThat(r.headers().firstValue("X-Content-Type-Options").orElseThrow()).isEqualTo("nosniff");
    assertThat(r.headers().firstValue("X-Frame-Options").orElseThrow()).isEqualTo("DENY");
  }
}
