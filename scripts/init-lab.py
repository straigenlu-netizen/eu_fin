#!/usr/bin/env python3
"""Create local, random secrets. Never overwrites an existing environment file."""
import base64, pathlib, secrets
root=pathlib.Path(__file__).resolve().parent.parent
path=root/'.env'
if path.exists():raise SystemExit('.env already exists; refusing to replace your keys or credentials')
values={'BANK_JWT_SECRET':secrets.token_urlsafe(48),'BANK_DATA_KEY':base64.b64encode(secrets.token_bytes(32)).decode(),'DB_PASSWORD':secrets.token_urlsafe(32),'KEYCLOAK_ADMIN_PASSWORD':secrets.token_urlsafe(24)}
for name in ['ALICE','BOB','CARLA','DANIEL','EVA','AGENT','OPERATOR']:values['DEMO_'+name+'_PASSWORD']=secrets.token_urlsafe(18)
path.write_text('\n'.join(k+'='+v for k,v in values.items())+'\n');path.chmod(0o600)
print('Created local .env with random keys and demo credentials. Keep it private; do not commit it.')
