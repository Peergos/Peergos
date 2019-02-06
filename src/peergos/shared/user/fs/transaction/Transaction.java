package peergos.shared.user.fs.transaction;

import jsinterop.annotations.JsMethod;
import peergos.shared.NetworkAccess;
import peergos.shared.cbor.CborObject;
import peergos.shared.cbor.Cborable;
import peergos.shared.crypto.SigningPrivateKeyAndPublicHash;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.user.fs.*;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface Transaction extends Cborable {

    long startTimeEpochMillis();

    String name();

    /**
     * Clear data associated with this transaction
     */
    CompletableFuture<Boolean> clear(NetworkAccess networkAccess);

    static Transaction deserialize(byte[] data) {
        CborObject cborObject = CborObject.fromByteArray(data);
        CborObject.CborMap map =  (CborObject.CborMap) cborObject;
        Type type = Type.valueOf(map.getString("type"));
        switch (type)  {
            case FILE_UPLOAD:
                return FileUploadTransaction.fromCbor(map);
            default:
                throw new IllegalStateException("Unimplemented type "+ type);
        }
    }

    enum Type {
        FILE_UPLOAD
    }

    @JsMethod
    static CompletableFuture<FileUploadTransaction> buildFileUploadTransaction(String path,
                                                                               int fileSizeLo,
                                                                               int fileSizeHi,
                                                                               AsyncReader fileData,
                                                                               SigningPrivateKeyAndPublicHash writer,
                                                                               List<Location> locations) {
        return buildFileUploadTransaction(path, fileSizeLo & 0xFFFFFFFFL | (((long) fileSizeHi)) << 32,
                fileData, writer, locations);
    }

    static CompletableFuture<FileUploadTransaction> buildFileUploadTransaction(String path,
                                                                               long fileSize,
                                                                               AsyncReader fileData,
                                                                               SigningPrivateKeyAndPublicHash writer,
                                                                               List<Location> locations) {
        return Hash.sha256(fileData, fileSize)
                .thenApply(hash -> new FileUploadTransaction(System.currentTimeMillis(), path,
                        new Multihash(Multihash.Type.sha2_256, hash), writer, locations));
    }
}
