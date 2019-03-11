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

public abstract class CryptreeNode implements Cborable {

    public static final int CURRENT_FILE_VERSION = 1;
    public static final int CURRENT_DIR_VERSION = 1;

    public abstract MaybeMultihash committedHash();

    public abstract boolean isDirectory();

    public abstract int getVersion();

    public int getVersionAndType() {
        return getVersion() << 1 | (isDirectory() ? 0 : 1);
    }

    public abstract SymmetricKey getParentKey(SymmetricKey baseKey);

    public abstract SymmetricKey getMetaKey(SymmetricKey baseKey);

    public abstract EncryptedCapability getParentLink();

    public abstract FileProperties getProperties(SymmetricKey parentKey);

    public abstract Optional<SymmetricLinkToSigner> getWriterLink();

    public SigningPrivateKeyAndPublicHash getSigner(SymmetricKey wBaseKey, Optional<SigningPrivateKeyAndPublicHash> entrySigner) {
        return getWriterLink().map(link -> link.target(wBaseKey))
                .orElseGet(() -> entrySigner.orElseThrow(() ->
                        new IllegalStateException("No link to private signing key present on directory!")));
    }

    public Set<AbsoluteCapability> getChildrenCapabilities(AbsoluteCapability us) {
        return Collections.emptySet();
    }

    public abstract CompletableFuture<? extends CryptreeNode> updateProperties(WritableAbsoluteCapability us,
                                                               Optional<SigningPrivateKeyAndPublicHash> entryWriter,
                                                               FileProperties newProps,
                                                               NetworkAccess network);

    public abstract boolean isDirty(SymmetricKey baseKey);

    public abstract CryptreeNode withHash(Multihash newHash);

    public abstract CryptreeNode withWriterLink(SymmetricLinkToSigner newWriterLink);

    public abstract CryptreeNode withParentLink(EncryptedCapability newParentLink);

    /**
     *
     * @param rBaseKey
     * @return the mapkey of the next chunk of this file or folder if present
     */
    public abstract Optional<byte[]> getNextChunkLocation(SymmetricKey rBaseKey);

    public abstract CompletableFuture<? extends CryptreeNode> copyTo(AbsoluteCapability us,
                                                     SymmetricKey newBaseKey,
                                                     WritableAbsoluteCapability newParentCap,
                                                     Optional<SigningPrivateKeyAndPublicHash> newEntryWriter,
                                                     SymmetricKey parentparentKey,
                                                     byte[] newMapKey,
                                                     NetworkAccess network,
                                                     SafeRandom random);

    public boolean hasParentLink() {
        return getParentLink() != null;
    }

    public CompletableFuture<RetrievedCapability> getParent(PublicKeyHash owner,
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

    public static CryptreeNode fromCbor(CborObject cbor, Multihash hash) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Incorrect cbor for FileAccess: " + cbor);

        CborObject.CborMap m = (CborObject.CborMap) cbor;
        int versionAndType = (int) m.getLong("v");
        boolean isFile = (versionAndType & 1) != 0;
        if (isFile)
            return FileAccess.fromCbor(cbor, hash);
        return DirAccess.fromCbor(cbor, hash);
    }
}
