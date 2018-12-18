package peergos.shared.user.fs.cryptree;

import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.crypto.random.*;
import peergos.shared.crypto.symmetric.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.merklebtree.*;
import peergos.shared.user.fs.*;

import java.util.*;
import java.util.concurrent.*;

public interface CryptreeNode extends Cborable {

    int CURRENT_FILE_VERSION = 1;
    int CURRENT_DIR_VERSION = 1;

    MaybeMultihash committedHash();

    boolean isDirectory();

    int getVersion();

    default int getVersionAndType() {
        return getVersion() << 1 | (isDirectory() ? 0 : 1);
    }

    SymmetricKey getParentKey(SymmetricKey baseKey);

    SymmetricKey getMetaKey(SymmetricKey baseKey);

    SymmetricLocationLink getParentLink();

    FileProperties getProperties(SymmetricKey parentKey);

    CompletableFuture<? extends CryptreeNode> updateProperties(Capability writableCapability,
                                                               FileProperties newProps,
                                                               NetworkAccess network);

    boolean isDirty(SymmetricKey baseKey);

    CompletableFuture<? extends CryptreeNode> copyTo(SymmetricKey baseKey,
                                                   SymmetricKey newBaseKey,
                                                   Location newParentLocation,
                                                   SymmetricKey parentparentKey,
                                                   PublicKeyHash newOwner,
                                                   SigningPrivateKeyAndPublicHash entryWriterKey,
                                                   byte[] newMapKey,
                                                   NetworkAccess network,
                                                   SafeRandom random);

    default CompletableFuture<RetrievedFilePointer> getParent(SymmetricKey baseKey, NetworkAccess network) {
        SymmetricLocationLink parentLink = getParentLink();
        if (parentLink == null)
            return CompletableFuture.completedFuture(null);

        return network.retrieveAllMetadata(Arrays.asList(parentLink.toCapability(baseKey))).thenApply(res -> {
            RetrievedFilePointer retrievedFilePointer = res.stream().findAny().get();
            return retrievedFilePointer;
        });
    }

    static CryptreeNode fromCbor(CborObject cbor, Multihash hash) {
        if (! (cbor instanceof CborObject.CborList))
            throw new IllegalStateException("Incorrect cbor for FileAccess: " + cbor);

        List<? extends Cborable> value = ((CborObject.CborList) cbor).value;
        int versionAndType = (int) ((CborObject.CborLong) value.get(0)).value;
        boolean isFile = (versionAndType & 1) != 0;
        if (isFile)
            return FileAccess.fromCbor(cbor, hash);
        return DirAccess.fromCbor(cbor, hash);
    }
}
