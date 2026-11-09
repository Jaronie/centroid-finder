package io.github.jaronie.centroidServer.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import io.github.jaronie.centroidServer.exception.ApiException;
import io.github.jaronie.centroidServer.web.dto.ErrorResponse;

/**
 * Turns exceptions into the exact error bodies the spec requires.
 *
 * <p>This is Express's error-handling middleware -- the four-argument
 * (err, req, res, next) form that sits at the end and shapes whatever
 * reached it.
 *
 * <p>It is not optional. Without it, an uncaught exception produces Spring's
 * own default body, which looks nothing like the required {"error": "..."}.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** Our own deliberate errors, which already carry both status and message. */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApiException(ApiException e) {
        if (e.getStatus().is5xxServerError()) {
            log.error("{} -> {}", e.getStatus(), e.getMessage(), e);
        } else {
            log.warn("{} -> {}", e.getStatus(), e.getMessage());
        }
        return ResponseEntity.status(e.getStatus()).body(new ErrorResponse(e.getMessage()));
    }

    /**
     * The safety net for everything else.
     *
     * <p>Spring's own HTTP failures land here too: an unmapped URL, a missing
     * static file, a wrong HTTP method. Those are not bugs and already know
     * their correct status code, so they are detected and passed through
     * rather than flattened into a 500.
     *
     * <p>They are recognised by Spring's ErrorResponse interface. Note the name
     * clash with our own ErrorResponse record, which is why Spring's is written
     * out in full here.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleAnythingElse(Exception e) {
        if (e instanceof org.springframework.web.ErrorResponse spring) {
            HttpStatus status = HttpStatus.valueOf(spring.getStatusCode().value());
            log.warn("{} -> {}", status, e.getMessage());
            return ResponseEntity.status(status).body(new ErrorResponse(status.getReasonPhrase()));
        }

        log.error("Unhandled exception", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("Unexpected server error"));
    }
}
