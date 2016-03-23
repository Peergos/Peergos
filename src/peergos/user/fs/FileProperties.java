package peergos.user.fs;

import peergos.server.storage.ContentAddressedStorage;

import peergos.util.*;

import java.io.*;
import java.time.*;
import java.util.Optional;

public class FileProperties {
    public final String name;
    public final long size;
    public final LocalDateTime modified;
    public final boolean isHidden;
    public final Optional<byte[]> thumbnail;

    public FileProperties(String name, long size, LocalDateTime modified, boolean isHidden, Optional<byte[]> thumbnail) {
        this.name = name;
        this.size = size;
        this.modified = modified;
        this.isHidden = isHidden;
        this.thumbnail = thumbnail;
    }

    public byte[] serialize() {
        DataSink dout = new DataSink();
        dout.writeString(name);
        dout.writeDouble(size);
        dout.writeDouble(modified.toEpochSecond(ZoneOffset.UTC));
        dout.writeBoolean(isHidden);
        if (!thumbnail.isPresent())
            dout.write(-1);
        else
            dout.writeArray(thumbnail.get());

        return dout.toByteArray();
    }

    public static FileProperties deserialize(byte[] raw) throws IOException {
        DataInput din = new DataSource(raw);
        String name = din.readUTF();
        int size = din.readInt();
        double modified = din.readDouble();
        boolean isHidden = din.readBoolean();
        int length = din.readInt();
        Optional<byte[]> thumbnail = length == -1 ?
                Optional.empty() :
                Optional.of(Serialize.deserializeByteArray(din, ContentAddressedStorage.MAX_OBJECT_LENGTH));

        return new FileProperties(name, size, LocalDateTime.ofEpochSecond((int)modified, 0, ZoneOffset.UTC), isHidden, thumbnail);
    }
}