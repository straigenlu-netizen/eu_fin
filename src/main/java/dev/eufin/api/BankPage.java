package dev.eufin.api;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class BankPage {
  @GetMapping({"/bank", "/bank/"})
  public String page() {
    return "forward:/bank/index.html";
  }
}
