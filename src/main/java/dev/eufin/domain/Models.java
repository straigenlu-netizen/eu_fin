package dev.eufin.domain;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;

public final class Models {
  private Models() {}

  public record Actor(String subject, boolean operator) {}

  public record OpenAccount(
      @NotBlank @Size(max = 34) String iban, @NotBlank @Size(max = 140) String name) {}

  public record Account(
      String id, String iban, String ownerId, String name, BigDecimal balance, BigDecimal held) {
    public BigDecimal available() {
      return balance.subtract(held);
    }
  }

  public record Initiate(
      @NotBlank String debtorId,
      @NotBlank String creditorId,
      @NotNull BigDecimal amount,
      @NotBlank @Pattern(regexp = "SCT|SCT_INST|SDD_CORE") String scheme,
      @NotBlank @Size(max = 35) String endToEndId,
      @NotBlank @Size(max = 140) String creditorName,
      String mandateId) {}

  public record Payment(
      String id,
      String actor,
      String debtorId,
      String creditorId,
      BigDecimal amount,
      String scheme,
      String endToEndId,
      String mandateId,
      String status,
      String route,
      String reason,
      Instant createdAt,
      Instant deadline,
      Instant settledAt) {}

  public record Amount(@NotNull BigDecimal amount) {}

  public record NewMandate(
      @NotBlank String debtorId,
      @NotBlank String creditorId,
      @NotNull java.time.LocalDate validUntil,
      @NotNull BigDecimal maxAmount) {}

  public record NewConsent(
      @NotBlank String accountId,
      @NotBlank @Size(max = 100) String tpp,
      @NotNull Instant expiresAt) {}

  public record ReconcileLine(
      @NotBlank String paymentId, @NotNull BigDecimal amount, @NotBlank String status) {}

  public record Transition(
      @NotBlank @Pattern(regexp = "SCREEN|CLEAR|SETTLE|REJECT|RETURN|TIMEOUT|RECALL")
          String action) {}
}
