package peergos.shared.inode;

import peergos.shared.cbor.*;
import peergos.shared.user.fs.*;

import java.util.*;

public class InodeCap implements Cborable {
    public final Inode inode;
    public final Optional<AbsoluteCapability> cap;

    public InodeCap(Inode inode, Optional<AbsoluteCapability> cap) {
        this.inode = inode;
        this.cap = cap;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        InodeCap inodeCap = (InodeCap) o;
        return inode.equals(inodeCap.inode) &&
                cap.equals(inodeCap.cap);
    }

    @Override
    public int hashCode() {
        return Objects.hash(inode, cap);
    }

    @Override
    public CborObject toCbor() {
        SortedMap<String, Cborable> state = new TreeMap<>();
        state.put("i", inode.toCbor());
        cap.map(c -> state.put("c", c.toCbor()));
        return CborObject.CborMap.build(state);
    }

    public static InodeCap fromCbor(Cborable cbor) {
        if (!(cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor!");
        CborObject.CborMap m = (CborObject.CborMap) cbor;
        Inode inode = m.get("i", Inode::fromCbor);
        Optional<AbsoluteCapability> cap = m.getOptional("c", AbsoluteCapability::fromCbor);
        return new InodeCap(inode, cap);
    }
}
