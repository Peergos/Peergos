package peergos.shared.user;

import peergos.shared.cbor.*;
import peergos.shared.crypto.symmetric.SymmetricKey;
import peergos.shared.user.fs.FilePointer;
import peergos.shared.util.*;

import java.io.*;
import java.util.*;

public class UserStaticData implements Cborable {

    private final List<EntryPoint> staticData;
    public final SymmetricKey rootKey;

    public UserStaticData(List<EntryPoint> staticData, SymmetricKey rootKey) {
        this.staticData = staticData;
        this.rootKey = rootKey;
    }

    public UserStaticData(SymmetricKey rootKey) {
        this(new ArrayList<>(), rootKey);
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
        if (! staticData.contains(entryPoint))
            staticData.add(entryPoint);
    }

    public boolean remove(FilePointer filePointer) {
        for (Iterator<EntryPoint> it = staticData.iterator() ;it.hasNext();) {
            EntryPoint entry = it.next();
            if (entry.pointer.equals(filePointer)) {
                it.remove();
                return true;
            }
        }
        return false;
    }

    public byte[] serialize() {
        DataSink sink = new DataSink();
        sink.writeInt(staticData.size());
        staticData.forEach(ep -> sink.writeArray(ep.serializeAndSymmetricallyEncrypt(rootKey)));
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
        return new HashSet<>(staticData);
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
