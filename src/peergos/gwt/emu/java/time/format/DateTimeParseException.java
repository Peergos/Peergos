package java.time.format;


public class DateTimeParseException extends java.time.DateTimeException {

    public DateTimeParseException(String message) {
        super(message);
    }

    public DateTimeParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
