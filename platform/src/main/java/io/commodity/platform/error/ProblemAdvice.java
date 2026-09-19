package io.commodity.platform.error;

import java.net.URI;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Renders {@link DomainException} as application/problem+json with the failing numbers included.
 * WHY only DomainException is mapped: a blanket handler for IllegalArgumentException would turn programming and
 * configuration errors into "400 bad request" and hide them. Client mistakes must be raised explicitly as
 * DomainException (see {@link Parse}); anything else is a genuine 500.
 * Registered by component scan of io.commodity.platform.error in each service that exposes REST.
 */
@RestControllerAdvice
public class ProblemAdvice {

    private static final String BASE = "https://commodity.demo/errors/";

    @ExceptionHandler(DomainException.class)
    ResponseEntity<ProblemDetail> handle(DomainException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(e.status()), e.getMessage());
        pd.setType(URI.create(BASE + e.type()));
        pd.setTitle(e.getMessage());
        e.details().forEach(pd::setProperty);
        return ResponseEntity.status(e.status()).contentType(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON).body(pd);
    }
}
