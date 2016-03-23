package peergos.user.fs;

import org.ipfs.api.*;
import peergos.crypto.*;
import peergos.crypto.symmetric.SymmetricKey;
import peergos.util.*;

import java.io.*;

public class ReadableFilePointer {
    public final UserPublicKey owner, writer;
    public final byte[] mapKey;
    public final SymmetricKey baseKey;

    public ReadableFilePointer(UserPublicKey owner, UserPublicKey writer, byte[] mapKey, SymmetricKey baseKey) {
        this.owner = owner;
        this.writer = writer;
        this.mapKey = mapKey;
        this.baseKey = baseKey;
    }

    public byte[] serialize() {
        try {
            DataSink bout = new DataSink();
            bout.writeArray(owner.getPublicKeys());
            bout.writeByte(this.isWritable() ? 1 : 0);
            writer.serialize(bout);
            bout.writeArray(mapKey);
            bout.writeArray(baseKey.serialize());
            return bout.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public ReadableFilePointer readOnly() {
	if (!isWritable())
	    return this;
	UserPublicKey publicWriter = UserPublicKey.fromPublicKeys(this.writer.getPublicKeys());
	return new ReadableFilePointer(this.owner, publicWriter, this.mapKey, this.baseKey);
    }

    public boolean isWritable() {
        return this.writer instanceof User;
    }

    public String toLink() {
        return "#" + Base58.encode(owner.getPublicKeys()) + "/" + Base58.encode(writer.getPublicKeys()) + "/" + Base58.encode(mapKey) + "/" + Base58.encode(baseKey.serialize());
    }

    public static ReadableFilePointer fromLink(String keysString) {
        String[] split = keysString.split("/");
        UserPublicKey owner = UserPublicKey.fromPublicKeys(Base58.decode(split[0]));
        UserPublicKey writer = UserPublicKey.fromPublicKeys(Base58.decode(split[1]));
        byte[] mapKey = Base58.decode(split[2]);
        SymmetricKey baseKey = SymmetricKey.deserialize(Base58.decode(split[3]));
        return new ReadableFilePointer(owner, writer, mapKey, baseKey);
    }

    public static ReadableFilePointer deserialize(byte[] arr) throws IOException {
        DataSource bin = new DataSource(arr);
        byte[] owner = bin.readArray();
        boolean hasPrivateKeys = bin.readByte() == 1;
        UserPublicKey writer = hasPrivateKeys ? User.deserialize(bin) : UserPublicKey.deserialize(bin);
        byte[] mapKey = bin.readArray();
        byte[] rootDirKeySecret = bin.readArray();
        return new ReadableFilePointer(UserPublicKey.fromPublicKeys(owner), writer, mapKey, SymmetricKey.deserialize(rootDirKeySecret));
    }

    public static ReadableFilePointer createNull() {
        return new ReadableFilePointer(UserPublicKey.createNull(), UserPublicKey.createNull(), new byte(32), SymmetricKey.createNull());
    }
}
