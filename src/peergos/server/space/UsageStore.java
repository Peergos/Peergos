package peergos.server.space;

public interface UsageStore extends WriterUsageStore, UserUsageStore, WriterQuotaStore {

    void initialized();

    void close();
}
