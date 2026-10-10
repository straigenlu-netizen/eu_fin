#!/usr/bin/env python3
"""Generate an HS256 lab token using only the Python standard library. No auth server."""
import base64, hashlib, hmac, json, os, sys, time
secret=os.environ.get('BANK_JWT_SECRET','')
if len(secret.encode())<32:
    raise SystemExit('Set BANK_JWT_SECRET to at least 32 random bytes first')
subject=sys.argv[1] if len(sys.argv)>1 else 'alice'
scopes=sys.argv[2] if len(sys.argv)>2 else 'bank.pay'
if os.environ.get('BANK_DEMO_DATA_ENABLED','false').lower()=='true':
    aliases={'alice':1,'bob':2,'carla':3,'daniel':4,'eva':5}
    if subject in aliases: subject=f'00000000-0000-0000-0000-{aliases[subject]:012d}'
def b64(value): return base64.urlsafe_b64encode(value).rstrip(b'=').decode()
header=b64(json.dumps({'alg':'HS256','typ':'JWT'}).encode())
claims=b64(json.dumps({'iss':os.environ.get('BANK_ISSUER','eu-fin-lab'),'aud':['eu-fin'],'sub':subject,'scope':scopes,'amr':['mfa'],'iat':int(time.time()),'exp':int(time.time())+900}).encode())
unsigned=header+'.'+claims
print(unsigned+'.'+b64(hmac.new(secret.encode(),unsigned.encode(),hashlib.sha256).digest()))
