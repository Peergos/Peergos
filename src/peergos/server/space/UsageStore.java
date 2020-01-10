package peergos.server.space;

import peergos.shared.*;
import peergos.shared.crypto.hash.*;

import java.util.*;

public interface UsageStore {

    void addUserIfAbsent(String username);

    UserUsage getUsage(String owner);

    void confirmUsage(String owner, PublicKeyHash writer, long usageDelta);

    void addWriter(String owner, PublicKeyHash writer);

    Set<PublicKeyHash> getAllWriters();

    WriterUsage getUsage(PublicKeyHash writer);

    void updateWriterUsage(PublicKeyHash writer, MaybeMultihash target, Set<PublicKeyHash> ownedKeys, long retainedStorage);

    void clearPendingUsage(String username, PublicKeyHash writer);

    void addPendingUsage(String username, PublicKeyHash writer, int size);

    void setWriters(String username, PublicKeyHash writer, Set<PublicKeyHash> ownedWriters);

    void setErrored(boolean errored, String username, PublicKeyHash writer);

    void initialized();

    void close();
}
