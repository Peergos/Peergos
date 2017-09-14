package peergos.shared.user.fs;

import peergos.shared.merklebtree.*;

public class LocatedEncryptedChunk {
    public final Location location;
    public final MaybeMultihash existingHash;
    public final EncryptedChunk chunk;
    public final byte[] nonce;

    public LocatedEncryptedChunk(Location location, MaybeMultihash existingHash, EncryptedChunk chunk, byte[] nonce) {
        this.location = location;
        this.existingHash = existingHash;
        this.chunk = chunk;
        this.nonce = nonce;
    }
}
