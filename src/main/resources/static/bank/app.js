'use strict';
// Tokens live only in memory. sessionStorage holds only the one-use PKCE verifier/state.
let staffPage=0;
let accessToken=null, idToken=null, profile=null, config=null;
const $=id=>document.getElementById(id);
const message=text=>{$('message').textContent=text;};
const node=(tag,text,className)=>{const el=document.createElement(tag);if(text!==undefined)el.textContent=text;if(className)el.className=className;return el;};
async function api(path,method='GET',body,headers={}){const r=await fetch('/api/v1'+path,{method,headers:{Authorization:'Bearer '+accessToken,...(body?{'Content-Type':'application/json'}:{}),...headers},body:body?JSON.stringify(body):undefined,cache:'no-store'});const text=await r.text();let data=null;try{data=text?JSON.parse(text):null;}catch{}if(!r.ok){const error=new Error(data?.title||'Request failed');error.status=r.status;throw error;}return data;}
function roles(token){const part=token.split('.')[1].replace(/-/g,'+').replace(/_/g,'/');const claims=JSON.parse(atob(part));return [...(claims.scope||'').split(' '),...(claims.resource_access?.['eu-fin']?.roles||[])];}
async function login(){if(!config.configured){message('Sign-in is not available yet. Please start the identity service described in the project guide.');return;}const random=()=>Array.from(crypto.getRandomValues(new Uint8Array(32)),b=>b.toString(16).padStart(2,'0')).join('');const verifier=random(),state=random();const digest=new Uint8Array(await crypto.subtle.digest('SHA-256',new TextEncoder().encode(verifier)));const challenge=btoa(String.fromCharCode(...digest)).replace(/\+/g,'-').replace(/\//g,'_').replace(/=+$/,'');sessionStorage.setItem('bank-pkce',JSON.stringify({verifier,state}));const params=new URLSearchParams({client_id:config.clientId,response_type:'code',scope:'openid',redirect_uri:location.origin+'/bank/',state,code_challenge:challenge,code_challenge_method:'S256'});location.assign(config.issuer+'/protocol/openid-connect/auth?'+params);}
async function callback(){const params=new URLSearchParams(location.search);if(!params.has('code'))return;const saved=JSON.parse(sessionStorage.getItem('bank-pkce')||'null');sessionStorage.removeItem('bank-pkce');history.replaceState(null,'','/bank/');if(!saved||saved.state!==params.get('state'))throw new Error('Sign-in verification failed. Please try again.');const r=await fetch(config.issuer+'/protocol/openid-connect/token',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:new URLSearchParams({client_id:config.clientId,grant_type:'authorization_code',code:params.get('code'),redirect_uri:location.origin+'/bank/',code_verifier:saved.verifier})});if(!r.ok)throw new Error('Sign-in failed');const tokens=await r.json();accessToken=tokens.access_token;idToken=tokens.id_token;const permissions=roles(accessToken);$('login').hidden=true;$('logout').hidden=false;$('welcome').hidden=true;$('workspace').hidden=false;$('staffTab').hidden=!permissions.includes('bank.agent');$('operationsTab').hidden=!permissions.includes('bank.ops');for(const id of ['profile','accounts','refunds'])document.querySelector('[data-tab="'+id+'"]').hidden=!permissions.includes('bank.pay');if(permissions.includes('bank.pay'))await loadProfile();else if(permissions.includes('bank.ops')){showTab('operations');await loadOperations();}else showTab('staff');}
function showTab(id){for(const p of ['profile','accounts','refunds','operations','staff'])$(p).hidden=p!==id;}
function formData(){const f=new FormData($('profileForm'));return {identity:{legalName:f.get('legalName'),dateOfBirth:f.get('dateOfBirth'),nationality:f.get('nationality')},contact:{email:f.get('email'),phone:f.get('phone'),address:f.get('address'),city:f.get('city'),country:f.get('country')}};}
async function loadProfile(){try{profile=await api('/customers/me');for(const [key,value] of Object.entries({...profile.identity,...profile.contact}))$('profileForm').elements.namedItem(key).value=value;$('profileStatus').textContent='Identity review: '+profile.kycStatus+(profile.suspended?' · Account access suspended':'');$('saveProfile').textContent='Save contact details';$('requestIdentity').hidden=false;for(const key of ['legalName','dateOfBirth','nationality'])$('profileForm').elements.namedItem(key).readOnly=true;await loadAccounts();}catch(e){if(e.status!==404)throw e;message('Welcome. Complete your profile to get started.');}}
async function loadAccounts(){const accounts=await api('/accounts');$('accountList').replaceChildren();$('debtor').replaceChildren();for(const a of accounts){const card=node('article',undefined,'card');card.append(node('p',a.iban),node('p',Number(a.balance).toFixed(2)+' EUR','balance'),node('p','Reserved: '+Number(a.held).toFixed(2)+' EUR'));$('accountList').append(card);const option=node('option',a.iban);option.value=a.id;$('debtor').append(option);}}
async function loadStaff(){const customers=await api('/staff/customers?page='+staffPage);$('previousCustomers').disabled=staffPage===0;$('nextCustomers').disabled=customers.length<25;$('customers').replaceChildren();for(const c of customers){const box=node('article',undefined,'customer');box.append(node('strong',c.legalName),node('p',c.emailMasked+' · '+c.phoneMasked),node('p',c.kycStatus+' · Risk '+c.riskTier+(c.suspended?' · Suspended':'')));const approve=node('button','Approve identity');approve.addEventListener('click',()=>action(()=>api('/staff/customers/'+encodeURIComponent(c.subject)+'/kyc','POST',{decision:'APPROVE'}).then(loadStaff)));const toggle=node('button',c.suspended?'Restore access':'Suspend access');toggle.addEventListener('click',()=>action(()=>api('/staff/customers/'+encodeURIComponent(c.subject),'PATCH',{riskTier:c.riskTier,suspended:!c.suspended,expectedVersion:c.version}).then(loadStaff)));box.append(approve,toggle);$('customers').append(box);}const changes=await api('/staff/identity-changes');$('changes').replaceChildren();for(const c of changes){const box=node('article',undefined,'customer');box.append(node('p','Customer '+c.subject+' · Pending correction'),node('p','Current: '+c.currentIdentity.legalName+' · '+c.currentIdentity.dateOfBirth+' · '+c.currentIdentity.nationality),node('p','Requested: '+c.proposedIdentity.legalName+' · '+c.proposedIdentity.dateOfBirth+' · '+c.proposedIdentity.nationality));for(const decision of ['APPROVE','REJECT']){const b=node('button',decision==='APPROVE'?'Approve correction':'Reject correction');b.addEventListener('click',()=>action(()=>api('/staff/identity-changes/'+encodeURIComponent(c.id)+'/review','POST',{decision}).then(loadStaff)));box.append(b);}$('changes').append(box);}}
async function action(fn){try{await fn();}catch(e){message(e.message.replace(/_/g,' '));}}
$('login').addEventListener('click',()=>action(login));$('logout').addEventListener('click',()=>{const params=new URLSearchParams({client_id:config.clientId,post_logout_redirect_uri:location.origin+'/bank/'});if(idToken)params.set('id_token_hint',idToken);accessToken=null;idToken=null;profile=null;location.assign(config.issuer+'/protocol/openid-connect/logout?'+params);});document.querySelectorAll('[data-tab]').forEach(b=>b.addEventListener('click',()=>action(async()=>{showTab(b.dataset.tab);if(b.dataset.tab==='staff')await loadStaff();if(b.dataset.tab==='accounts')await loadAccounts();if(b.dataset.tab==='operations')await loadOperations();})));$('refreshStaff').addEventListener('click',()=>action(loadStaff));
$('profileForm').addEventListener('submit',e=>{e.preventDefault();action(async()=>{const data=formData();if(profile)await api('/customers/me/contact','PUT',{contact:data.contact,expectedVersion:profile.version});else await api('/customers/me','POST',data);await loadProfile();message('Profile saved.');});});
$('requestIdentity').addEventListener('click',()=>{for(const key of ['legalName','dateOfBirth','nationality'])$('profileForm').elements.namedItem(key).readOnly=false;const button=$('requestIdentity');if(button.dataset.editing){action(async()=>{await api('/customers/me/identity-changes','POST',{identity:formData().identity,expectedVersion:profile.version});button.dataset.editing='';button.textContent='Request identity correction';await loadProfile();message('Identity correction submitted for review.');});}else{button.dataset.editing='true';button.textContent='Submit identity correction';message('Edit your identity details, then submit the correction.');}});
$('accountForm').addEventListener('submit',e=>{e.preventDefault();action(async()=>{await api('/accounts','POST',{iban:new FormData(e.target).get('iban'),name:profile.identity.legalName});await loadAccounts();message('EUR account opened.');});});
$('paymentForm').addEventListener('submit',e=>{e.preventDefault();action(async()=>{const body=Object.fromEntries(new FormData(e.target));body.amount=Number(body.amount);const verified=await api('/payees/verify','POST',{iban:body.creditorIban,name:body.creditorName});delete body.creditorIban;body.creditorId=verified.creditorId;const p=await api('/payments','POST',body,{'Idempotency-Key':crypto.randomUUID()});$('paymentResult').replaceChildren(node('p','Payment '+p.id+' · '+p.status));await loadAccounts();message('Payment accepted and funds reserved.');setTimeout(()=>action(async()=>{const current=await api('/payments/'+encodeURIComponent(p.id));$('paymentResult').replaceChildren(node('p','Payment '+current.id+' · '+current.status));await loadAccounts();}),1000);});});
(async()=>{try{config=await(await fetch('/api/public/auth-config',{cache:'no-store'})).json();await callback();}catch(e){message(e.message);}})();

$('previousCustomers').addEventListener('click',()=>action(async()=>{staffPage=Math.max(0,staffPage-1);await loadStaff();}));
$('nextCustomers').addEventListener('click',()=>action(async()=>{staffPage++;await loadStaff();}));

// Reimbursement, investigation and recovery are separate user actions.
let refundPayment = null, operationsPage = 0;
const disputeLabel = status => ({REFUNDED_UNDER_REVIEW:'Refund credited; investigation in progress',
  CONFIRMED_UNAUTHORISED:'Unauthorised debit confirmed', AUTHORISATION_EVIDENCE_FOUND:'Authorisation evidence found; refund retained'})[status] || status;
async function loadRefundPayment() {
  refundPayment = null;
  $('requestSddRefund').hidden = true; $('sddDisputeForm').hidden = true;
  $('refundDetails').replaceChildren(); $('disputeDetails').replaceChildren();
  const payment = await api('/payments/' + encodeURIComponent($('refundPaymentId').value.trim()));
  const accounts = await api('/accounts');
  if (payment.scheme !== 'SDD_CORE' || !accounts.some(a => a.id === payment.debtorId))
    throw new Error('Only the payer can request a direct debit refund.');
  refundPayment = payment;
  $('refundDetails').append(node('p', payment.amount + ' EUR · ' + payment.status.replaceAll('_',' ')));
  $('requestSddRefund').hidden = payment.status !== 'SETTLED';
  $('sddDisputeForm').hidden = payment.status !== 'SETTLED';
  try { const claim = await api('/payments/' + encodeURIComponent(payment.id) + '/sdd-dispute');
    $('disputeDetails').append(node('p', disputeLabel(claim.status)));
  } catch (error) { if (error.status !== 404) throw error; }
}
$('refundLookup').addEventListener('submit', e => { e.preventDefault(); action(loadRefundPayment); });
$('requestSddRefund').addEventListener('click', () => action(async () => {
  if (!refundPayment) return;
  const button = $('requestSddRefund'); button.disabled = true;
  try { await api('/payments/' + encodeURIComponent(refundPayment.id) + '/refund','POST');
    await loadRefundPayment(); await loadAccounts(); message('Your direct debit refund has been credited.');
  } finally { button.disabled = false; }
}));
$('sddDisputeForm').addEventListener('submit', e => { e.preventDefault(); action(async () => {
  if (!refundPayment) return;
  const button = e.target.querySelector('button'); button.disabled = true;
  try { await api('/payments/' + encodeURIComponent(refundPayment.id) + '/sdd-dispute','POST',
      {allegation:$('sddAllegation').value});
    await loadRefundPayment(); await loadAccounts(); message('Your refund has been credited. The bank will investigate separately.');
  } finally { button.disabled = false; }
}); });
async function loadOperations() {
  const [claims, refunds] = await Promise.all([api('/ops/sdd-disputes?page='+operationsPage), api('/ops/sdd-refunds?page='+operationsPage)]);
  $('sddCases').replaceChildren(); $('sddRecoveries').replaceChildren();
  if (!claims.length) $('sddCases').append(node('p','No dispute cases on this page.'));
  for (const claim of claims) {
    const card = node('article',undefined,'customer');
    card.append(node('p','Payment '+claim.paymentId),node('p',disputeLabel(claim.status)));
    if (claim.status === 'REFUNDED_UNDER_REVIEW') {
      const form = node('form'), decision = node('select');
      for (const [value,text] of [['CONFIRMED_UNAUTHORISED','Confirm unauthorised debit'],['AUTHORISATION_EVIDENCE_FOUND','Record authorisation evidence']]) {
        const option = node('option',text); option.value = value; decision.append(option);
      }
      const evidence = node('input'); evidence.placeholder = 'Evidence SHA-256, required when evidence was found';
      evidence.pattern = '[a-fA-F0-9]{64}'; evidence.maxLength = 64; evidence.setAttribute('aria-label','Evidence SHA-256');
      decision.setAttribute('aria-label','Investigation decision');
      const submit = node('button','Save investigation decision'); form.append(decision,evidence,submit);
      form.addEventListener('submit',e => { e.preventDefault(); action(async () => {
        submit.disabled = true;
        try { await api('/ops/sdd-disputes/'+encodeURIComponent(claim.id)+'/decision','POST',
            {decision:decision.value,...(evidence.value?{evidenceHash:evidence.value}:{})}); await loadOperations();
        } finally { submit.disabled = false; }
      }); }); card.append(form);
    } $('sddCases').append(card);
  }
  if (!refunds.length) $('sddRecoveries').append(node('p','No refund recoveries on this page.'));
  for (const refund of refunds) {
    const card = node('article',undefined,'customer');
    card.append(node('p','Payment '+refund.paymentId),node('p','Outstanding: '+refund.outstandingAmount+' EUR'));
    if (Number(refund.outstandingAmount) > 0) {
      const form = node('form'), amount = node('input'), submit = node('button','Recover from creditor');
      amount.type = 'number'; amount.min = '0.01'; amount.max = String(refund.outstandingAmount); amount.step = '0.01';
      amount.required = true; amount.setAttribute('aria-label','Recovery amount in EUR');
      const key = crypto.randomUUID(); form.append(amount,submit);
      form.addEventListener('submit',e => { e.preventDefault(); action(async () => {
        submit.disabled = true;
        try { await api('/ops/sdd-refunds/'+encodeURIComponent(refund.paymentId)+'/recoveries','POST',
            {amount:Number(amount.value)},{'Idempotency-Key':key}); await loadOperations();
        } finally { submit.disabled = false; }
      }); }); card.append(form);
    } $('sddRecoveries').append(card);
  }
}
$('refreshOperations').addEventListener('click',() => action(loadOperations));
$('previousOperations').addEventListener('click',() => action(async () => { operationsPage = Math.max(0,operationsPage-1); await loadOperations(); }));
$('nextOperations').addEventListener('click',() => action(async () => { operationsPage++; await loadOperations(); }));
