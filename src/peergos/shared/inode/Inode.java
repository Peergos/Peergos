package peergos.shared.inode;

import peergos.shared.cbor.*;

import java.util.*;

public class Inode implements Cborable, Comparable<Inode> {
    public final long inode;
    public final PathElement name;

    public Inode(long inode, PathElement name) {
        if (inode < 0)
            throw new IllegalStateException("Inode must be positive!");
        this.inode = inode;
        this.name = name;
    }

    public Inode(long inode, String name) {
        this(inode, new PathElement(name));
    }

    @Override
    public int compareTo(Inode inode) {
        return name.name.compareTo(inode.name.name);
    }

    public InodeCap withoutCap() {
        return new InodeCap(this, Optional.empty());
    }

    public String toString() {
        return inode + "/" + name;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Inode inode1 = (Inode) o;
        return inode == inode1.inode &&
                name.equals(inode1.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(inode, name);
    }

    @Override
    public CborObject toCbor() {
        SortedMap<String, Cborable> state = new TreeMap<>();
        state.put("i", new CborObject.CborLong(inode));
        state.put("n", new CborObject.CborString(name.name));
        return CborObject.CborMap.build(state);
    }

    public static Inode fromCbor(Cborable cbor) {
        if (!(cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor!");
        CborObject.CborMap m = (CborObject.CborMap) cbor;
        long inode = m.getLong("i");
        String name = m.getString("n");
        return new Inode(inode, new PathElement(name));
    }
}
