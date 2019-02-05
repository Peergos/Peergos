package peergos.shared.user.fs;

import peergos.shared.io.ipfs.multihash.*;
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

    public boolean equals(Object that) {
        if (that == null)
            return false;
        if (!(that instanceof RetrievedCapability))
            return false;
        return capability.equals(((RetrievedCapability)that).capability);
    }

    public RetrievedCapability withCryptree(CryptreeNode fileAccess) {
        return new RetrievedCapability(capability, fileAccess);
    }

    public RetrievedCapability withHash(Multihash newHash) {
        return new RetrievedCapability(capability, fileAccess.withHash(newHash));
    }
}
