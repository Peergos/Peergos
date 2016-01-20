package peergos.user.fs;

import peergos.server.storage.ContentAddressedStorage;

import peergos.util.ByteArrayWrapper;
import peergos.util.Serialize;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Optional;

public class FileProperties {
    public final String name;
    public final int size;
    public final boolean isHidden;
    public final Optional<ByteArrayWrapper> thumbnail;

    public FileProperties(String name, int size, boolean isHidden, Optional<ByteArrayWrapper> thumbnail) {
        this.name = name;
        this.size = size;
        this.isHidden = isHidden;
        this.thumbnail = thumbnail;
    }

    public byte[] serialize() throws IOException {
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        try (DataOutputStream dout = new DataOutputStream(bout)) {
            dout.writeUTF(name);
            dout.write(size);
            dout.writeBoolean(isHidden);
            if (!thumbnail.isPresent())
                dout.write(-1);
            else
                Serialize.serialize(thumbnail.get().data, dout);
        }

        return bout.toByteArray();
    }

    public static FileProperties deserialize(DataInputStream din) throws IOException {
        String name = din.readUTF();
        int size = din.readInt();
        boolean isHidden = din.readBoolean();
        int length = din.readInt();
        Optional<ByteArrayWrapper> thumbnail = length == -1 ?
                Optional.<ByteArrayWrapper>empty() :
                Optional.of(new ByteArrayWrapper(Serialize.deserializeByteArray(din, ContentAddressedStorage.MAX_OBJECT_LENGTH)));

        return new FileProperties(name, size, isHidden, thumbnail);
    }
}