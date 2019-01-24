package peergos.server.transaction;

import peergos.shared.NetworkAccess;
import peergos.shared.cbor.CborObject;
import peergos.shared.cbor.Cborable;
import peergos.shared.user.fs.Location;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface Transaction extends Cborable {

    long startTimeEpochMillis();

    long timeoutMs();

    String name();

    /**
     * Asynchronously clear data associated with this transaction
     */
    CompletableFuture<Boolean> clear(NetworkAccess networkAccess);


    default boolean hasTimedOut() {
        long now = System.currentTimeMillis();
        return (now - startTimeEpochMillis()) > timeoutMs();
    }

    static Transaction deserialize(byte[] data) {
        CborObject cborObject = CborObject.fromByteArray(data);
        CborObject.CborMap map =  (CborObject.CborMap) cborObject;
        Type type = Type.valueOf(map.get("type").toString());
        switch (type)  {
            case FILE_UPLOAD:
                return FileUploadTransaction.deserialize(map);
            default:
                throw new IllegalStateException("Unimplemented type "+ type);

        }
    }

    enum Type {
        FILE_UPLOAD
    }
}
