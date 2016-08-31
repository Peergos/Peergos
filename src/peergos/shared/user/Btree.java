package peergos.shared.user;

import peergos.shared.crypto.UserPublicKey;
import peergos.shared.ipfs.api.Multihash;
import peergos.shared.merklebtree.MaybeMultihash;
import peergos.shared.merklebtree.PairMultihash;
import peergos.shared.util.*;

import java.io.*;

public interface Btree {
    /**
     *
     * @param sharingKey
     * @param mapKey
     * @param value
     * @return the new root hash of the btree
     * @throws IOException
     */
    PairMultihash put(UserPublicKey sharingKey,
                      byte[] mapKey,
                      Multihash value) throws IOException;

    /**
     *
     * @param sharingKey
     * @param mapKey
     * @return  the value stored under mapKey for sharingKey
     * @throws IOException
     */
    MaybeMultihash get(UserPublicKey sharingKey,
                                 byte[] mapKey) throws IOException;

    /**
     *
     * @param sharingKey
     * @param mapKey
     * @return  hash(sharingKey.metadata) | the new root hash of the btree
     * @throws IOException
     */
    PairMultihash remove(UserPublicKey sharingKey,
                            byte[] mapKey) throws IOException;

    class HTTP implements Btree {

        private final HttpPoster poster;

        public HTTP(HttpPoster poster) {
            this.poster = poster;
        }

        @Override
        public PairMultihash put(UserPublicKey sharingKey, byte[] mapKey, Multihash value) throws IOException {
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            DataOutputStream dout = new DataOutputStream(bout);

            Serialize.serialize(sharingKey.toUserPublicKey().serialize(), dout);
            Serialize.serialize(mapKey, dout);
            Serialize.serialize(value.toBytes(), dout);
            dout.flush();

            byte[] res = poster.postUnzip("btree/put", bout.toByteArray());
            DataSource source = new DataSource(res);
            int success = source.readInt();
            if (success != 1)
                throw new IOException("Couldn't add value to BTree!");
            return new PairMultihash(MaybeMultihash.deserialize(source), MaybeMultihash.deserialize(source));
        }

        @Override
        public MaybeMultihash get(UserPublicKey sharingKey, byte[] mapKey) throws IOException {
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            DataOutputStream dout = new DataOutputStream(bout);

            Serialize.serialize(sharingKey.toUserPublicKey().serialize(), dout);
            Serialize.serialize(mapKey, dout);
            dout.flush();

            byte[] res = poster.postUnzip("btree/get", bout.toByteArray());
            DataSource source = new DataSource(res);
            int success = source.readInt();
            if (success != 1)
                throw new IOException("Couldn't get value from BTree!");
            byte[] multihash = source.readArray();
            if (multihash.length == 0)
                return MaybeMultihash.EMPTY();
            return new MaybeMultihash(new Multihash(multihash));
        }

        @Override
        public PairMultihash remove(UserPublicKey sharingKey, byte[] mapKey) throws IOException {
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            DataOutputStream dout = new DataOutputStream(bout);

            Serialize.serialize(sharingKey.toUserPublicKey().serialize(), dout);
            Serialize.serialize(mapKey, dout);
            dout.flush();

            byte[] res = poster.postUnzip("btree/delete", bout.toByteArray());
            DataSource source = new DataSource(res);
            int success = source.readInt();
            if (success != 1)
                throw new IOException("Couldn't add data to DHT!");
            return new PairMultihash(MaybeMultihash.deserialize(source), MaybeMultihash.deserialize(source));
        }
    }
}
