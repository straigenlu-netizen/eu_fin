package dev.eufin.api;

import dev.eufin.domain.BankError;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class Errors {
  @ExceptionHandler(BankError.class)
  ResponseEntity<ProblemDetail> bank(BankError e) {
    return problem(e.status, e.code);
  }

  @ExceptionHandler({
    MethodArgumentNotValidException.class,
    org.springframework.http.converter.HttpMessageNotReadableException.class,
    org.springframework.web.bind.MissingRequestHeaderException.class,
    jakarta.validation.ConstraintViolationException.class
  })
  ResponseEntity<ProblemDetail> validation(Exception e) {
    return problem(400, "INVALID_REQUEST");
  }

  @ExceptionHandler(io.github.resilience4j.circuitbreaker.CallNotPermittedException.class)
  ResponseEntity<ProblemDetail> unavailable(Exception e) {
    return problem(503, "CLEARING_UNAVAILABLE");
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  ResponseEntity<ProblemDetail> conflict(Exception e) {
    return problem(409, "DATA_CONFLICT");
  }

  private ResponseEntity<ProblemDetail> problem(int status, String code) {
    var p = ProblemDetail.forStatus(status);
    p.setTitle(code);
    return ResponseEntity.status(status).body(p);
  }
}
