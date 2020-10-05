package peergos.shared.user;

public class CapsDiff {
    public final long priorReadByteOffset, priorWriteByteOffset;
    public final FriendSourcedTrieNode.ReadAndWriteCaps newCaps;

    public CapsDiff(long priorReadByteOffset, long priorWriteByteOffset, FriendSourcedTrieNode.ReadAndWriteCaps newCaps) {
        this.priorReadByteOffset = priorReadByteOffset;
        this.priorWriteByteOffset = priorWriteByteOffset;
        this.newCaps = newCaps;
    }

    public boolean isEmpty() {
        return newCaps.readCaps.getBytesRead() == 0 && newCaps.writeCaps.getBytesRead() == 0;
    }

    public long updatedReadBytes() {
        return priorReadByteOffset + newCaps.readCaps.getBytesRead();
    }

    public long updatedWriteBytes() {
        return priorWriteByteOffset + newCaps.writeCaps.getBytesRead();
    }

    public long priorBytes() {
        return priorReadByteOffset + priorWriteByteOffset;
    }
}
