package peergos.shared.user;

import peergos.shared.crypto.UserPublicKey;
import peergos.shared.ipfs.api.Multihash;
import peergos.shared.merklebtree.MaybeMultihash;
import peergos.shared.merklebtree.PairMultihash;
import peergos.shared.util.*;

import java.io.*;
import java.util.concurrent.*;

public interface Btree {
    /**
     *
     * @param sharingKey
     * @param mapKey
     * @param value
     * @return the new root hash of the btree
     * @throws IOException
     */
    CompletableFuture<PairMultihash> put(UserPublicKey sharingKey, byte[] mapKey, Multihash value);

    /**
     *
     * @param sharingKey
     * @param mapKey
     * @return  the value stored under mapKey for sharingKey
     * @throws IOException
     */
    CompletableFuture<MaybeMultihash> get(UserPublicKey sharingKey, byte[] mapKey);

    /**
     *
     * @param sharingKey
     * @param mapKey
     * @return  hash(sharingKey.metadata) | the new root hash of the btree
     * @throws IOException
     */
    CompletableFuture<PairMultihash> remove(UserPublicKey sharingKey, byte[] mapKey);

    class HTTP implements Btree {

        private final HttpPoster poster;

        public HTTP(HttpPoster poster) {
            this.poster = poster;
        }

        @Override
        public CompletableFuture<PairMultihash> put(UserPublicKey sharingKey, byte[] mapKey, Multihash value) {
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            DataOutputStream dout = new DataOutputStream(bout);

            try {
                Serialize.serialize(sharingKey.toUserPublicKey().serialize(), dout);
                Serialize.serialize(mapKey, dout);
                Serialize.serialize(value.toBytes(), dout);
                dout.flush();

                return poster.postUnzip("btree/put", bout.toByteArray()).thenApply(res -> {
                    DataSource source = new DataSource(res);
                    try {
                        int success = source.readInt();
                        if (success != 1)
                            throw new IOException("Couldn't add value to BTree!");
                        return new PairMultihash(MaybeMultihash.deserialize(source), MaybeMultihash.deserialize(source));
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public CompletableFuture<MaybeMultihash> get(UserPublicKey sharingKey, byte[] mapKey) {
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            DataOutputStream dout = new DataOutputStream(bout);

            try {
                Serialize.serialize(sharingKey.toUserPublicKey().serialize(), dout);
                Serialize.serialize(mapKey, dout);
                dout.flush();

                return poster.postUnzip("btree/get", bout.toByteArray()).thenApply(res -> {
                    DataSource source = new DataSource(res);
                    try {
                        int success = source.readInt();
                        if (success != 1)
                            throw new IOException("Couldn't get value from BTree!");
                        byte[] multihash = source.readArray();
                        if (multihash.length == 0)
                            return MaybeMultihash.EMPTY();
                        return new MaybeMultihash(new Multihash(multihash));
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public CompletableFuture<PairMultihash> remove(UserPublicKey sharingKey, byte[] mapKey) {
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            DataOutputStream dout = new DataOutputStream(bout);

            try {
                Serialize.serialize(sharingKey.toUserPublicKey().serialize(), dout);
                Serialize.serialize(mapKey, dout);
                dout.flush();

                return poster.postUnzip("btree/delete", bout.toByteArray()).thenApply(res -> {
                    DataSource source = new DataSource(res);
                    try {
                        int success = source.readInt();
                        if (success != 1)
                            throw new IOException("Couldn't add data to DHT!");
                        return new PairMultihash(MaybeMultihash.deserialize(source), MaybeMultihash.deserialize(source));
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
