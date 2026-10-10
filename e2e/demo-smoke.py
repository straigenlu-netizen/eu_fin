"""Verify five demo identities against the real HTTP server (stdlib only)."""
import json,subprocess,urllib.request
from pathlib import Path
root=Path(__file__).resolve().parent.parent

def get(path,subject,scope):
    token=subprocess.check_output(['python3',str(root/'scripts/lab-token.py'),subject,scope],text=True).strip()
    request=urllib.request.Request('http://localhost:18080/api/v1'+path,headers={'Authorization':'Bearer '+token})
    with urllib.request.urlopen(request,timeout=5) as response:return json.load(response)

customers=get('/staff/customers','agent','bank.agent')
assert len(customers)==5
for login,name in [('alice','Alice Demo'),('bob','Bob Example'),('carla','Carla Sample'),('daniel','Daniel Demo'),('eva','Eva Example')]:
    profile=get('/customers/me',login,'bank.pay')
    assert profile['identity']['legalName']==name
    assert profile['kycStatus']=='VERIFIED'
    accounts=get('/accounts',login,'bank.pay')
    assert len(accounts)==1 and float(accounts[0]['balance'])==1000
print('Demo HTTP smoke passed: 5 profiles, 5 accounts, verified status, 1000 EUR each')
