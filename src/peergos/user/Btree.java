package peergos.user;

import org.ipfs.api.Multihash;
import peergos.crypto.UserPublicKey;
import peergos.server.merklebtree.MaybeMultihash;
import peergos.server.merklebtree.PairMultihash;
import peergos.util.*;

import java.io.*;
import java.net.*;

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

        private final URL dht;

        public HTTP(URL dht) {
            this.dht = dht;
        }

        public URL buildURL(String method) throws IOException {
            try {
                return new URL(dht, method);
            } catch (MalformedURLException mexican) {
                throw new IOException(mexican);
            }
        }

        @Override
        public PairMultihash put(UserPublicKey sharingKey, byte[] mapKey, Multihash value) throws IOException {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) buildURL("btree/put").openConnection();
                conn.setDoInput(true);
                conn.setDoOutput(true);
                DataOutputStream dout = new DataOutputStream(conn.getOutputStream());

                Serialize.serialize(sharingKey.toUserPublicKey().serialize(), dout);
                Serialize.serialize(mapKey, dout);
                Serialize.serialize(value.toBytes(), dout);
                dout.flush();

                DataInputStream din = new DataInputStream(conn.getInputStream());
                int success = din.readInt();
                if (success != 1)
                    throw new IOException("Couldn't add value to BTree!");
                byte[] res = Serialize.deserializeByteArray(din, 512);
                DataSource source = new DataSource(res);
                return new PairMultihash(MaybeMultihash.deserialize(source), MaybeMultihash.deserialize(source));
            } finally {
                if (conn != null)
                    conn.disconnect();
            }
        }

        @Override
        public MaybeMultihash get(UserPublicKey sharingKey, byte[] mapKey) throws IOException {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) buildURL("btree/get").openConnection();
                conn.setDoInput(true);
                conn.setDoOutput(true);
                DataOutputStream dout = new DataOutputStream(conn.getOutputStream());

                Serialize.serialize(sharingKey.toUserPublicKey().serialize(), dout);
                Serialize.serialize(mapKey, dout);
                dout.flush();

                DataInputStream din = new DataInputStream(conn.getInputStream());
                int success = din.readInt();
                if (success != 1)
                    throw new IOException("Couldn't get value from BTree!");
                byte[] res = Serialize.deserializeByteArray(din, 256);
                if (res.length == 0)
                    return MaybeMultihash.EMPTY();
                byte[] multihash = new DataSource(res).readArray();
                if (multihash.length == 0)
                    return MaybeMultihash.EMPTY();
                return new MaybeMultihash(new Multihash(multihash));
            } finally {
                if (conn != null)
                    conn.disconnect();
            }
        }

        @Override
        public PairMultihash remove(UserPublicKey sharingKey, byte[] mapKey) throws IOException {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) buildURL("btree/delete").openConnection();
                conn.setDoInput(true);
                conn.setDoOutput(true);
                DataOutputStream dout = new DataOutputStream(conn.getOutputStream());

                Serialize.serialize(sharingKey.toUserPublicKey().serialize(), dout);
                Serialize.serialize(mapKey, dout);
                dout.flush();

                DataInputStream din = new DataInputStream(conn.getInputStream());
                int success = din.readInt();
                if (success != 1)
                    throw new IOException("Couldn't add data to DHT!");
                byte[] res = Serialize.deserializeByteArray(din, 512);
                DataSource source = new DataSource(res);
                // read header for byte array
                source.readInt();
                return new PairMultihash(MaybeMultihash.deserialize(source), MaybeMultihash.deserialize(source));
            } finally {
                if (conn != null)
                    conn.disconnect();
            }
        }
    }
}
