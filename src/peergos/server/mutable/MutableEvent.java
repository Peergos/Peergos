package peergos.server.mutable;

import peergos.shared.crypto.hash.*;

public class MutableEvent {

    public final PublicKeyHash writer;
    public final byte[] writerSignedBtreeRootHash;

    public MutableEvent(PublicKeyHash writer, byte[] writerSignedBtreeRootHash) {
        this.writer = writer;
        this.writerSignedBtreeRootHash = writerSignedBtreeRootHash;
    }
}
