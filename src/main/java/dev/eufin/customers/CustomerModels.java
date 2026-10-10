package dev.eufin.customers;

import jakarta.validation.constraints.*;
import java.time.LocalDate;

public final class CustomerModels {
  private CustomerModels() {}

  public record Identity(
      @NotBlank @Size(max = 140) String legalName,
      @NotNull @Past LocalDate dateOfBirth,
      @NotBlank @Pattern(regexp = "[A-Z]{2}") String nationality) {}

  public record Contact(
      @NotBlank @Email @Size(max = 254) String email,
      @NotBlank @Pattern(regexp = "\\+[1-9][0-9]{7,14}") String phone,
      @NotBlank @Size(max = 200) String address,
      @NotBlank @Size(max = 100) String city,
      @NotBlank @Pattern(regexp = "[A-Z]{2}") String country) {}

  public record Register(
      @NotNull @jakarta.validation.Valid Identity identity,
      @NotNull @jakarta.validation.Valid Contact contact) {}

  public record UpdateContact(
      @NotNull @jakarta.validation.Valid Contact contact, @NotNull @Min(0) Long expectedVersion) {}

  public record UpdateIdentity(
      @NotNull @jakarta.validation.Valid Identity identity,
      @NotNull @Min(0) Long expectedVersion) {}

  public record StaffUpdate(
      @NotBlank @Pattern(regexp = "LOW|MEDIUM|HIGH") String riskTier,
      @NotNull Boolean suspended,
      @NotNull @Min(0) Long expectedVersion) {}

  public record Review(@NotBlank @Pattern(regexp = "APPROVE|REJECT") String decision) {}
}
