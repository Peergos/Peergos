package peergos.shared.user;

import jsinterop.annotations.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.symmetric.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.io.*;
import java.util.*;

@JsType
public class EntryPoint {

    public final FilePointer pointer;
    public final String owner;
    public final Set<String> readers, writers;

    public EntryPoint(FilePointer pointer, String owner, Set<String> readers, Set<String> writers) {
        this.pointer = pointer;
        this.owner = owner;
        this.readers = readers;
        this.writers = writers;
    }

    public byte[] serializeAndEncrypt(BoxingKeyPair user, PublicBoxingKey target) throws IOException {
        return target.encryptMessageFor(this.serialize(), user.secretBoxingKey);
    }

    public byte[] serializeAndSymmetricallyEncrypt(SymmetricKey key) {
        byte[] nonce = key.createNonce();
        return ArrayOps.concat(nonce, key.encrypt(serialize(), nonce));
    }

    public byte[] serialize() {
        DataSink sink = new DataSink();
        sink.writeArray(pointer.serialize());
        sink.writeString(owner);
        sink.writeInt(readers.size());
        readers.forEach(s -> sink.writeString(s));
        sink.writeInt(writers.size());
        writers.forEach(s -> sink.writeString(s));
        return sink.toByteArray();
    }

    static EntryPoint deserialize(byte[] raw) throws IOException {
        DataSource din = new DataSource(raw);
        FilePointer pointer = FilePointer.fromByteArray(din.readArray());
        String owner = din.readString();
        int nReaders = din.readInt();
        Set<String> readers = new HashSet<>();
        for (int i=0; i < nReaders; i++)
            readers.add(din.readString());
        int nWriters = din.readInt();
        Set<String> writers = new HashSet<>();
        for (int i=0; i < nWriters; i++)
            writers.add(din.readString());
        return new EntryPoint(pointer, owner, readers, writers);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        EntryPoint that = (EntryPoint) o;

        if (pointer != null ? !pointer.equals(that.pointer) : that.pointer != null) return false;
        if (owner != null ? !owner.equals(that.owner) : that.owner != null) return false;
        if (readers != null ? !readers.equals(that.readers) : that.readers != null) return false;
        return writers != null ? writers.equals(that.writers) : that.writers == null;

    }

    @Override
    public int hashCode() {
        int result = pointer != null ? pointer.hashCode() : 0;
        result = 31 * result + (owner != null ? owner.hashCode() : 0);
        result = 31 * result + (readers != null ? readers.hashCode() : 0);
        result = 31 * result + (writers != null ? writers.hashCode() : 0);
        return result;
    }

    static EntryPoint symmetricallyDecryptAndDeserialize(byte[] input, SymmetricKey key) throws IOException {
        byte[] nonce = Arrays.copyOfRange(input, 0, 24);
        byte[] raw = key.decrypt(Arrays.copyOfRange(input, 24, input.length), nonce);
        DataInputStream din = new DataInputStream(new ByteArrayInputStream(raw));
        FilePointer pointer = FilePointer.fromByteArray(Serialize.deserializeByteArray(din, 4*1024*1024));
        String owner = Serialize.deserializeString(din, CoreNode.MAX_USERNAME_SIZE);
        int nReaders = din.readInt();
        Set<String> readers = new HashSet<>();
        for (int i=0; i < nReaders; i++)
            readers.add(Serialize.deserializeString(din, CoreNode.MAX_USERNAME_SIZE));
        int nWriters = din.readInt();
        Set<String> writers = new HashSet<>();
        for (int i=0; i < nWriters; i++)
            writers.add(Serialize.deserializeString(din, CoreNode.MAX_USERNAME_SIZE));
        return new EntryPoint(pointer, owner, readers, writers);
    }

}
