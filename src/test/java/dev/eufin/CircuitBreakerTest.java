package dev.eufin;

import static org.assertj.core.api.Assertions.*;

import dev.eufin.clearing.ClearingGateway;
import io.github.resilience4j.circuitbreaker.*;
import org.junit.jupiter.api.Test;

class CircuitBreakerTest {
  @Test
  void repeatedFailuresOpenCircuitAndStopCallingAdapter() {
    var gateway = new ClearingGateway();
    for (int i = 0; i < 4; i++)
      assertThatThrownBy(
              () ->
                  gateway.guard(
                      () -> {
                        throw new IllegalStateException("simulated outage");
                      }))
          .isInstanceOf(IllegalStateException.class);
    assertThat(gateway.state()).isEqualTo(CircuitBreaker.State.OPEN);
    assertThatThrownBy(() -> gateway.clear("TIPS_SIM"))
        .isInstanceOf(CallNotPermittedException.class);
  }
}
