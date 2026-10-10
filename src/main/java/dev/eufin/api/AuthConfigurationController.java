package dev.eufin.api;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

@RestController
public class AuthConfigurationController {
  private final String issuer;

  public AuthConfigurationController(@Value("${bank.oidc-url}") String issuer) {
    this.issuer = issuer;
  }

  @GetMapping("/api/public/auth-config")
  public Object config() {
    return Map.of("issuer", issuer, "clientId", "eu-fin", "configured", !issuer.isBlank());
  }
}
