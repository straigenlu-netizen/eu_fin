package dev.eufin.domain;

public class BankError extends RuntimeException {
  public final String code;
  public final int status;

  public BankError(String code, int status) {
    super(code);
    this.code = code;
    this.status = status;
  }

  public static BankError invalid(String code) {
    return new BankError(code, 422);
  }
}
