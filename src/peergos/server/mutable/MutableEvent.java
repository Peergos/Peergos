package peergos.server.mutable;

import peergos.shared.crypto.hash.*;

/** This propagates a change in a mutable pointer's target
 *
 */
public class MutableEvent {

    public final PublicKeyHash writer;
    public final byte[] writerSignedBtreeRootHash;

    public MutableEvent(PublicKeyHash writer, byte[] writerSignedBtreeRootHash) {
        this.writer = writer;
        this.writerSignedBtreeRootHash = writerSignedBtreeRootHash;
    }
}
