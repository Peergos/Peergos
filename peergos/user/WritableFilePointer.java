package peergos.user;

import peergos.crypto.*;
import peergos.util.*;

import java.io.*;

public class WritableFilePointer {
    public final UserPublicKey owner;
    public final User writer;
    public final ByteArrayWrapper mapKey;
    public final SymmetricKey rootDirKey;

    public WritableFilePointer(UserPublicKey owner, User writer, ByteArrayWrapper mapKey, SymmetricKey rootDirKey) {
        this.owner = owner;
        this.writer = writer;
        this.mapKey = mapKey;
        this.rootDirKey = rootDirKey;
    }

    public static WritableFilePointer deserialize(DataInput din) throws IOException {
        byte[] owner = Serialize.deserializeByteArray(din, UserContext.MAX_KEY_SIZE);
        byte[] privBytes = Serialize.deserializeByteArray(din, UserContext.MAX_KEY_SIZE);
        ByteArrayWrapper mapKey = new ByteArrayWrapper(Serialize.deserializeByteArray(din, UserContext.MAX_KEY_SIZE));
        byte[] secretRootDirKey = Serialize.deserializeByteArray(din, UserContext.MAX_KEY_SIZE);
        return new WritableFilePointer(new UserPublicKey(owner), User.deserialize(privBytes), mapKey, new SymmetricKey(secretRootDirKey));
    }

    public byte[] toByteArray() throws IOException {
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        serialize(new DataOutputStream(bout));
        return bout.toByteArray();
    }

    public void serialize(DataOutput dout) throws IOException {
        // TODO encrypt this
        Serialize.serialize(owner.getPublicKeys(), dout);
        Serialize.serialize(writer.getPrivateKeys(), dout);
        Serialize.serialize(mapKey.data, dout);
        Serialize.serialize(rootDirKey.getKey(), dout);
    }
}
