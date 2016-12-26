package peergos.shared.user;

import peergos.shared.cbor.*;
import peergos.shared.crypto.UserPublicKey;
import peergos.shared.crypto.symmetric.SymmetricKey;
import peergos.shared.user.fs.ReadableFilePointer;
import peergos.shared.util.*;

import java.io.*;
import java.util.*;

public class UserStaticData implements Cborable {

    private final SortedMap<UserPublicKey, EntryPoint> staticData;
    public final SymmetricKey rootKey;

    public UserStaticData(SortedMap<UserPublicKey, EntryPoint> staticData, SymmetricKey rootKey) {
        this.staticData = staticData;
        this.rootKey = rootKey;
    }

    public UserStaticData(SymmetricKey rootKey) {
        this(new TreeMap<>(), rootKey);
    }

    public UserStaticData withKey(SymmetricKey newKey) {
        return new UserStaticData(staticData, newKey);
    }

    public void clear() {
        staticData.clear();
    }

    public int size() {
        return staticData.size();
    }

    public void add(EntryPoint entryPoint) {
        staticData.put(entryPoint.pointer.location.writer, entryPoint);
    }

    public EntryPoint get(UserPublicKey userPublicKey) {
        return staticData.get(userPublicKey);
    }


    public boolean remove(ReadableFilePointer readableFilePointer) {
        for (Iterator<Map.Entry<UserPublicKey, EntryPoint>> it = staticData.entrySet().iterator() ;it.hasNext();) {
            Map.Entry<UserPublicKey, EntryPoint> entry = it.next();
            if (entry.getValue().pointer.equals(readableFilePointer)) {
                it.remove();
                return true;
            }
        }
        return false;
    }


    public byte[] serialize() {
        DataSink sink = new DataSink();
        sink.writeInt(staticData.size());
        staticData.values().forEach(ep -> sink.writeArray(ep.serializeAndSymmetricallyEncrypt(rootKey)));
        return sink.toByteArray();
    }

    public static UserStaticData deserialize(byte[] raw, SymmetricKey rootKey) {
        try {
            DataSource source = new DataSource(raw);
            int count = source.readInt();
            System.out.println("Found "+count+" entry points");

            UserStaticData staticData = new UserStaticData(rootKey);
            for (int i = 0; i < count; i++) {
                EntryPoint entry = EntryPoint.symmetricallyDecryptAndDeserialize(source.readArray(), rootKey);
                staticData.add(entry);
            }
            return staticData;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public Set<EntryPoint> getEntryPoints() {
        return new HashSet<>(staticData.values());
    }

    @Override
    public CborObject toCbor() {
        return new CborObject.CborByteArray(serialize());
    }

    public static UserStaticData fromCbor(CborObject cbor, SymmetricKey rootKey) {
        if (! (cbor instanceof CborObject.CborByteArray))
            throw new IllegalStateException("UserStaticData cbor must be a byte[]! " + cbor);
        return deserialize(((CborObject.CborByteArray) cbor).value, rootKey);
    }
}
