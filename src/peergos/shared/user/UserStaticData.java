package peergos.shared.user;

import peergos.shared.crypto.UserPublicKey;
import peergos.shared.crypto.symmetric.SymmetricKey;
import peergos.shared.user.fs.ReadableFilePointer;
import peergos.shared.util.DataSink;

import java.util.*;

public class UserStaticData {

    private final SortedMap<UserPublicKey, EntryPoint> staticData = new TreeMap<>();

    public UserStaticData() {
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


    public byte[] serialize(SymmetricKey rootKey) {
        DataSink sink = new DataSink();
        sink.writeInt(staticData.size());
        staticData.values().forEach(ep -> sink.writeArray(ep.serializeAndSymmetricallyEncrypt(rootKey)));
        return sink.toByteArray();
    }

    public Set<EntryPoint> getEntryPoints() {
        return new HashSet<>(staticData.values());
    }

}
