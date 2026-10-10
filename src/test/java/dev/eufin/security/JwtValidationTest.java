package dev.eufin.security;

import static org.assertj.core.api.Assertions.*;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

class JwtValidationTest {
  private static final String SECRET = "test-only-32-byte-secret-never-production";
  private final JwtDecoder decoder =
      new SecurityConfig().decoder(SECRET, "eu-fin-lab", "", "eu-fin");

  private JWTClaimsSet.Builder claims() {
    return new JWTClaimsSet.Builder()
        .issuer("eu-fin-lab")
        .subject("alice")
        .audience("eu-fin")
        .issueTime(Date.from(Instant.now()))
        .claim("scope", "bank.pay");
  }

  private String sign(JWTClaimsSet.Builder claims) throws JOSEException {
    var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims.build());
    jwt.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
    return jwt.serialize();
  }

  @Test
  void correctlySignedTokenWithoutExpiryIsRejected() throws Exception {
    String token = sign(claims());
    assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
  }

  @Test
  void validExpiringTokenIsAccepted() throws Exception {
    String token = sign(claims().expirationTime(Date.from(Instant.now().plusSeconds(300))));
    assertThat(decoder.decode(token).getSubject()).isEqualTo("alice");
  }

  @Test
  void expiredAndNotYetValidTokensAreRejected() throws Exception {
    String expired = sign(claims().expirationTime(Date.from(Instant.now().minusSeconds(120))));
    String future = sign(claims()
        .expirationTime(Date.from(Instant.now().plusSeconds(600)))
        .notBeforeTime(Date.from(Instant.now().plusSeconds(300))));
    assertThatThrownBy(() -> decoder.decode(expired)).isInstanceOf(JwtException.class);
    assertThatThrownBy(() -> decoder.decode(future)).isInstanceOf(JwtException.class);
  }

  @Test
  void signatureDoesNotReplaceIssuerAudienceAndSubjectChecks() throws Exception {
    for (var invalid : new JWTClaimsSet.Builder[] {
        claims().issuer("untrusted"), claims().audience("another-bank"),
        claims().subject(null), claims().subject(" ")}) {
      String token = sign(invalid.expirationTime(Date.from(Instant.now().plusSeconds(300))));
      assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }
  }
}
