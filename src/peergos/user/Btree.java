package peergos.user;

import org.ipfs.api.Multihash;
import peergos.crypto.UserPublicKey;
import peergos.merklebtree.MaybeMultihash;
import peergos.merklebtree.PairMultihash;
import peergos.util.*;

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

            byte[] res = poster.post("btree/put", bout.toByteArray());
            DataInputStream din = new DataInputStream(new ByteArrayInputStream(res));
            int success = din.readInt();
            if (success != 1)
                throw new IOException("Couldn't add value to BTree!");
            byte[] pair = Serialize.deserializeByteArray(din, 512);
            DataSource source = new DataSource(pair);
            return new PairMultihash(MaybeMultihash.deserialize(source), MaybeMultihash.deserialize(source));
        }

        @Override
        public MaybeMultihash get(UserPublicKey sharingKey, byte[] mapKey) throws IOException {
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            DataOutputStream dout = new DataOutputStream(bout);

            Serialize.serialize(sharingKey.toUserPublicKey().serialize(), dout);
            Serialize.serialize(mapKey, dout);
            dout.flush();

            byte[] res = poster.post("btree/get", bout.toByteArray());
            DataInputStream din = new DataInputStream(new ByteArrayInputStream(res));
            int success = din.readInt();
            if (success != 1)
                throw new IOException("Couldn't get value from BTree!");
            byte[] raw = Serialize.deserializeByteArray(din, 256);
            if (raw.length == 0)
                return MaybeMultihash.EMPTY();
            byte[] multihash = new DataSource(raw).readArray();
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

            byte[] res = poster.post("btree/delete", bout.toByteArray());
            DataInputStream din = new DataInputStream(new ByteArrayInputStream(res));
            int success = din.readInt();
            if (success != 1)
                throw new IOException("Couldn't add data to DHT!");
            byte[] raw = Serialize.deserializeByteArray(din, 512);
            DataSource source = new DataSource(raw);
            // read header for byte array
            source.readInt();
            return new PairMultihash(MaybeMultihash.deserialize(source), MaybeMultihash.deserialize(source));
        }
    }
}
