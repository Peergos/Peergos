package peergos.user;

import peergos.crypto.*;
import peergos.crypto.asymmetric.SecretBoxingKey;
import peergos.crypto.asymmetric.SecretSigningKey;
import peergos.crypto.symmetric.SymmetricKey;
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

    public static ReadableFilePointer deserialize(DataInputStream din) throws IOException {
        UserPublicKey owner = UserPublicKey.deserialize(din);
        UserPublicKey writer = UserPublicKey.deserialize(din);
        ByteArrayWrapper mapKey = new ByteArrayWrapper(Serialize.deserializeByteArray(din, UserContext.MAX_KEY_SIZE));
        byte[] secretRootDirKey = Serialize.deserializeByteArray(din, UserContext.MAX_KEY_SIZE);
        try {
            SecretSigningKey signingKey = SecretSigningKey.deserialize(din);
            SecretBoxingKey boxingKey = SecretBoxingKey.deserialize(din);
            writer = new User(signingKey, boxingKey, writer.publicSigningKey, writer.publicBoxingKey);
        } catch (EOFException e) {}
        return new ReadableFilePointer(owner, writer, mapKey, new SymmetricKey(secretRootDirKey));
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
        Serialize.serialize(owner.serializePublicKeys(), dout);
        // this is broken
        if (writer instanceof User)
            Serialize.serialize(((User)writer).getPrivateKeys(), dout);
        else
            Serialize.serialize(writer.serializePublicKeys(), dout);
        Serialize.serialize(mapKey.data, dout);
        Serialize.serialize(rootDirKey.getKey(), dout);
    }
}
