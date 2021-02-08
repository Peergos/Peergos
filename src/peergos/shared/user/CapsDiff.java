package peergos.shared.user;

import peergos.shared.user.fs.*;

import java.util.*;
import java.util.stream.*;

public class CapsDiff {
    public final long priorReadByteOffset, priorWriteByteOffset;
    public final ReadAndWriteCaps newCaps;
    public final Map<String, CapsDiff> groupDiffs;

    public CapsDiff(long priorReadByteOffset,
                    long priorWriteByteOffset,
                    ReadAndWriteCaps newCaps,
                    Map<String, CapsDiff> groupDiffs) {
        this.priorReadByteOffset = priorReadByteOffset;
        this.priorWriteByteOffset = priorWriteByteOffset;
        this.newCaps = newCaps;
        this.groupDiffs = groupDiffs;
    }

    public int readCapCount() {
        return newCaps.readCaps.getRetrievedCapabilities().size();
    }

    public int writeCapCount() {
        return newCaps.writeCaps.getRetrievedCapabilities().size();
    }

    public CapsDiff flatten() {
        Map<String, CapsDiff> flattenedGroups = groupDiffs.entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey(), e -> e.getValue().flatten()));
        return new CapsDiff(updatedReadBytes(), updatedWriteBytes(), ReadAndWriteCaps.empty(), flattenedGroups);
    }

    public List<CapabilityWithPath> getNewCaps() {
        Stream<CapabilityWithPath> direct = Stream.concat(
                newCaps.readCaps.getRetrievedCapabilities().stream(),
                newCaps.writeCaps.getRetrievedCapabilities().stream());
        return Stream.concat(direct, groupDiffs.values().stream().flatMap(d -> d.getNewCaps().stream()))
                .collect(Collectors.toList());
    }

    public CapsDiff mergeGroups(CapsDiff other) {
        HashMap<String, CapsDiff> combined = new HashMap<>(groupDiffs);
        combined.putAll(other.groupDiffs);
        return new CapsDiff(priorReadByteOffset, priorWriteByteOffset, newCaps,
                combined);
    }

    public boolean isEmpty() {
        return newCaps.readCaps.getBytesRead() == 0 &&
                newCaps.writeCaps.getBytesRead() == 0 &&
                groupDiffs.values().stream().allMatch(CapsDiff::isEmpty);
    }

    public long updatedReadBytes() {
        return priorReadByteOffset + newCaps.readCaps.getBytesRead();
    }

    public long updatedWriteBytes() {
        return priorWriteByteOffset + newCaps.writeCaps.getBytesRead();
    }

    public long priorBytes() {
        return priorReadByteOffset + priorWriteByteOffset;
    }

    public static CapsDiff empty() {
        return new CapsDiff(0, 0, ReadAndWriteCaps.empty(), Collections.emptyMap());
    }

    public static class ReadAndWriteCaps {
        public final CapabilitiesFromUser readCaps, writeCaps;

        public ReadAndWriteCaps(CapabilitiesFromUser readCaps, CapabilitiesFromUser writeCaps) {
            this.readCaps = readCaps;
            this.writeCaps = writeCaps;
        }

        public static ReadAndWriteCaps empty() {
            CapabilitiesFromUser empty = new CapabilitiesFromUser(0, Collections.emptyList());
            return new ReadAndWriteCaps(empty, empty);
        }
    }
}
