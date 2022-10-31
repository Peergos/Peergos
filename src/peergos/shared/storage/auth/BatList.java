package peergos.shared.storage.auth;

import peergos.shared.cbor.*;

import java.util.*;

public class BatList implements Cborable {

    public final List<BatWithId> bats;

    public BatList(List<BatWithId> bats) {
        this.bats = bats;
    }

    @Override
    public CborObject toCbor() {
        return new CborObject.CborList(bats);
    }

    public static BatList fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborList))
            throw new IllegalStateException("Invalid cbor for bat list: " + cbor);

        return new BatList(((CborObject.CborList) cbor).map(BatWithId::fromCbor));
    }
}
