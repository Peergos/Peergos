package peergos.shared.user;

import peergos.shared.crypto.hash.*;

public class MutableVersion {
    public final PublicKeyHash writer;
    public final CommittedWriterData base;

    public MutableVersion(PublicKeyHash writer, CommittedWriterData base) {
        this.writer = writer;
        this.base = base;
    }
}
