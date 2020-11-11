package peergos.server.storage;

import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.api.*;
import peergos.shared.io.ipfs.cid.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.storage.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

/** This interface is only used locally on a server and never exposed.
 *  These methods allow garbage collection and local mirroring to be implemented.
 *
 */
public interface DeletableContentAddressedStorage extends ContentAddressedStorage {

    Stream<Multihash> getAllBlockHashes();

    void delete(Multihash hash);

    List<Multihash> getOpenTransactionBlocks();

    /** Ensure that local copies of all blocks in merkle tree referenced are present locally
     *
     * @param owner
     * @param existing
     * @param updated
     * @return
     */
    default CompletableFuture<List<Multihash>> mirror(PublicKeyHash owner,
                                                      Optional<Multihash> existing,
                                                      Optional<Multihash> updated,
                                                      TransactionId tid) {
        if (existing.isEmpty()) {
            if (updated.isEmpty())
                return Futures.of(Collections.emptyList());
            return recursivePin(owner, updated.get());
        }
        if (updated.isEmpty())
            return recursiveUnpin(owner, existing.get());
        return pinUpdate(owner, existing.get(), updated.get());
    }

    class HTTP extends ContentAddressedStorage.HTTP implements DeletableContentAddressedStorage {

        private final HttpPoster poster;

        public HTTP(HttpPoster poster, boolean isPeergosServer) {
            super(poster, isPeergosServer);
            this.poster = poster;
        }

        @Override
        public Stream<Multihash> getAllBlockHashes() {
            String jsonStream = new String(poster.get(apiPrefix + REFS_LOCAL).join());
            return JSONParser.parseStream(jsonStream).stream()
                    .map(m -> (String) (((Map) m).get("Ref")))
                    .map(Cid::decode);
        }

        @Override
        public void delete(Multihash hash) {
            poster.get(apiPrefix + BLOCK_RM + "?stream-channels=true&arg=" + hash.toString()).join();
        }

        @Override
        public List<Multihash> getOpenTransactionBlocks() {
            throw new IllegalStateException("Unimplemented!");
        }
    }
}
