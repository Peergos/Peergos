package peergos.shared.user.fs.transaction;

import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;

import java.util.concurrent.CompletableFuture;

public interface Transaction extends Cborable {

    long startTimeEpochMillis();

    String name();

    /**
     * Clear data associated with this transaction
     */
    CompletableFuture<Snapshot> clear(Snapshot version, Committer committer, NetworkAccess network, Hasher h);

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

    static CompletableFuture<FileUploadTransaction> buildFileUploadTransaction(String path,
                                                                               long fileSize,
                                                                               byte[] streamSecret,
                                                                               AsyncReader fileData,
                                                                               SigningPrivateKeyAndPublicHash writer,
                                                                               Location firstChunkLocation,
                                                                               Hasher h) {
        return h.hash(path.getBytes(), true)
                .thenApply(cid -> new FileUploadTransaction(System.currentTimeMillis(), path, cid.toString(), writer, firstChunkLocation, fileSize, streamSecret));
    }
}
