package peergos.shared.user.fs;

import peergos.shared.cbor.CborObject;
import peergos.shared.cbor.Cborable;
import peergos.shared.util.DataSink;
import peergos.shared.util.DataSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class CapabilityCache implements Cborable {

    private final List<Capability> capabilities;

    public CapabilityCache() {
        this.capabilities = new ArrayList<>();
    }

    public CapabilityCache(List<Capability> capabilities) {
        this.capabilities = capabilities;
    }

    public byte[] serialize() {
        DataSink sink = new DataSink();
        sink.writeInt(capabilities.size());
        capabilities.forEach(entry -> sink.writeArray(entry.serialize()));
        return sink.toByteArray();
    }

    public static CapabilityCache deserialize(byte[] raw) {
        try {
            DataSource source = new DataSource(raw);
            int count = source.readInt();

            List<Capability> capabilities = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                Capability entry = Capability.fromByteArray(source.readArray());
                capabilities.add(entry);
            }
            return new CapabilityCache(capabilities);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public CborObject toCbor() {
        return new CborObject.CborByteArray(serialize());
    }

    public static CapabilityCache fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborByteArray))
            throw new IllegalStateException("CapabilityCache cbor must be a byte[]! " + cbor);
        return deserialize(((CborObject.CborByteArray) cbor).value);
    }

}
