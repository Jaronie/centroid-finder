package io.github.jaronie.centroidServer.exception;

import org.springframework.http.HttpStatus;

/**
 * An error we are deliberately reporting to the client, carrying both the HTTP
 * status and the exact message the API spec requires.
 *
 * <p>In Express you would write `res.status(400).json({ error: "..." })` right
 * where the problem is found. Java cannot do that from inside a service that
 * has no access to the response, so instead we throw this, and
 * ApiExceptionHandler (file 6) converts it into that same response centrally.
 * Throwing also aborts the rest of the method, the way an early
 * `return res.status(...)` does in Express.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public ApiException(HttpStatus status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }

    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, message);
    }

    public static ApiException serverError(String message) {
        return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, message);
    }

    public static ApiException serverError(String message, Throwable cause) {
        return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, message, cause);
    }
}
