# CI source security checks

Semgrep Community scans src/main/java with six repository-owned rules: weak digests, ECB encryption, native deserialization, process execution, global hostname-verifier replacement, and external XML entities. Positive and negative fixtures must pass before the source scan. Findings fail CI; SARIF is preserved as an Actions artifact. Telemetry is disabled for scanning. No source is uploaded to a scanning SaaS.

This focused baseline is not equivalent to CodeQL's broader dataflow analysis and is not proof that code is secure. Trivy and Java security/integration tests run independently. CodeQL runs automatically when the repository is public. For private repositories, the owner must enable entitled Code Scanning and set the repository variable CODEQL_ENABLED=true. Its configuration failure is not suppressed with continue-on-error; unsupported execution is explicitly skipped. No paid feature is enabled automatically.

Semgrep is pinned to 1.180.0. Local commands:

```sh
semgrep --test security/semgrep
semgrep scan --config security/semgrep/java.yml --error --strict --metrics off src/main/java
```
