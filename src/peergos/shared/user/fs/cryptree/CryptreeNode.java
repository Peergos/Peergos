package peergos.shared.user.fs.cryptree;

import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.crypto.random.*;
import peergos.shared.crypto.symmetric.*;
import peergos.shared.io.ipfs.multihash.*;
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

    EncryptedCapability getParentLink();

    FileProperties getProperties(SymmetricKey parentKey);

    Optional<SymmetricLinkToSigner> getWriterLink();

    default SigningPrivateKeyAndPublicHash getSigner(SymmetricKey wBaseKey, Optional<SigningPrivateKeyAndPublicHash> entrySigner) {
        return getWriterLink().map(link -> link.target(wBaseKey))
                .orElseGet(() -> entrySigner.orElseThrow(() ->
                        new IllegalStateException("No link to private signing key present on directory!")));
    }

    CompletableFuture<? extends CryptreeNode> updateProperties(WritableAbsoluteCapability us,
                                                               Optional<SigningPrivateKeyAndPublicHash> entryWriter,
                                                               FileProperties newProps,
                                                               NetworkAccess network);

    boolean isDirty(SymmetricKey baseKey);

    CryptreeNode withWriterLink(SymmetricLinkToSigner newWriterLink);

    CryptreeNode withParentLink(EncryptedCapability newParentLink);

    /**
     *
     * @param rBaseKey
     * @return the mapkey of the next chunk of this file or folder if present
     */
    Optional<byte[]> getNextChunkLocation(SymmetricKey rBaseKey);

    CompletableFuture<? extends CryptreeNode> copyTo(AbsoluteCapability us,
                                                     SymmetricKey newBaseKey,
                                                     WritableAbsoluteCapability newParentCap,
                                                     Optional<SigningPrivateKeyAndPublicHash> newEntryWriter,
                                                     SymmetricKey parentparentKey,
                                                     byte[] newMapKey,
                                                     NetworkAccess network,
                                                     SafeRandom random);

    default CompletableFuture<RetrievedCapability> getParent(PublicKeyHash owner,
                                                             PublicKeyHash writer,
                                                             SymmetricKey baseKey,
                                                             NetworkAccess network) {
        EncryptedCapability parentLink = getParentLink();
        if (parentLink == null)
            return CompletableFuture.completedFuture(null);

        RelativeCapability relCap = parentLink.toCapability(baseKey);
        return network.retrieveAllMetadata(Arrays.asList(new AbsoluteCapability(owner, relCap.writer.orElse(writer), relCap.getMapKey(),
                relCap.rBaseKey, Optional.empty()))).thenApply(res -> {
            RetrievedCapability retrievedCapability = res.stream().findAny().get();
            return retrievedCapability;
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
