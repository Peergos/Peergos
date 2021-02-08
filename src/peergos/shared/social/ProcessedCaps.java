package peergos.shared.social;

import peergos.shared.cbor.*;
import peergos.shared.user.*;

import java.util.*;

/** Each of the users you follow have one of these serialized and stored in your cap cache for them and social feed.
 *
 */
public class ProcessedCaps implements Cborable {
    public final int readCaps, writeCaps;
    public final long readCapBytes, writeCapBytes;
    public final Map<String, ProcessedCaps> groups;

    public ProcessedCaps(int readCaps, int writeCaps, long readCapBytes, long writeCapBytes, Map<String, ProcessedCaps> groups) {
        this.readCaps = readCaps;
        this.writeCaps = writeCaps;
        this.readCapBytes = readCapBytes;
        this.writeCapBytes = writeCapBytes;
        this.groups = groups;
    }

    public ProcessedCaps add(CapsDiff diff) {
        if (readCapBytes != diff.priorReadByteOffset)
            throw new IllegalStateException("Applying cap diff to wrong base");
        if (writeCapBytes != diff.priorWriteByteOffset)
            throw new IllegalStateException("Applying cap diff to wrong base");

        HashMap<String, ProcessedCaps> updated = new HashMap<>(groups);
        for (Map.Entry<String, CapsDiff> e : diff.groupDiffs.entrySet()) {
            ProcessedCaps current = groups.get(e.getKey());
            CapsDiff gDiff = e.getValue();
            if (current == null) {
                updated.put(e.getKey(), new ProcessedCaps(gDiff.readCapCount(),
                        gDiff.writeCapCount(), gDiff.updatedReadBytes(), gDiff.updatedWriteBytes(), Collections.emptyMap()));
            } else {
                updated.put(e.getKey(), current.add(gDiff));
            }
        }
        return new ProcessedCaps(
                readCaps + diff.readCapCount(),
                writeCaps + diff.writeCapCount(),
                diff.updatedReadBytes(),
                diff.updatedWriteBytes(),
                updated
        );
    }

    public CapsDiff createGroupDiff(String name, CapsDiff diff) {
        Map<String, CapsDiff> groups = new HashMap<>();
        groups.put(name, diff);
        return new CapsDiff(readCapBytes, writeCapBytes, CapsDiff.ReadAndWriteCaps.empty(), groups);
    }

    public static ProcessedCaps empty() {
        return new ProcessedCaps(0, 0, 0L, 0L, Collections.emptyMap());
    }

    @Override
    public CborObject toCbor() {
        SortedMap<String, Cborable> state = new TreeMap<>();
        state.put("rc", new CborObject.CborLong(readCaps));
        state.put("wc", new CborObject.CborLong(writeCaps));
        state.put("rb", new CborObject.CborLong(readCapBytes));
        state.put("wb", new CborObject.CborLong(writeCapBytes));

        SortedMap<String, Cborable> groups = new TreeMap<>();
        for (Map.Entry<String, ProcessedCaps> e : this.groups.entrySet()) {
            groups.put(e.getKey(), e.getValue().toCbor());
        }
        state.put("g", CborObject.CborMap.build(groups));
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

        Map<String, ProcessedCaps> groups = m.getMap("g", c -> ((CborObject.CborString) c).value, ProcessedCaps::fromCbor);
        return new ProcessedCaps(readCaps, writeCaps, readCapBytes, writeCapBytes, groups);
    }
}
