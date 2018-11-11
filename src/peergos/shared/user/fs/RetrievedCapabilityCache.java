package peergos.shared.user.fs;

import peergos.shared.cbor.CborObject;
import peergos.shared.cbor.Cborable;
import peergos.shared.util.DataSink;
import peergos.shared.util.DataSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class RetrievedCapabilityCache implements Cborable {

    private final long recordsRead;
    private final List<RetrievedCapability> retrievedCapabilities;


    public RetrievedCapabilityCache(long recordsRead, List<RetrievedCapability> retrievedCapabilities) {
        this.recordsRead = recordsRead;
        this.retrievedCapabilities = retrievedCapabilities;
    }

    public long getRecordsRead() {
        return recordsRead;
    }

    public List<RetrievedCapability> getRetrievedCapabilities() {
        return retrievedCapabilities;
    }

    public byte[] serialize() {
        DataSink sink = new DataSink();
        sink.writeLong(recordsRead);
        sink.writeInt(retrievedCapabilities.size());
        retrievedCapabilities.forEach(entry -> sink.writeArray(entry.serialize()));
        return sink.toByteArray();
    }

    public static RetrievedCapabilityCache deserialize(byte[] raw) {
        try {
            DataSource source = new DataSource(raw);
            long recordsReadCount = source.readLong();
            int count = source.readInt();

            List<RetrievedCapability> capabilities = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                RetrievedCapability entry = RetrievedCapability.fromByteArray(source.readArray());
                capabilities.add(entry);
            }
            return new RetrievedCapabilityCache(recordsReadCount, capabilities);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public CborObject toCbor() {
        return new CborObject.CborByteArray(serialize());
    }

    public static RetrievedCapabilityCache fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborByteArray))
            throw new IllegalStateException("RetrievedCapabilityCache cbor must be a byte[]! " + cbor);
        return deserialize(((CborObject.CborByteArray) cbor).value);
    }

}
