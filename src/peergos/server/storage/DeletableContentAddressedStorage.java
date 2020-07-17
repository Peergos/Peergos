package peergos.server.storage;

import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.storage.*;

import java.util.stream.*;

public interface DeletableContentAddressedStorage extends ContentAddressedStorage {

    Stream<Multihash> getAllFiles();

    void delete(Multihash hash);
}
