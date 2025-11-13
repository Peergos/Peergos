package peergos.server.space;

import peergos.shared.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.util.*;

import java.util.*;

public interface WriterUsageStore {

    void addWriter(String owner, PublicKeyHash writer);

    Set<PublicKeyHash> getAllWriters();

    Set<PublicKeyHash> getAllWriters(PublicKeyHash owner);

    Set<PublicKeyHash> getAllWriters(String owner);

    WriterUsage getUsage(PublicKeyHash writer);

    PublicKeyHash getOwnerKey(PublicKeyHash writer);

    PublicKeyHash getOwnerKey(String username);

    void updateWriterUsage(PublicKeyHash writer,
                           MaybeMultihash target,
                           Set<PublicKeyHash> removedOwnedKeys,
                           Set<PublicKeyHash> addedOwnedKeys,
                           long retainedStorage);

    // return current usage root, and username
    List<Triple<Multihash, String, PublicKeyHash>> getAllTargets();
}
