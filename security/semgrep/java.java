class SecurityRuleFixtures {
  void rejected() throws Exception {
    // ruleid: bank-no-weak-digest
    MessageDigest.getInstance("MD5");
    // ruleid: bank-no-weak-digest
    MessageDigest.getInstance("SHA-1");
    // ruleid: bank-no-ecb-cipher
    Cipher.getInstance("AES/ECB/PKCS5Padding");
    // ruleid: bank-no-native-deserialization
    new ObjectInputStream(input);
    // ruleid: bank-no-process-execution
    Runtime.getRuntime().exec(command);
    // ruleid: bank-no-process-execution
    new ProcessBuilder(command);
    // ruleid: bank-no-tls-hostname-bypass
    HttpsURLConnection.setDefaultHostnameVerifier(verifier);
    // ruleid: bank-no-external-xml-entities
    factory.setFeature("http://xml.org/sax/features/external-general-entities", true);
    // ruleid: bank-no-external-xml-entities
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", true);
  }
  void accepted() throws Exception {
    // ok: bank-no-weak-digest
    MessageDigest.getInstance("SHA-256");
    // ok: bank-no-ecb-cipher
    Cipher.getInstance("AES/GCM/NoPadding");
    // ok: bank-no-external-xml-entities
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
  }
}
