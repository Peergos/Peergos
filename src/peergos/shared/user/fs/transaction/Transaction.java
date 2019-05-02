package peergos.shared.user.fs.transaction;

import jsinterop.annotations.JsMethod;
import peergos.shared.NetworkAccess;
import peergos.shared.cbor.CborObject;
import peergos.shared.cbor.Cborable;
import peergos.shared.crypto.SigningPrivateKeyAndPublicHash;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface Transaction extends Cborable {

    long startTimeEpochMillis();

    String name();

    /**
     * Clear data associated with this transaction
     */
    CompletableFuture<Snapshot> clear(Snapshot version, Committer committer, NetworkAccess network);

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
        return CompletableFuture.completedFuture(new FileUploadTransaction(System.currentTimeMillis(), path, writer, locations));
    }
}
