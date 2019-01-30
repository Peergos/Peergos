package peergos.server.transaction;

import jsinterop.annotations.JsMethod;
import peergos.shared.NetworkAccess;
import peergos.shared.cbor.CborObject;
import peergos.shared.cbor.Cborable;
import peergos.shared.crypto.SigningPrivateKeyAndPublicHash;
import peergos.shared.crypto.hash.PublicKeyHash;
import peergos.shared.user.fs.Location;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface Transaction extends Cborable {

    long startTimeEpochMillis();

    String name();

    /**
     * Asynchronously clear data associated with this transaction
     */
    CompletableFuture<Boolean> clear(NetworkAccess networkAccess);


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


    @JsMethod
    static Transaction buildFileUploadTransaction(String path,
                                                  SigningPrivateKeyAndPublicHash writer,
                                                  List<Location> locations) {
        long startTimeEpochMillis = System.currentTimeMillis();
        return new FileUploadTransaction(startTimeEpochMillis, path, writer, locations);
    }
}
