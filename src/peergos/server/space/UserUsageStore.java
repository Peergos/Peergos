package peergos.server.space;

import peergos.shared.crypto.hash.*;

public interface UserUsageStore extends WriterUsageStore {

    void addUserIfAbsent(String username);

    UserUsage getUsage(String username);

    void confirmUsage(String username, PublicKeyHash writer, long usageDelta, boolean errored);

    void addPendingUsage(String username, PublicKeyHash writer, int size);

}
