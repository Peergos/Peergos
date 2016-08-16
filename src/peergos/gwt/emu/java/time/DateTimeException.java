package java.time;

public class DateTimeException extends RuntimeException {

    public DateTimeException(String message) {
        super(message);
    }

    public DateTimeException(String message, Throwable cause) {
        super(message, cause);
    }
}
