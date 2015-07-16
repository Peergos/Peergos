package peergos.user;

import peergos.crypto.*;
import peergos.util.*;

import java.io.*;

public class ReadableFilePointer {
    public final UserPublicKey owner;
    public final UserPublicKey writer; // will be a User if pointer is writable
    public final ByteArrayWrapper mapKey;
    public final SymmetricKey rootDirKey;

    public ReadableFilePointer(UserPublicKey owner, UserPublicKey writer, ByteArrayWrapper mapKey, SymmetricKey rootDirKey) {
        this.owner = owner;
        this.writer = writer;
        this.mapKey = mapKey;
        this.rootDirKey = rootDirKey;
    }

    public static ReadableFilePointer deserialize(DataInput din) throws IOException {
        byte[] owner = Serialize.deserializeByteArray(din, UserContext.MAX_KEY_SIZE);
        byte[] writerRaw = Serialize.deserializeByteArray(din, UserContext.MAX_KEY_SIZE);
        ByteArrayWrapper mapKey = new ByteArrayWrapper(Serialize.deserializeByteArray(din, UserContext.MAX_KEY_SIZE));
        byte[] secretRootDirKey = Serialize.deserializeByteArray(din, UserContext.MAX_KEY_SIZE);
        UserPublicKey writer = writerRaw.length == TweetNaCl.BOX_SECRET_KEY_BYTES + TweetNaCl.SIGN_SECRET_KEY_BYTES ?
                User.deserialize(writerRaw) : new UserPublicKey(writerRaw);
        return new ReadableFilePointer(new UserPublicKey(owner), writer, mapKey, new SymmetricKey(secretRootDirKey));
    }

    public boolean isWritable() {
        return writer instanceof User;
    }

    public byte[] toByteArray() throws IOException {
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        serialize(new DataOutputStream(bout));
        return bout.toByteArray();
    }

    public void serialize(DataOutput dout) throws IOException {
        Serialize.serialize(owner.getPublicKeys(), dout);
        if (writer instanceof User)
            Serialize.serialize(((User)writer).getPrivateKeys(), dout);
        else
            Serialize.serialize(writer.getPublicKeys(), dout);
        Serialize.serialize(mapKey.data, dout);
        Serialize.serialize(rootDirKey.getKey(), dout);
    }
}
