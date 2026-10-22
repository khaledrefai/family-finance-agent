package ae.familyfinance.core.common;

import org.springframework.http.HttpStatus;

/** Thrown for expected business errors; turned into a JSON error by {@link ApiExceptionHandler}. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
