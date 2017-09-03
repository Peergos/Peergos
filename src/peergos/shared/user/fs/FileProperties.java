package peergos.shared.user.fs;

import jsinterop.annotations.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.symmetric.*;
import peergos.shared.storage.ContentAddressedStorage;
import peergos.shared.util.*;

import java.io.*;
import java.time.*;
import java.util.*;

@JsType
public class FileProperties {
    public static final FileProperties EMPTY = new FileProperties("", 0, LocalDateTime.MIN, false, Optional.empty());

    public final String name;
    @JsIgnore
    public final long size;
    public final LocalDateTime modified;
    public final boolean isHidden;
    public final Optional<byte[]> thumbnail;

    public FileProperties(String name, int sizeHi, int sizeLo, LocalDateTime modified, boolean isHidden, Optional<byte[]> thumbnail) {
        this.name = name;
        this.size = sizeLo | ((sizeHi | 0L) << 32);
        this.modified = modified;
        this.isHidden = isHidden;
        this.thumbnail = thumbnail;
    }

    @JsIgnore
    public FileProperties(String name, long size, LocalDateTime modified, boolean isHidden, Optional<byte[]> thumbnail) {
        this(name, (int)(size >> 32), (int) size, modified, isHidden, thumbnail);
    }

    public int sizeLow() {
        return (int) size;
    }

    public int sizeHigh() {
        return (int) (size >> 32);
    }

    public byte[] serialize() {
        DataSink dout = new DataSink();
        dout.writeString(name);
        dout.writeLong(size);
        dout.writeLong(modified.toEpochSecond(ZoneOffset.UTC));
        dout.writeBoolean(isHidden);
        if (!thumbnail.isPresent())
            dout.writeInt(0);
        else {
            dout.writeArray(thumbnail.get());
        }
        return dout.toByteArray();
    }

    public static FileProperties deserialize(byte[] raw) throws IOException {
        DataSource din = new DataSource(raw);
        String name = din.readString();
        long size = din.readLong();
        long modified = din.readLong();
        boolean isHidden = din.readBoolean();
        int length = din.readInt();
        Optional<byte[]> thumbnail = length == 0 ?
                Optional.empty() :
                Optional.of(Serialize.deserializeByteArray(length, din, length));

        return new FileProperties(name, size, LocalDateTime.ofEpochSecond(modified, 0, ZoneOffset.UTC), isHidden, thumbnail);
    }

    public static FileProperties decrypt(byte[] raw, SymmetricKey metaKey) {
        try {
            byte[] nonce = Arrays.copyOfRange(raw, 0, TweetNaCl.SECRETBOX_NONCE_BYTES);
            byte[] cipher = Arrays.copyOfRange(raw, TweetNaCl.SECRETBOX_NONCE_BYTES, raw.length);
            return FileProperties.deserialize(metaKey.decrypt(cipher, nonce));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @JsIgnore
    public FileProperties withSize(long newSize) {
        return new FileProperties(name, newSize, modified, isHidden, thumbnail);
    }

    public FileProperties withModified(LocalDateTime modified) {
        return new FileProperties(name, size, modified, isHidden, thumbnail);
    }

    @Override
    public String toString() {
        return "FileProperties{" +
                "name='" + name + '\'' +
                ", size=" + size +
                ", modified=" + modified +
                ", isHidden=" + isHidden +
                ", thumbnail=" + thumbnail +
                '}';
    }
}