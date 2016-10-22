package peergos.shared.user.fs;

import jsinterop.annotations.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.symmetric.SymmetricKey;
import peergos.shared.ipfs.api.Base58;
import peergos.shared.util.*;

import java.io.*;
import java.util.*;

public class ReadableFilePointer {
    public final Location location;
    public final SymmetricKey baseKey;

    @JsConstructor
    public ReadableFilePointer(Location location, SymmetricKey baseKey) {
        this.location = location;
        this.baseKey = baseKey;
    }

    public ReadableFilePointer(UserPublicKey owner, UserPublicKey writer, byte[] mapKey, SymmetricKey baseKey) {
        this(new Location(owner, writer, mapKey), baseKey);
    }

    public Location getLocation() {
        return location;
    }

    public ReadableFilePointer withBaseKey(SymmetricKey newBaseKey) {
        return new ReadableFilePointer(location, newBaseKey);
    }

    public ReadableFilePointer withWritingKey(UserPublicKey writingKey) {
        return new ReadableFilePointer(location.withWriter(writingKey), baseKey);
    }

    public byte[] serialize() {
        try {
            DataSink bout = new DataSink();
            bout.writeArray(location.owner.getPublicKeys());
            bout.writeByte(this.isWritable() ? 1 : 0);
            location.writer.serialize(bout);
            bout.writeArray(location.getMapKey());
            bout.writeArray(baseKey.serialize());
            return bout.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static ReadableFilePointer deserialize(byte[] arr) throws IOException {
        DataSource bin = new DataSource(arr);
        UserPublicKey owner = UserPublicKey.fromByteArray(bin.readArray());
        UserPublicKey writer = User.deserialize(bin);
        byte[] mapKey = bin.readArray();
        byte[] rootDirKeySecret = bin.readArray();
        return new ReadableFilePointer(owner, writer, mapKey, SymmetricKey.deserialize(rootDirKeySecret));
    }

    public ReadableFilePointer readOnly() {
        if (!isWritable())
            return this;
        UserPublicKey publicWriter = UserPublicKey.fromPublicKeys(this.location.writer.getPublicKeys());
        return new ReadableFilePointer(this.location.owner, publicWriter, this.location.getMapKey(), this.baseKey);
    }

    public boolean isWritable() {
        return this.location.writer instanceof User;
    }

    public String toLink() {
        return "#" + Base58.encode(location.writer.getPublicKeys()) + "/" + Base58.encode(location.getMapKey()) + "/" + Base58.encode(baseKey.serialize());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        ReadableFilePointer that = (ReadableFilePointer) o;

        if (location != null ? !location.equals(that.location) : that.location != null) return false;
        return baseKey != null ? baseKey.equals(that.baseKey) : that.baseKey == null;

    }

    @Override
    public int hashCode() {
        int result = location != null ? location.hashCode() : 0;
        result = 31 * result + (baseKey != null ? baseKey.hashCode() : 0);
        return result;
    }

    @Override
    public String toString() {
        return ArrayOps.bytesToHex(location.getMapKey());
    }

    public boolean isNull() {
        UserPublicKey nullUser = UserPublicKey.createNull();
        return nullUser.equals(location.owner) &&
                nullUser.equals(location.writer) &&
                Arrays.equals(location.getMapKey(), new byte[32]) &&
                baseKey.equals(SymmetricKey.createNull());
    }

    public static ReadableFilePointer fromLink(String keysString) {
        if (keysString.startsWith("#"))
            keysString = keysString.substring(1);
        String[] split = keysString.split("/");
        UserPublicKey owner = UserPublicKey.createNull();
        UserPublicKey writer = UserPublicKey.fromPublicKeys(Base58.decode(split[0]));
        byte[] mapKey = Base58.decode(split[1]);
        SymmetricKey baseKey = SymmetricKey.deserialize(Base58.decode(split[2]));
        return new ReadableFilePointer(owner, writer, mapKey, baseKey);
    }

    public static ReadableFilePointer createNull() {
        return new ReadableFilePointer(UserPublicKey.createNull(), UserPublicKey.createNull(), new byte[32], SymmetricKey.createNull());
    }
}
