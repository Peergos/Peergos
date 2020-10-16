package peergos.shared.social;

import peergos.shared.cbor.*;
import peergos.shared.user.*;

import java.util.*;

public class ProcessedCaps implements Cborable {
    public final int readCaps, writeCaps;
    public final long readCapBytes, writeCapBytes;

    public ProcessedCaps(int readCaps, int writeCaps, long readCapBytes, long writeCapBytes) {
        this.readCaps = readCaps;
        this.writeCaps = writeCaps;
        this.readCapBytes = readCapBytes;
        this.writeCapBytes = writeCapBytes;
    }

    public long totalBytes() {
        return readCapBytes + writeCapBytes;
    }

    public ProcessedCaps add(CapsDiff diff) {
        if (readCapBytes != diff.priorReadByteOffset)
            throw new IllegalStateException("Applying cap diff to wrong base");
        if (writeCapBytes != diff.priorWriteByteOffset)
            throw new IllegalStateException("Applying cap diff to wrong base");
        return new ProcessedCaps(
                readCaps + diff.newCaps.readCaps.getRetrievedCapabilities().size(),
                writeCaps + diff.newCaps.writeCaps.getRetrievedCapabilities().size(),
                diff.updatedReadBytes(),
                diff.updatedWriteBytes()
        );
    }

    public static ProcessedCaps empty() {
        return new ProcessedCaps(0, 0, 0L, 0L);
    }

    @Override
    public CborObject toCbor() {
        SortedMap<String, Cborable> state = new TreeMap<>();
        state.put("rc", new CborObject.CborLong(readCaps));
        state.put("wc", new CborObject.CborLong(writeCaps));
        state.put("rb", new CborObject.CborLong(readCapBytes));
        state.put("wb", new CborObject.CborLong(writeCapBytes));
        return CborObject.CborMap.build(state);
    }

    public static ProcessedCaps fromCbor(Cborable cbor) {
        if (!(cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor! " + cbor);
        CborObject.CborMap m = (CborObject.CborMap) cbor;

        int readCaps = (int) m.getLong("rc");
        int writeCaps = (int) m.getLong("wc");
        long readCapBytes = m.getLong("rb");
        long writeCapBytes = m.getLong("wb");
        return new ProcessedCaps(readCaps, writeCaps, readCapBytes, writeCapBytes);
    }
}
