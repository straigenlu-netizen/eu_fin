package dev.eufin;

import static org.assertj.core.api.Assertions.*;

import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.security.KeyStore;
import javax.net.ssl.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"test", "mtls"})
class MtlsE2ETest {
  static final char[] PASSWORD = "test-only-certificate-password".toCharArray();
  static final Path DIR = certificates();
  @LocalServerPort int port;

  static Path certificates() {
    try {
      Path dir = Files.createTempDirectory("eufin-tls-");
      for (String name : new String[] {"server", "client"}) {
        String[] args = {
          "keytool",
          "-genkeypair",
          "-alias",
          name,
          "-keyalg",
          "RSA",
          "-keysize",
          "2048",
          "-validity",
          "2",
          "-dname",
          "CN=localhost",
          "-ext",
          "SAN=dns:localhost",
          "-storetype",
          "PKCS12",
          "-keystore",
          dir.resolve(name + ".p12").toString(),
          "-storepass",
          new String(PASSWORD),
          "-keypass",
          new String(PASSWORD),
          "-noprompt"
        };
        run(args);
        run(
            "keytool",
            "-exportcert",
            "-alias",
            name,
            "-keystore",
            dir.resolve(name + ".p12").toString(),
            "-storepass",
            new String(PASSWORD),
            "-file",
            dir.resolve(name + ".cer").toString());
        run(
            "keytool",
            "-importcert",
            "-alias",
            name,
            "-file",
            dir.resolve(name + ".cer").toString(),
            "-keystore",
            dir.resolve("trust.p12").toString(),
            "-storetype",
            "PKCS12",
            "-storepass",
            new String(PASSWORD),
            "-noprompt");
      }
      return dir;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  static void run(String... command) throws Exception {
    Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
    String log = new String(p.getInputStream().readAllBytes());
    if (p.waitFor() != 0) throw new IllegalStateException(log);
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry p) {
    p.add("TLS_KEY_STORE", () -> DIR.resolve("server.p12").toString());
    p.add("TLS_TRUST_STORE", () -> DIR.resolve("trust.p12").toString());
    p.add("TLS_PASSWORD", () -> new String(PASSWORD));
  }

  HttpClient client(boolean certificate) throws Exception {
    KeyStore trust = KeyStore.getInstance("PKCS12");
    try (var in = Files.newInputStream(DIR.resolve("trust.p12"))) {
      trust.load(in, PASSWORD);
    }
    var tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    tm.init(trust);
    KeyManager[] keys = null;
    if (certificate) {
      KeyStore key = KeyStore.getInstance("PKCS12");
      try (var in = Files.newInputStream(DIR.resolve("client.p12"))) {
        key.load(in, PASSWORD);
      }
      var km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
      km.init(key, PASSWORD);
      keys = km.getKeyManagers();
    }
    var ssl = SSLContext.getInstance("TLS");
    ssl.init(keys, tm.getTrustManagers(), null);
    return HttpClient.newBuilder().sslContext(ssl).build();
  }

  @Test
  void tlsRequiresClientCertificateAndJwtStillControlsAccess() throws Exception {
    var health =
        HttpRequest.newBuilder(URI.create("https://localhost:" + port + "/actuator/health"))
            .GET()
            .build();
    assertThatThrownBy(() -> client(false).send(health, HttpResponse.BodyHandlers.ofString()))
        .isInstanceOf(java.io.IOException.class);
    assertThat(client(true).send(health, HttpResponse.BodyHandlers.ofString()).statusCode())
        .isEqualTo(200);
    var accounts =
        HttpRequest.newBuilder(URI.create("https://localhost:" + port + "/api/v1/accounts"))
            .GET()
            .build();
    assertThat(client(true).send(accounts, HttpResponse.BodyHandlers.ofString()).statusCode())
        .isEqualTo(401);
    String jwt = new HttpE2ETest().token("alice", "bank.pay");
    var authenticated =
        HttpRequest.newBuilder(accounts.uri())
            .header("Authorization", "Bearer " + jwt)
            .GET()
            .build();
    assertThat(client(true).send(authenticated, HttpResponse.BodyHandlers.ofString()).statusCode())
        .isEqualTo(200);
  }
}
