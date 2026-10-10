package dev.eufin.security;

import java.nio.charset.StandardCharsets;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {
  @Bean
  JwtDecoder decoder(
      @Value("${bank.jwt-secret}") String secret,
      @Value("${bank.issuer}") String issuer,
      @Value("${bank.jwk-set-uri}") String jwks,
      @Value("${bank.audience}") String audience) {
    NimbusJwtDecoder decoder;
    if (!jwks.isBlank()) decoder = NimbusJwtDecoder.withJwkSetUri(jwks).build();
    else {
      if (secret.getBytes(StandardCharsets.UTF_8).length < 32)
        throw new IllegalStateException(
            "Set BANK_JWK_SET_URI or BANK_JWT_SECRET (at least 32 bytes) for lab authentication");
      decoder =
          NimbusJwtDecoder.withSecretKey(
                  new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"))
              .build();
    }
    OAuth2TokenValidator<Jwt> aud =
        jwt ->
            jwt.getAudience().contains(audience)
                    && jwt.getSubject() != null
                    && !jwt.getSubject().isBlank()
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
    decoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer), aud));
    return decoder;
  }

  @Bean
  SecurityFilterChain security(HttpSecurity http, ApiGuard guard) throws Exception {
    return http.headers(
            h ->
                h.contentSecurityPolicy(
                        c ->
                            c.policyDirectives(
                                "default-src 'self'; script-src 'self'; style-src 'self';"
                                    + " connect-src 'self' http://localhost:8082; object-src"
                                    + " 'none'; base-uri 'none'; frame-ancestors 'none'"))
                    .referrerPolicy(
                        r ->
                            r.policy(
                                org.springframework.security.web.header.writers
                                    .ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)))
        // Keep CSRF protection. Resource-server defaults exempt only requests carrying
        // an explicit bearer token; cookie-only and anonymous unsafe requests stay protected.
        .csrf(org.springframework.security.config.Customizer.withDefaults())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            a ->
                a.requestMatchers(
                        "/actuator/health",
                        "/actuator/health/liveness",
                        "/actuator/health/readiness",
                        "/bank",
                        "/bank/**",
                        "/api/public/auth-config")
                    .permitAll()
                    .requestMatchers("/api/v1/staff/**")
                    .hasAuthority("SCOPE_bank.agent")
                    .requestMatchers("/api/v1/ops/**")
                    .hasAuthority("SCOPE_bank.ops")
                    .requestMatchers("/api/v1/open-banking/**")
                    .hasAuthority("SCOPE_bank.read")
                    .requestMatchers("/api/**")
                    .hasAuthority("SCOPE_bank.pay")
                    .anyRequest()
                    .denyAll())
        .addFilterAfter(
            guard,
            org.springframework.security.oauth2.server.resource.web.authentication
                .BearerTokenAuthenticationFilter.class)
        .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(jwtConverter())))
        .build();
  }

  private org.springframework.security.oauth2.server.resource.authentication
          .JwtAuthenticationConverter
      jwtConverter() {
    var converter =
        new org.springframework.security.oauth2.server.resource.authentication
            .JwtAuthenticationConverter();
    var scopes =
        new org.springframework.security.oauth2.server.resource.authentication
            .JwtGrantedAuthoritiesConverter();
    converter.setJwtGrantedAuthoritiesConverter(
        jwt -> {
          var authorities =
              new java.util.ArrayList<org.springframework.security.core.GrantedAuthority>(
                  scopes.convert(jwt));
          java.util.Map<String, Object> resources = jwt.getClaim("resource_access");
          if (resources != null
              && resources.get("eu-fin") instanceof java.util.Map<?, ?> access
              && access.get("roles") instanceof java.util.List<?> roles) {
            for (Object role : roles)
              if (java.util.Set.of("bank.pay", "bank.read", "bank.agent", "bank.ops")
                  .contains(role.toString()))
                authorities.add(
                    new org.springframework.security.core.authority.SimpleGrantedAuthority(
                        "SCOPE_" + role));
          }
          return authorities;
        });
    return converter;
  }

  @Bean
  org.springframework.boot.web.servlet.FilterRegistrationBean<ApiGuard> guardRegistration(
      ApiGuard guard) {
    var bean = new org.springframework.boot.web.servlet.FilterRegistrationBean<>(guard);
    bean.setEnabled(false);
    return bean;
  }
}
