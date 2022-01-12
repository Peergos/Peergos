package peergos.shared.user.fs;

import jsinterop.annotations.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.crypto.symmetric.SymmetricKey;
import peergos.shared.util.*;

import java.util.*;

public class Location implements Cborable {

    @JsProperty
    public final PublicKeyHash owner, writer;
    private final byte[] mapKey;

    public Location(PublicKeyHash owner, PublicKeyHash writer, byte[] mapKey) {
        if (mapKey.length != RelativeCapability.MAP_KEY_LENGTH)
            throw  new IllegalArgumentException("map key length "+ mapKey.length +" is not "+ RelativeCapability.MAP_KEY_LENGTH);
        this.owner = owner;
        this.writer = writer;
        this.mapKey = mapKey;
    }

    public CborObject toCbor() {
        return new CborObject.CborList(Arrays.asList(
                owner.toCbor(),
                writer.toCbor(),
                new CborObject.CborByteArray(mapKey)
        ));
    }

    @JsMethod
    public byte[] getMapKey() {
        return Arrays.copyOf(mapKey, mapKey.length);
    }

    public String toString() {
        return new ByteArrayWrapper(mapKey).toString();
    }

    public static Location fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborList))
            throw new IllegalStateException("Incorrect cbor for Location: " + cbor);
        List<? extends Cborable> values = ((CborObject.CborList) cbor).value;
        return new Location(
                PublicKeyHash.fromCbor(values.get(0)),
                PublicKeyHash.fromCbor(values.get(1)),
                ((CborObject.CborByteArray) values.get(2)).value);
    }

    public Location withMapKey(byte[] newMapKey) {
        return new Location(owner, writer, newMapKey);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Location location = (Location) o;

        if (owner != null ? !owner.equals(location.owner) : location.owner != null) return false;
        if (writer != null ? !writer.equals(location.writer) : location.writer != null) return false;
        return Arrays.equals(mapKey, location.mapKey);

    }

    @Override
    public int hashCode() {
        int result = owner != null ? owner.hashCode() : 0;
        result = 31 * result + (writer != null ? writer.hashCode() : 0);
        result = 31 * result + Arrays.hashCode(mapKey);
        return result;
    }
}
