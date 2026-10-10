# Infrastructure lab

`compose.yml` uses a local single-broker Kafka and PostgreSQL. No real bank connections.
Set random `DB_PASSWORD` and `BANK_JWT_SECRET` in your shell or a gitignored `.env`.
Kafka and Registry have no authentication in this lab. Ports bind to localhost.

`nginx.conf` is an optional gateway example (rate and body limits), not enabled in Compose.
`kubernetes.yml` is a deployment template, not a provisioned cluster. Create `bank-runtime`
Secret with DB_URL, DB_USER, DB_PASSWORD, KAFKA_BOOTSTRAP, BANK_KAFKA_ENABLED,
BANK_JWK_SET_URI and BANK_ISSUER. Do not use H2 for a deployed application.
Enable actuator probe endpoints via `MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED=true`.

mTLS: activate `mtls` profile and set TLS_KEY_STORE, TLS_TRUST_STORE, TLS_PASSWORD.
Client certificates must chain to your trust store; JWT authorization is also required.
Certificate lifecycle and production PKI are outside this lab.
OIDC: set BANK_JWK_SET_URI to the provider JWKS endpoint, BANK_ISSUER to its exact issuer.
Tokens must contain audience `eu-fin` and scopes `bank.pay`, `bank.ops`, or `bank.read`.
The backend is a resource server. The UI uses authorization-code PKCE with Keycloak via compose.oidc.yml. This is not payment SCA/dynamic linking. The backend does not issue tokens.

CI executes unit/integration/HTTP e2e and real PostgreSQL/Kafka Testcontainers tests on
Java 17 and 21. CodeQL may need GitHub Code Security enabled for a private repository.
Trivy is configured but scan outcomes are not assumed until CI actually runs.
