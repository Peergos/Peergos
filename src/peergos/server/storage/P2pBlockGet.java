package peergos.server.storage;

import peergos.shared.crypto.hash.PublicKeyHash;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.storage.auth.BatWithId;

import java.util.List;
import java.util.Optional;

public interface P2pBlockGet {

    List<BlockMetadata> bulkGet(List<Multihash> peerid, PublicKeyHash owner, List<Cid> blocks, Optional<BatWithId> mirrorBat);
}
