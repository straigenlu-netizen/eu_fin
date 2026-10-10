package dev.eufin.security;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Per-subject lab rate limit and mandatory MFA for staff/ops. Never logs tokens or PII. */
@Component
public class ApiGuard extends OncePerRequestFilter {
  private static final class Window {
    long minute;
    int count;

    Window(long minute) {
      this.minute = minute;
    }
  }

  private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
  private final Clock clock;
  private final int limit;

  public ApiGuard(Clock clock, @Value("${bank.requests-per-minute:120}") int limit) {
    this.clock = clock;
    this.limit = limit;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    if (req.getRequestURI().startsWith("/api/")
        && auth != null
        && auth.getPrincipal() instanceof Jwt jwt) {
      if (req.getRequestURI().startsWith("/api/v1/staff/")
          || req.getRequestURI().startsWith("/api/v1/ops/")) {
        var amr = jwt.getClaimAsStringList("amr");
        if (amr == null || !amr.contains("mfa")) {
          deny(res, 403, "MFA_REQUIRED");
          return;
        }
      }
      long minute = clock.instant().getEpochSecond() / 60;
      if (windows.size() > 10000) windows.entrySet().removeIf(e -> e.getValue().minute < minute);
      if (windows.size() > 10000 && !windows.containsKey(jwt.getSubject())) {
        deny(res, 429, "RATE_LIMITED");
        return;
      }
      Window w = windows.computeIfAbsent(jwt.getSubject(), k -> new Window(minute));
      synchronized (w) {
        if (w.minute != minute) {
          w.minute = minute;
          w.count = 0;
        }
        if (++w.count > limit) {
          res.setHeader("Retry-After", "60");
          deny(res, 429, "RATE_LIMITED");
          return;
        }
      }
    }
    chain.doFilter(req, res);
  }

  private void deny(HttpServletResponse res, int status, String code) throws IOException {
    res.setStatus(status);
    res.setContentType("application/problem+json");
    res.getWriter().write("{\"title\":\"" + code + "\",\"status\":" + status + "}");
  }
}
