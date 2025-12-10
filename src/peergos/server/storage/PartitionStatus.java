package peergos.server.storage;

public interface PartitionStatus {

    boolean isDone();

    void complete();

    PartitionStatus DONE = new PartitionStatus() {
        @Override
        public boolean isDone() {
            return true;
        }

        @Override
        public void complete() {

        }
    };
}
