package com.chubb.claims.common;

import com.chubb.claims.claim.StaleClaimVersionException;
import com.chubb.claims.workflow.InformationRequestNotFoundException;
import jakarta.validation.ConstraintViolationException;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import org.slf4j.*;
import org.springframework.dao.*;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.*;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
  private static final String INTERNAL_MESSAGE =
      "An internal error occurred while processing the request.";

  public record ApiError(
      Instant timestamp,
      int status,
      String code,
      String message,
      String correlationId,
      Map<String, String> fieldErrors) {}

  private ResponseEntity<ApiError> error(
      HttpStatus status, String code, String message, Map<String, String> fields) {
    if (status.is4xxClientError()) {
      log.warn(
          "Request rejected code={} status={} correlationId={}",
          code,
          status.value(),
          MDC.get("correlationId"));
    }
    return ResponseEntity.status(status)
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            new ApiError(
                Instant.now(), status.value(), code, message, MDC.get("correlationId"), fields));
  }

  @ExceptionHandler(InformationRequestNotFoundException.class)
  ResponseEntity<ApiError> informationMissing(InformationRequestNotFoundException e) {
    return error(HttpStatus.NOT_FOUND, "INFORMATION_REQUEST_NOT_FOUND", e.getMessage(), Map.of());
  }

  @ExceptionHandler(NotFoundException.class)
  ResponseEntity<ApiError> notFound(NotFoundException e) {
    return error(HttpStatus.NOT_FOUND, "NOT_FOUND", e.getMessage(), Map.of());
  }

  @ExceptionHandler({
    StaleClaimVersionException.class,
    OptimisticLockingFailureException.class,
    jakarta.persistence.OptimisticLockException.class
  })
  ResponseEntity<ApiError> stale(Exception e) {
    return error(
        HttpStatus.CONFLICT,
        "STALE_CLAIM_VERSION",
        "The claim has changed because it was modified by another request. Refresh and retry.",
        Map.of());
  }

  @ExceptionHandler(DomainException.class)
  ResponseEntity<ApiError> conflict(DomainException e) {
    return error(HttpStatus.CONFLICT, "CLAIM_CONFLICT", e.getMessage(), Map.of());
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  ResponseEntity<ApiError> integrity(DataIntegrityViolationException e) {
    if (e instanceof DuplicateKeyException || hasUniqueViolation(e)) {
      return error(
          HttpStatus.CONFLICT,
          "DUPLICATE_RESOURCE",
          "A conflicting record already exists. Refresh and retry.",
          Map.of());
    }
    return unexpected(e);
  }

  private boolean hasUniqueViolation(Throwable error) {
    Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
    for (Throwable cause = error; cause != null && visited.add(cause); cause = cause.getCause()) {
      if (cause instanceof SQLException sql && "23505".equals(sql.getSQLState())) return true;
    }
    return false;
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ApiError> validation(MethodArgumentNotValidException e) {
    Map<String, String> fields = new TreeMap<>();
    e.getBindingResult()
        .getFieldErrors()
        .forEach(f -> fields.putIfAbsent(f.getField(), f.getDefaultMessage()));
    return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Request validation failed", fields);
  }

  @ExceptionHandler(HandlerMethodValidationException.class)
  ResponseEntity<ApiError> methodValidation(HandlerMethodValidationException e) {
    if (e.isForReturnValue()) return unexpected(e);
    Map<String, String> fields = new TreeMap<>();
    e.getParameterValidationResults()
        .forEach(
            result -> {
              String name = result.getMethodParameter().getParameterName();
              result
                  .getResolvableErrors()
                  .forEach(
                      problem ->
                          fields.putIfAbsent(
                              name == null ? "parameter" : name, problem.getDefaultMessage()));
            });
    return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Request validation failed", fields);
  }

  @ExceptionHandler(ConstraintViolationException.class)
  ResponseEntity<ApiError> constraintValidation(ConstraintViolationException e) {
    Map<String, String> fields = new TreeMap<>();
    e.getConstraintViolations()
        .forEach(v -> fields.putIfAbsent(v.getPropertyPath().toString(), v.getMessage()));
    return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Request validation failed", fields);
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<ApiError> malformed(HttpMessageNotReadableException e) {
    return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Malformed request body", Map.of());
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  ResponseEntity<ApiError> parameter(MethodArgumentTypeMismatchException e) {
    return error(
        HttpStatus.BAD_REQUEST,
        "INVALID_REQUEST",
        "Invalid request parameter",
        Map.of(e.getName(), "Invalid format or unsupported value"));
  }

  @ExceptionHandler(MissingServletRequestParameterException.class)
  ResponseEntity<ApiError> missingParameter(MissingServletRequestParameterException e) {
    return error(
        HttpStatus.BAD_REQUEST,
        "INVALID_REQUEST",
        "Required request parameter is missing",
        Map.of(e.getParameterName(), "Required parameter is missing"));
  }

  @ExceptionHandler(NoResourceFoundException.class)
  ResponseEntity<ApiError> resource(NoResourceFoundException e) {
    return error(HttpStatus.NOT_FOUND, "NOT_FOUND", "Resource not found", Map.of());
  }

  @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
  ResponseEntity<ApiError> method(
      org.springframework.web.HttpRequestMethodNotSupportedException e) {
    var response =
        error(
            HttpStatus.METHOD_NOT_ALLOWED,
            "METHOD_NOT_ALLOWED",
            "HTTP method not supported",
            Map.of());
    var headers = new HttpHeaders();
    headers.putAll(response.getHeaders());
    if (e.getSupportedHttpMethods() != null) headers.setAllow(e.getSupportedHttpMethods());
    return new ResponseEntity<>(response.getBody(), headers, response.getStatusCode());
  }

  @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
  ResponseEntity<ApiError> media(org.springframework.web.HttpMediaTypeNotSupportedException e) {
    return error(
        HttpStatus.UNSUPPORTED_MEDIA_TYPE,
        "UNSUPPORTED_MEDIA_TYPE",
        "Content type not supported",
        Map.of());
  }

  @ExceptionHandler(org.springframework.web.HttpMediaTypeNotAcceptableException.class)
  ResponseEntity<ApiError> acceptable(
      org.springframework.web.HttpMediaTypeNotAcceptableException e) {
    return error(
        HttpStatus.NOT_ACCEPTABLE,
        "NOT_ACCEPTABLE",
        "Requested response format not supported",
        Map.of());
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ApiError> unexpected(Exception e) {
    // Preserve technical stack frames/causes while excluding messages that may contain SQL values
    // or PII.
    log.error(
        "Unexpected request failure correlationId={}",
        MDC.get("correlationId"),
        safeTrace(e, Collections.newSetFromMap(new IdentityHashMap<>())));
    return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", INTERNAL_MESSAGE, Map.of());
  }

  private Throwable safeTrace(Throwable source, Set<Throwable> visited) {
    Throwable trace = new Throwable(source.getClass().getName() + " (message redacted)");
    if (!visited.add(source)) return trace;
    trace.setStackTrace(source.getStackTrace());
    if (source.getCause() != null && !visited.contains(source.getCause())) {
      trace.initCause(safeTrace(source.getCause(), visited));
    }
    for (Throwable suppressed : source.getSuppressed()) {
      if (!visited.contains(suppressed)) trace.addSuppressed(safeTrace(suppressed, visited));
    }
    return trace;
  }
}
