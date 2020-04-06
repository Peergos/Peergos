package peergos.server.space;

import peergos.shared.*;
import peergos.shared.crypto.hash.*;

import java.util.*;

public interface WriterUsageStore {

    void addWriter(String owner, PublicKeyHash writer);

    Set<PublicKeyHash> getAllWriters();

    WriterUsage getUsage(PublicKeyHash writer);

    void updateWriterUsage(PublicKeyHash writer, MaybeMultihash target, Set<PublicKeyHash> ownedKeys, long retainedStorage);
}
