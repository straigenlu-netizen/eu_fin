package dev.eufin.customers;

import static dev.eufin.customers.CustomerModels.*;

import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class CustomerController {
  private final CustomerService service;

  public CustomerController(CustomerService service) {
    this.service = service;
  }

  private String sub(Authentication a) {
    return ((Jwt) a.getPrincipal()).getSubject();
  }

  @PostMapping("/customers/me")
  public Object register(Authentication a, @Valid @RequestBody Register request) {
    return service.register(sub(a), request);
  }

  @GetMapping("/customers/me")
  public Object me(Authentication a) {
    return service.self(sub(a));
  }

  @PutMapping("/customers/me/contact")
  public Object contact(Authentication a, @Valid @RequestBody UpdateContact request) {
    return service.updateContact(sub(a), request);
  }

  @PostMapping("/customers/me/identity-changes")
  public Object identity(Authentication a, @Valid @RequestBody UpdateIdentity request) {
    return Map.of("id", service.requestIdentity(sub(a), request));
  }

  @GetMapping("/staff/customers")
  public Object customers(Authentication a, @RequestParam(defaultValue = "0") int page) {
    return service.staffList(sub(a), page);
  }

  @PatchMapping("/staff/customers/{subject}")
  public Object metadata(
      Authentication a, @PathVariable String subject, @Valid @RequestBody StaffUpdate request) {
    return service.staffUpdate(sub(a), subject, request);
  }

  @PostMapping("/staff/customers/{subject}/kyc")
  public void kyc(
      Authentication a, @PathVariable String subject, @Valid @RequestBody Review request) {
    service.verify(sub(a), subject, request.decision());
  }

  @GetMapping("/staff/identity-changes")
  public Object changes(Authentication a) {
    return service.changes(sub(a));
  }

  @PostMapping("/staff/identity-changes/{id}/review")
  public void review(
      Authentication a, @PathVariable String id, @Valid @RequestBody Review request) {
    service.reviewIdentity(sub(a), id, request.decision());
  }

  @GetMapping("/staff/audit")
  public Object audits(Authentication a) {
    return service.audits(sub(a));
  }
}
