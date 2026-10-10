package dev.eufin.api;

import static dev.eufin.domain.Models.*;

import dev.eufin.domain.BankService;
import dev.eufin.iso.IsoService;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class BankController {
  private final BankService bank;
  private final IsoService iso;

  public BankController(BankService bank, IsoService iso) {
    this.bank = bank;
    this.iso = iso;
  }

  private Actor actor(Authentication a) {
    return new Actor(
        ((Jwt) a.getPrincipal()).getSubject(),
        a.getAuthorities().stream().anyMatch(g -> g.getAuthority().equals("SCOPE_bank.ops")));
  }

  @PostMapping("/accounts")
  public Account open(Authentication a, @Valid @RequestBody OpenAccount r) {
    return bank.open(actor(a), r);
  }

  @GetMapping("/accounts")
  public List<Account> accounts(Authentication a) {
    return bank.accounts(actor(a));
  }

  @GetMapping("/accounts/{id}")
  public Account account(Authentication a, @PathVariable String id) {
    return bank.account(actor(a), id);
  }

  @PostMapping("/ops/accounts/{id}/fund")
  public Account fund(
      Authentication a,
      @PathVariable String id,
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody Amount amount) {
    return bank.fund(actor(a), id, key, amount.amount());
  }

  public record Payee(
      @jakarta.validation.constraints.NotBlank String iban,
      @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 140)
          String name) {}

  @PostMapping("/payees/verify")
  public Map<String, String> verifyPayee(@Valid @RequestBody Payee request) {
    return Map.of(
        "creditorId", bank.verifyPayee(request.iban(), request.name()), "result", "MATCH");
  }

  @PostMapping("/payments")
  public Payment pay(
      Authentication a,
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody Initiate r) {
    return bank.initiate(actor(a), key, r);
  }

  @GetMapping("/payments/{id}")
  public Payment payment(Authentication a, @PathVariable String id) {
    return bank.payment(actor(a), id);
  }

  @GetMapping("/payments/{id}/events")
  public Object events(Authentication a, @PathVariable String id) {
    return bank.history(actor(a), id);
  }

  @PostMapping("/ops/payments/{id}/transitions")
  public Payment transition(
      Authentication a, @PathVariable String id, @Valid @RequestBody Transition r) {
    return bank.transition(actor(a), id, r.action());
  }

  @PostMapping("/payments/{id}/refund")
  public Payment refund(Authentication a, @PathVariable String id) {
    return bank.refund(actor(a), id);
  }

  @GetMapping("/accounts/{id}/statement")
  public Object statement(Authentication a, @PathVariable String id) {
    return bank.statement(actor(a), id);
  }

  @PostMapping("/mandates")
  public Object mandate(Authentication a, @Valid @RequestBody NewMandate r) {
    return Map.of("id", bank.mandate(actor(a), r));
  }

  @DeleteMapping("/mandates/{id}")
  public void revokeMandate(Authentication a, @PathVariable String id) {
    bank.revokeMandate(actor(a), id);
  }

  @PostMapping("/consents")
  public Object consent(Authentication a, @Valid @RequestBody NewConsent r) {
    return Map.of("id", bank.consent(actor(a), r));
  }

  @DeleteMapping("/consents/{id}")
  public void revokeConsent(Authentication a, @PathVariable String id) {
    bank.revokeConsent(actor(a), id);
  }

  @GetMapping("/open-banking/account")
  public Account openBanking(Authentication a, @RequestHeader("Consent-ID") String consent) {
    return bank.openBanking(((Jwt) a.getPrincipal()).getSubject(), consent);
  }

  @PostMapping("/ops/reconciliation")
  public Object reconcile(Authentication a, @RequestBody List<@Valid ReconcileLine> r) {
    return bank.reconcile(actor(a), r);
  }

  @PostMapping(value = "/iso/pain001", consumes = "application/xml")
  public Payment pain(
      Authentication a, @RequestHeader("Idempotency-Key") String key, @RequestBody String xml) {
    return iso.importPain(actor(a), key, xml);
  }

  @GetMapping(value = "/payments/{id}/iso/{type}", produces = "application/xml")
  public String export(Authentication a, @PathVariable String id, @PathVariable String type) {
    return iso.export(actor(a), id, type);
  }

  @GetMapping(value = "/accounts/{id}/camt053", produces = "application/xml")
  public String camt(Authentication a, @PathVariable String id) {
    return iso.statement(actor(a), id);
  }

  @PostMapping(value = "/iso/mt103", consumes = "text/plain")
  public Object mt(Authentication a, @RequestBody String text) {
    return iso.previewMt(text);
  }
}
