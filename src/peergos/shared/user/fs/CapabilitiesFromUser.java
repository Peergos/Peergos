package peergos.shared.user.fs;

import peergos.shared.cbor.CborObject;
import peergos.shared.cbor.Cborable;

import java.util.*;
import java.util.stream.*;

public class CapabilitiesFromUser implements Cborable {

    private final long recordsRead;
    private final List<RetrievedCapability> retrievedCapabilities;

    public CapabilitiesFromUser(long recordsRead, List<RetrievedCapability> retrievedCapabilities) {
        this.recordsRead = recordsRead;
        this.retrievedCapabilities = retrievedCapabilities;
    }

    public long getRecordsRead() {
        return recordsRead;
    }

    public List<RetrievedCapability> getRetrievedCapabilities() {
        return retrievedCapabilities;
    }

    @Override
    public CborObject toCbor() {
        Map<String, CborObject> cbor = new TreeMap<>();
        cbor.put("count", new CborObject.CborLong(recordsRead));
        cbor.put("caps", new CborObject.CborList(retrievedCapabilities));
        return CborObject.CborMap.build(cbor);
    }

    public static CapabilitiesFromUser fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("CapabilitiesFromUser cbor must be a Map! " + cbor);
        long count = ((CborObject.CborLong) (((CborObject.CborMap) cbor).values.get(new CborObject.CborString("count")))).value;
        List<RetrievedCapability> caps = ((CborObject.CborList)((CborObject.CborMap) cbor).values.get(new CborObject.CborString("caps")))
                .value.stream()
                .map(RetrievedCapability::fromCbor)
                .collect(Collectors.toList());
        return new CapabilitiesFromUser(count, caps);
    }

}
