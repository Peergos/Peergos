package peergos.user.fs;

import org.bouncycastle.util.Arrays;
import peergos.crypto.SymmetricLink;
import peergos.crypto.User;
import peergos.crypto.UserPublicKey;
import peergos.crypto.asymmetric.PublicSigningKey;
import peergos.crypto.symmetric.SymmetricKey;
import peergos.util.ByteArrayWrapper;
import peergos.util.Serialize;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public class FileAccess {
    public static class EncryptedFileProperties{
        //todo : wrap byte array
    }
    public static class  FileRetriever{
        //todo
    }
    public static class ReadableFilePointer {
        public final UserPublicKey owner, writer;
        public final ByteArrayWrapper mapKey;
        public final SymmetricKey baseKey;

        public ReadableFilePointer(UserPublicKey owner, UserPublicKey writer, ByteArrayWrapper mapKey, SymmetricKey baseKey) {
            this.owner = owner;
            this.writer = writer;
            this.mapKey = mapKey;
            this.baseKey = baseKey;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            ReadableFilePointer that = (ReadableFilePointer) o;

            if (owner != null ? !owner.equals(that.owner) : that.owner != null) return false;
            if (writer != null ? !writer.equals(that.writer) : that.writer != null) return false;
            if (mapKey != null ? !mapKey.equals(that.mapKey) : that.mapKey != null) return false;
            return baseKey != null ? baseKey.equals(that.baseKey) : that.baseKey == null;

        }

        @Override
        public int hashCode() {
            int result = owner != null ? owner.hashCode() : 0;
            result = 31 * result + (writer != null ? writer.hashCode() : 0);
            result = 31 * result + (mapKey != null ? mapKey.hashCode() : 0);
            result = 31 * result + (baseKey != null ? baseKey.hashCode() : 0);
            return result;
        }

        public void serialize(DataOutputStream  dout) throws IOException {
            dout.write(owner.serialize());
            dout.writeBoolean(isWritable());
            dout.write(writer.serialize());
            dout.write(mapKey.data);
            baseKey.serialize(dout);
        }

        public  ReadableFilePointer toReadOnly() {
            if (! isWritable())
                return this;
            UserPublicKey readOnly = new UserPublicKey(writer.publicSigningKey, writer.publicBoxingKey);
            return new ReadableFilePointer(owner, readOnly, mapKey, baseKey);
        }

        public boolean isWritable(){
            return this.writer instanceof User;
        }

        public String toPublicLink() {
            throw new IllegalStateException("Unimplemented");
        }

        public static ReadableFilePointer fromPublicLink(String keysString) {
            throw new IllegalStateException("Unimplemented");
        }

        public static ReadableFilePointer deserialize(DataInputStream din) throws IOException {
            UserPublicKey owner = UserPublicKey.deserialize(din);
            boolean hasPrivateKeys = din.readBoolean();
            UserPublicKey writer = hasPrivateKeys ? User.deserialize(din): UserPublicKey.deserialize(din);
            byte[] mayKey = Serialize.deserializeByteArray(din, 0x1000);
            SymmetricKey baseKey = SymmetricKey.deserialize(din);
            return new ReadableFilePointer(owner, writer, new ByteArrayWrapper(mayKey), baseKey);
        }

        public static ReadableFilePointer createNull() {
            throw new IllegalStateException("Unimplemented");
        }
    }

    public static class SymmetricLocationLink {
        public final ByteArrayWrapper link;
        public final Location location;

        public SymmetricLocationLink(ByteArrayWrapper link, Location location) {
            this.link = link;
            this.location = location;
        }


        public static SymmetricLocationLink deserialize(DataInputStream din) throws IOException {
            byte[] link = Serialize.deserializeByteArray(din, 0x1000);
            Location location = Location.deserialize(din);
            return new SymmetricLocationLink(
                    new ByteArrayWrapper(link), location);
        }

        public void serialize(DataOutputStream dout) throws IOException {
            dout.write(link.data);
            dout.write(location.serialize());
        }

        public static SymmetricLocationLink create(SymmetricKey fromKey, SymmetricKey toKey, Location location) {

            ByteArrayWrapper nonce = new ByteArrayWrapper(
                    fromKey.createNonce());

            byte[] bytes = fromKey.encrypt(toKey.toByteArray(), nonce.data);
            byte[] link = Arrays.concatenate(nonce.data, bytes);

            return new SymmetricLocationLink(
                    new ByteArrayWrapper(link),
                    location);
        }
    }


    public static class RetrievedFilePointer {
        public final ReadableFilePointer readableFilePointer;
        public final FileAccess fileAccess;

        public RetrievedFilePointer(ReadableFilePointer readableFilePointer, FileAccess fileAccess) {
            this.readableFilePointer = readableFilePointer;
            this.fileAccess = fileAccess;
        }
    }


//    private final SymmetricLink parentToMeta;
//    private final EncryptedFileProperties fileProperties;
//    private final FileRetriever retriever;
//    private final SymmetricLocationLink parentLink;

}
