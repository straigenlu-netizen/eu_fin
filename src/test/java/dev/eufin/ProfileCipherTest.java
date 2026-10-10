package dev.eufin;

import static org.assertj.core.api.Assertions.*;

import dev.eufin.customers.ProfileCipher;
import org.junit.jupiter.api.Test;

class ProfileCipherTest {
  @Test
  void encryptionUsesFreshNonceAndRejectsTamperingOrSwappedCustomer() {
    var cipher = new ProfileCipher("dGVzdC1vbmx5LWF1dGhlbnRpY2F0aW9uLWtleS0xMjM=");
    String a = cipher.encrypt("alice", "private data"), b = cipher.encrypt("alice", "private data");
    assertThat(a).isNotEqualTo(b);
    assertThat(cipher.decrypt("alice", a)).isEqualTo("private data");
    assertThatThrownBy(() -> cipher.decrypt("bob", a)).isInstanceOf(IllegalStateException.class);
    String[] p = a.split(":");
    byte[] bytes = java.util.Base64.getDecoder().decode(p[2]);
    bytes[0] ^= 1;
    String changed = p[0] + ":" + p[1] + ":" + java.util.Base64.getEncoder().encodeToString(bytes);
    assertThatThrownBy(() -> cipher.decrypt("alice", changed))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void shortKeysFailClosed() {
    assertThatThrownBy(() -> new ProfileCipher("YWJj")).isInstanceOf(IllegalStateException.class);
  }
}
