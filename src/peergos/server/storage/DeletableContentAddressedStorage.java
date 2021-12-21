package peergos.server.storage;

import peergos.shared.cbor.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.api.*;
import peergos.shared.io.ipfs.cid.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.storage.*;
import peergos.shared.storage.auth.*;
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

    default void bulkDelete(List<Multihash> blocks) {
        for (Multihash block : blocks) {
            delete(block);
        }
    }

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
                                                      Optional<BatWithId> mirrorBat,
                                                      Cid ourNodeId,
                                                      TransactionId tid,
                                                      Hasher hasher) {
        if (updated.isEmpty())
            return Futures.of(Collections.emptyList());
        Multihash newRoot = updated.get();
        if (existing.equals(updated))
            return Futures.of(Collections.singletonList(newRoot));
        boolean isRaw = (newRoot instanceof Cid) && ((Cid) newRoot).codec == Cid.Codec.Raw;

        Optional<byte[]> newVal = isRaw ?
                getRaw(newRoot, mirrorBat, ourNodeId, hasher).join() :
                get(newRoot, mirrorBat, ourNodeId, hasher).join().map(Cborable::serialize);
        if (newVal.isEmpty())
            throw new IllegalStateException("Couldn't retrieve block: " + newRoot);

        byte[] newBlock = newVal.get();

        if (isRaw)
            return Futures.of(Collections.singletonList(newRoot));

        List<Multihash> newLinks = CborObject.fromByteArray(newBlock).links();
        List<Multihash> existingLinks = existing.map(h -> get(h, mirrorBat, ourNodeId, hasher).join())
                .flatMap(copt -> copt.map(CborObject::links))
                .orElse(Collections.emptyList());

        for (int i=0; i < newLinks.size(); i++) {
            Optional<Multihash> existingLink = i < existingLinks.size() ?
                    Optional.of(existingLinks.get(i)) :
                    Optional.empty();
            Optional<Multihash> updatedLink = Optional.of(newLinks.get(i));
            mirror(owner, existingLink, updatedLink, mirrorBat, ourNodeId, tid, hasher).join();
        }
        return Futures.of(Collections.singletonList(newRoot));
    }

    class HTTP extends ContentAddressedStorage.HTTP implements DeletableContentAddressedStorage {

        private final HttpPoster poster;

        public HTTP(HttpPoster poster, boolean isPeergosServer, Hasher hasher) {
            super(poster, isPeergosServer, hasher);
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
