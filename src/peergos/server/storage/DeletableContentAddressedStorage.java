package peergos.server.storage;

import peergos.shared.io.ipfs.api.*;
import peergos.shared.io.ipfs.cid.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.storage.*;
import peergos.shared.user.*;

import java.util.*;
import java.util.stream.*;

public interface DeletableContentAddressedStorage extends ContentAddressedStorage {

    Stream<Multihash> getAllFiles();

    void delete(Multihash hash);

    List<Multihash> getOpenTransactionBlocks();

    class HTTP extends ContentAddressedStorage.HTTP implements DeletableContentAddressedStorage {

        private final HttpPoster poster;

        public HTTP(HttpPoster poster, boolean isPeergosServer) {
            super(poster, isPeergosServer);
            this.poster = poster;
        }

        @Override
        public Stream<Multihash> getAllFiles() {
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
