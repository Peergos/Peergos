package peergos.shared.user.fs;

import peergos.shared.cbor.CborObject;
import peergos.shared.cbor.Cborable;

import java.util.*;
import java.util.stream.*;

public class CapabilitiesFromUser implements Cborable {

    private final long bytesRead;
    private final List<CapabilityWithPath> retrievedCapabilities;

    public CapabilitiesFromUser(long bytesRead, List<CapabilityWithPath> retrievedCapabilities) {
        this.bytesRead = bytesRead;
        this.retrievedCapabilities = retrievedCapabilities;
    }

    public long getBytesRead() {
        return bytesRead;
    }

    public List<CapabilityWithPath> getRetrievedCapabilities() {
        return retrievedCapabilities;
    }

    public static CapabilitiesFromUser empty() {
        return new CapabilitiesFromUser(0L, Collections.emptyList());
    }

    @Override
    public CborObject toCbor() {
        Map<String, Cborable> cbor = new TreeMap<>();
        cbor.put("bytes", new CborObject.CborLong(bytesRead));
        cbor.put("caps", new CborObject.CborList(retrievedCapabilities));
        return CborObject.CborMap.build(cbor);
    }

    public static CapabilitiesFromUser fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("CapabilitiesFromUser cbor must be a Map! " + cbor);
        CborObject.CborMap m = (CborObject.CborMap) cbor;
        long bytesRead = m.getLong("bytes");
        List<CapabilityWithPath> caps = m.getList("caps")
                .value.stream()
                .map(CapabilityWithPath::fromCbor)
                .collect(Collectors.toList());
        return new CapabilitiesFromUser(bytesRead, caps);
    }

}
