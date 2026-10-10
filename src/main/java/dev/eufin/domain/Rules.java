package dev.eufin.domain;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

public final class Rules {
  private Rules() {}

  private static final Map<String, Integer> LENGTHS =
      Map.of(
          "DE", 22, "PL", 28, "FR", 27, "NL", 18, "BE", 16, "ES", 24, "IT", 27, "IE", 22, "AT", 20,
          "FI", 18);

  public static String iban(String raw) {
    if (raw == null) throw BankError.invalid("INVALID_IBAN");
    String s = raw.replace(" ", "").toUpperCase(java.util.Locale.ROOT);
    if (!s.matches("[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}")
        || !LENGTHS.containsKey(s.substring(0, 2))
        || s.length() != LENGTHS.get(s.substring(0, 2)))
      throw BankError.invalid("INVALID_OR_UNSUPPORTED_IBAN");
    String moved = s.substring(4) + s.substring(0, 4);
    StringBuilder n = new StringBuilder();
    for (char c : moved.toCharArray())
      n.append(c >= 'A' && c <= 'Z' ? Integer.toString(c - 'A' + 10) : Character.toString(c));
    if (!new BigInteger(n.toString()).mod(BigInteger.valueOf(97)).equals(BigInteger.ONE))
      throw BankError.invalid("INVALID_IBAN");
    return s;
  }

  public static BigDecimal money(BigDecimal n) {
    if (n == null
        || n.signum() <= 0
        || n.scale() > 2
        || n.compareTo(new BigDecimal("99999999999999999.99")) > 0)
      throw BankError.invalid("INVALID_AMOUNT");
    return n.setScale(2);
  }

  public static String fingerprint(Object value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(value.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public static boolean nameMatches(String a, String b) {
    return a.strip().replaceAll("\\s+", " ").equalsIgnoreCase(b.strip().replaceAll("\\s+", " "));
  }

  public static String route(String scheme, String iban) {
    return switch (scheme) {
      case "SCT" -> "SEPA_CLEARING";
      case "SCT_INST" -> iban.startsWith("DE") ? "TIPS_SIM" : "RT1_SIM";
      case "SDD_CORE" -> "SDD_CLEARING";
      default -> throw BankError.invalid("UNSUPPORTED_SCHEME");
    };
  }
}
