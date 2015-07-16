package peergos.user;

import peergos.corenode.*;
import peergos.crypto.*;
import peergos.util.*;

import java.io.*;
import java.util.*;

public class EntryPoint
{
    public static final int MAX_SIZE = 4*1024;
    public final ReadableFilePointer pointer;
    public final String owner;
    public final SortedSet<String> readers; // usernames of people we've granted read access
    public final SortedSet<String> writers; // usernames of people we've granted write access

    public EntryPoint(ReadableFilePointer pointer, String owner, SortedSet<String> readers, SortedSet<String> writers) {
        this.pointer = pointer;
        this.owner = owner;
        this.readers = readers;
        this.writers = writers;
        if (!pointer.isWritable() && writers.size() > 0)
            throw new IllegalArgumentException("We can't grant someone write access to something we only have read access to!");
    }

    public static EntryPoint decryptAndDeserialize(byte[] input, User user, UserPublicKey from) throws IOException {
        byte[] raw = user.decryptMessage(input, from.publicBoxingKey);
        DataInput din = new DataInputStream(new ByteArrayInputStream(raw));
        ReadableFilePointer pointer = ReadableFilePointer.deserialize(din);
        String owner = Serialize.deserializeString(din, 1024);
        int nReaders = din.readInt();
        SortedSet<String> readers = new TreeSet<>();
        for (int i=0; i < nReaders; i++)
            readers.add(Serialize.deserializeString(din, 1024));
        int nWriters = din.readInt();
        SortedSet<String> writers = new TreeSet<>();
        for (int i=0; i < nWriters; i++)
            writers.add(Serialize.deserializeString(din, 1024));
        return new EntryPoint(pointer, owner, readers, writers);
    }

    public byte[] serializeAndEncrypt(User user, UserPublicKey target) {
        return target.encryptMessageFor(serialize(), user.secretBoxingKey);
    }

    private byte[] serialize() {
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        DataOutput dout = new DataOutputStream(bout);
        try {
            pointer.serialize(dout);
            Serialize.serialize(owner, dout);
            dout.writeInt(readers.size());
            for (String reader : readers)
                Serialize.serialize(reader, dout);
            dout.writeInt(writers.size());
            for (String writer : writers)
                Serialize.serialize(writer, dout);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return bout.toByteArray();
    }
}
