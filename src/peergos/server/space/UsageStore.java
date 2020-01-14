package peergos.server.space;

public interface UsageStore extends WriterUsageStore, UserUsageStore {

    void initialized();

    void close();
}
