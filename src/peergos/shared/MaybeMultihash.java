package peergos.shared;

import peergos.shared.cbor.*;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.io.ipfs.Multihash;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

public class MaybeMultihash implements Cborable {
    private final Multihash hash;

    public MaybeMultihash(Multihash hash) {
        this.hash = hash;
    }

    public boolean isPresent() {
        return hash != null;
    }

    public Optional<Multihash> toOptional() {
        return isPresent() ? Optional.of(hash) : Optional.empty();
    }

    public <T> Optional<T> map(Function<Multihash, T> func) {
        return isPresent() ? Optional.of(func.apply(hash)) : Optional.empty();
    }

    public Multihash get() {
        if (! isPresent())
            throw new IllegalStateException("hash not present");
        return hash;
    }

    public CompletableFuture<Boolean> ifPresent(Function<Multihash, CompletableFuture<Boolean>> con) {
        if (isPresent())
            return con.apply(hash);
        return CompletableFuture.completedFuture(true);
    }

    public String toString() {
        return hash != null ? hash.toString() : "EMPTY";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        MaybeMultihash that = (MaybeMultihash) o;

        return hash != null ? hash.equals(that.hash) : that.hash == null;
    }

    @Override
    public int hashCode() {
        return hash != null ? hash.hashCode() : 0;
    }

    public static MaybeMultihash fromCbor(Cborable cbor) {
        if (cbor instanceof CborObject.CborNull)
            return MaybeMultihash.empty();

        if (! (cbor instanceof CborObject.CborByteArray))
            throw new IllegalStateException("Incorrect cbor for MaybeMultihash: " + cbor);
        return MaybeMultihash.of(Cid.cast(((CborObject.CborByteArray) cbor).value));
    }

    @Override
    public CborObject toCbor() {
        return isPresent() ? new CborObject.CborByteArray(hash.toBytes()) : new CborObject.CborNull();
    }

    private static MaybeMultihash EMPTY = new MaybeMultihash(null);

    public static MaybeMultihash empty() {
        return EMPTY;
    }

    public static MaybeMultihash of(Multihash hash) {
        return new MaybeMultihash(hash);
    }
}
