package peergos.shared.crypto;

public class InvalidCipherTextException extends IllegalStateException {
    public InvalidCipherTextException() {
    }

    public InvalidCipherTextException(String msg) {
        super(msg);
    }
}
