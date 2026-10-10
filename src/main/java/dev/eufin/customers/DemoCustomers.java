package dev.eufin.customers;

import static dev.eufin.customers.CustomerModels.*;
import static dev.eufin.domain.Models.*;

import dev.eufin.domain.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "bank.demo-data-enabled", havingValue = "true")
public class DemoCustomers implements ApplicationRunner {
  private final CustomerService customers;
  private final BankService bank;

  public DemoCustomers(CustomerService customers, BankService bank) {
    this.customers = customers;
    this.bank = bank;
  }

  public void run(ApplicationArguments args) {
    String[] names = {"Alice Demo", "Bob Example", "Carla Sample", "Daniel Demo", "Eva Example"};
    String[] users = {"alice", "bob", "carla", "daniel", "eva"};
    String[] ibans = {
      "DE89370400440532013000",
      "NL91ABNA0417164300",
      "FR1420041010050500013M02606",
      "BE68539007547034",
      "PL61109010140000071219812874"
    };
    for (int i = 0; i < 5; i++) {
      String subject = String.format("00000000-0000-0000-0000-%012d", i + 1);
      try {
        customers.self(subject);
        continue;
      } catch (BankError e) {
        if (e.status != 404) throw e;
      }
      customers.register(
          subject,
          new Register(
              new Identity(names[i], LocalDate.of(1990 + i, 1, 1), "PL"),
              new Contact(
                  users[i] + "@example.test",
                  "+4812345678" + i,
                  "Fictional Street " + (i + 1),
                  "Warsaw",
                  "PL")));
      customers.verify("demo-seeder", subject, "APPROVE");
      Account a = bank.open(new Actor(subject, false), new OpenAccount(ibans[i], names[i]));
      bank.fund(new Actor("demo-seeder", true), a.id(), "demo-opening", new BigDecimal("1000.00"));
    }
  }
}
