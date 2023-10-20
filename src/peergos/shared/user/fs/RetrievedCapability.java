package peergos.shared.user.fs;

import peergos.shared.crypto.symmetric.*;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.user.fs.cryptree.*;

public class RetrievedCapability {
    public final AbsoluteCapability capability;
    public final CryptreeNode fileAccess;

    public RetrievedCapability(AbsoluteCapability capability, CryptreeNode fileAccess) {
        if (fileAccess == null)
            throw new IllegalStateException("Null FileAccess!");
        this.capability = capability;
        this.fileAccess = fileAccess;
    }

    public SymmetricKey getParentKey() {
        return getParentKey(fileAccess, capability.rBaseKey);
    }

    public FileProperties getProperties() {
        return fileAccess.getProperties(getParentKey());
    }

    public SymmetricKey getParentParentKey() {
        return fileAccess.getParentCapability(capability.rBaseKey).get().rBaseKey;
    }

    public RelativeCapability getParentCap() {
        return fileAccess.getParentCapability(capability.rBaseKey).get();
    }

    private static SymmetricKey getParentKey(CryptreeNode node, SymmetricKey baseKey) {
        if (node.isDirectory())
            try {
                return node.getParentKey(baseKey);
            } catch (Exception e) {
                // if we don't have read access to this folder, then we must just have the parent key already
            }
        return baseKey;
    }

    public RetrievedCapability withCryptree(CryptreeNode fileAccess) {
        return new RetrievedCapability(capability, fileAccess);
    }

    public RetrievedCapability withHash(Multihash newHash) {
        return new RetrievedCapability(capability, fileAccess.withHash(newHash));
    }
}
