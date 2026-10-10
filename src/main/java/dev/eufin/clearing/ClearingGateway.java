package dev.eufin.clearing;

import io.github.resilience4j.circuitbreaker.*;
import java.time.Duration;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/** Lab seam: replace Supplier with a remote adapter, never a real TIPS connection. */
@Component
public class ClearingGateway {
  private final CircuitBreaker breaker =
      CircuitBreaker.of(
          "clearing",
          CircuitBreakerConfig.custom()
              .slidingWindowSize(4)
              .minimumNumberOfCalls(4)
              .failureRateThreshold(50)
              .waitDurationInOpenState(Duration.ofSeconds(30))
              .build());

  public void clear(String route) {
    guard(
        () -> {
          if (!java.util.Set.of("SEPA_CLEARING", "SDD_CLEARING", "TIPS_SIM", "RT1_SIM")
              .contains(route)) throw new IllegalArgumentException("Unknown clearing route");
          return true;
        });
  }

  public <T> T guard(Supplier<T> operation) {
    return CircuitBreaker.decorateSupplier(breaker, operation).get();
  }

  public CircuitBreaker.State state() {
    return breaker.getState();
  }
}
