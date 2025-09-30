package peergos.server.storage;

import peergos.shared.crypto.hash.PublicKeyHash;
import peergos.shared.io.ipfs.Cid;

import java.util.List;

public interface NewBlocksProcessor {

    void process(PublicKeyHash writer, List<Cid> blocks, int totalSize);
}
