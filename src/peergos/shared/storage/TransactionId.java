package peergos.shared.storage;

public final class TransactionId {
    public final String id;

    public TransactionId(String id) {
        this.id = id;
    }

    @Override
    public String toString() {
        return id;
    }
}
