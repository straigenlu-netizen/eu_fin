#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
export BANK_JWT_SECRET=$(python3 -c 'import secrets; print(secrets.token_urlsafe(48))')
export BANK_DATA_KEY=$(python3 -c 'import secrets,base64; print(base64.b64encode(secrets.token_bytes(32)).decode())')
export BANK_DEMO_DATA_ENABLED=true
export BANK_OIDC_URL=http://localhost:8082/realms/eu-fin
export BANK_AUTO_CLEARING_ENABLED=true
# Disposable H2 only; never point this script at production data.
java -jar target/eu-fin-0.1.0.jar --server.port=18080 > target/browser-server.log 2>&1 &
server_pid=$!
trap 'kill "$server_pid" 2>/dev/null || true' EXIT
python3 - <<'PY'
import time,urllib.request
for attempt in range(150):
    try:
        urllib.request.urlopen('http://localhost:18080/actuator/health',timeout=1)
        break
    except Exception:
        time.sleep(.2)
else:
    raise SystemExit('Bank server failed to start; inspect target/browser-server.log')
PY
python3 e2e/demo-smoke.py
if [[ "${EUFIN_SMOKE_ONLY:-false}" != "true" ]]; then npm run test:e2e; fi
