# Browser end-to-end suite

The suite drives a real Spring Boot HTTP server and database. Only identity-provider authorization/token endpoints are mocked; it does not prove a live Keycloak login. Checks cover PKCE parameters, profile editing, registration, client/staff views, masked contacts and stored-XSS prevention. Screenshots and a JSON report are written to `target/browser`.

Install Node and dependencies: `npm install`, then `npx playwright install chromium`. Build `./mvnw package`. In a disposable test environment export random `BANK_JWT_SECRET` (at least 32 bytes) and `BANK_DATA_KEY` (base64 32 bytes), plus:

```sh
export BANK_DEMO_DATA_ENABLED=true
export BANK_OIDC_URL=http://localhost:8082/realms/eu-fin
export BANK_AUTO_CLEARING_ENABLED=true
java -jar target/eu-fin-0.1.0.jar --server.port=18080
```

In another terminal with the SAME JWT secret run `npm run test:e2e`. Server and browser must share the same network. Use only an isolated database: the suite edits customer data. This suite has not been executed in the supplied environment because no Chromium binary was available and its download host was blocked.

For an automated disposable run after building and installing Chromium: `bash e2e/run-local.sh`. GitHub Actions has a separate browser job; it has not run yet.
