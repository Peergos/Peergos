package peergos.server.storage;

import peergos.shared.crypto.hash.PublicKeyHash;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.storage.auth.BatWithId;

import java.util.List;
import java.util.Optional;

public interface P2pBlockGet {

    BlockMetadata get(List<Multihash> peerid, PublicKeyHash owner, Cid block, Optional<BatWithId> mirrorBat);
}
