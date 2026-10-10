package dev.eufin;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.eufin.api.BankController;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.*;

class ApiContractTest {
  @Test
  void everyControllerRouteIsDeclaredAndEveryContractRouteExists() throws Exception {
    var spec = new ObjectMapper().readTree(Files.readString(Path.of("api/openapi.json")));
    Set<String> actual = new HashSet<>(), declared = new HashSet<>();
    for (Class<?> controller :
        new Class<?>[] {BankController.class, dev.eufin.customers.CustomerController.class})
      for (var method : controller.getDeclaredMethods())
        for (var annotation : method.getAnnotations()) {
          String verb = null;
          String[] routes = null;
          if (annotation instanceof GetMapping a) {
            verb = "get";
            routes = a.value();
          }
          if (annotation instanceof PostMapping a) {
            verb = "post";
            routes = a.value();
          }
          if (annotation instanceof DeleteMapping a) {
            verb = "delete";
            routes = a.value();
          }
          if (annotation instanceof PutMapping a) {
            verb = "put";
            routes = a.value();
          }
          if (annotation instanceof PatchMapping a) {
            verb = "patch";
            routes = a.value();
          }
          if (verb != null) for (String route : routes) actual.add(verb + " /api/v1" + route);
        }
    spec.get("paths")
        .fields()
        .forEachRemaining(
            path ->
                path.getValue()
                    .fieldNames()
                    .forEachRemaining(verb -> declared.add(verb + " " + path.getKey())));
    assertThat(actual).containsExactlyInAnyOrderElementsOf(declared);
    assertThat(spec.get("components").get("securitySchemes").has("bearerAuth")).isTrue();
  }
}
