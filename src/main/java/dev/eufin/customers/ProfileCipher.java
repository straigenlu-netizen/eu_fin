package dev.eufin.customers;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Versioned AES-256-GCM envelope. Subject as AAD prevents swapping customer ciphertext. */
@Component
public class ProfileCipher {
  private final byte[] key;
  private final SecureRandom random = new SecureRandom();

  public ProfileCipher(@Value("${bank.data-key}") String encoded) {
    try {
      key = Base64.getDecoder().decode(encoded);
    } catch (Exception e) {
      throw new IllegalStateException("BANK_DATA_KEY must be base64 AES-256 key");
    }
    if (key.length != 32) throw new IllegalStateException("BANK_DATA_KEY must decode to 32 bytes");
  }

  public String encrypt(String subject, String value) {
    try {
      byte[] nonce = new byte[12];
      random.nextBytes(nonce);
      var cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
      cipher.updateAAD(subject.getBytes(StandardCharsets.UTF_8));
      return "v1:"
          + Base64.getEncoder().encodeToString(nonce)
          + ":"
          + Base64.getEncoder()
              .encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException("Profile encryption failed", e);
    }
  }

  public String decrypt(String subject, String envelope) {
    try {
      String[] parts = envelope.split(":");
      if (parts.length != 3 || !parts[0].equals("v1")) throw new IllegalArgumentException();
      var cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.DECRYPT_MODE,
          new SecretKeySpec(key, "AES"),
          new GCMParameterSpec(128, Base64.getDecoder().decode(parts[1])));
      cipher.updateAAD(subject.getBytes(StandardCharsets.UTF_8));
      return new String(
          cipher.doFinal(Base64.getDecoder().decode(parts[2])), StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new IllegalStateException("Profile integrity check failed", e);
    }
  }
}
