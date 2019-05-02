package peergos.shared.user.fs.transaction;

import peergos.shared.NetworkAccess;
import peergos.shared.cbor.CborObject;
import peergos.shared.cbor.Cborable;
import peergos.shared.crypto.SigningPrivateKeyAndPublicHash;
import peergos.shared.crypto.hash.PublicKeyHash;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.storage.IpfsTransaction;
import peergos.shared.storage.TransactionId;
import peergos.shared.user.*;
import peergos.shared.user.fs.Location;
import peergos.shared.user.fs.cryptree.CryptreeNode;
import peergos.shared.util.Futures;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;

public class FileUploadTransaction implements Transaction {
    private final long startTimeEpochMillis;
    private final String path;
    //  common to whole file
    private final PublicKeyHash owner;
    private final SigningPrivateKeyAndPublicHash writer;
    private final List<Location> locations;

    public FileUploadTransaction(long startTimeEpochMillis,
                                 String path,
                                 SigningPrivateKeyAndPublicHash writer,
                                 List<Location> locations) {
        ensureValid(locations, writer);

        this.startTimeEpochMillis = startTimeEpochMillis;
        this.path = path;
        this.writer = writer;
        this.locations = locations;
        this.owner = locations.get(0).owner;
    }

    private void ensureValid(List<Location> locations, SigningPrivateKeyAndPublicHash writer) {
        long distinctOWners = locations.stream().map(e -> e.owner).distinct().count();
        if (distinctOWners != 1)
            throw new IllegalStateException("All locations for transaction must have the same owner");
        long distinctWriters = locations.stream().map(e -> e.writer).distinct().count();
        if (distinctWriters != 1)
            throw new IllegalStateException("All locations for transaction must have the same writer");
        if (! locations.get(0).writer.equals(writer.publicKeyHash))
            throw new IllegalStateException("All locations for transaction must have the same writer as the supplied signing pair");
    }

    public List<Location> getLocations() {
        return locations;
    }

    private CompletableFuture<Snapshot> clear(Snapshot version, Committer committer, NetworkAccess networkAccess, Location location) {
        return networkAccess.deleteChunkIfPresent(version, committer, location.owner, writer, location.getMapKey());
    }

    public CompletableFuture<Snapshot> clear(Snapshot version, Committer committer, NetworkAccess network) {
        return Futures.reduceAll(locations, version, (s, loc) -> clear(s, committer, network, loc), (a, b) -> b);
    }

    @Override
    public String name() {
        return "" + path.hashCode();
    }

    @Override
    public long startTimeEpochMillis() {
        return startTimeEpochMillis;
    }

    @Override
    public CborObject toCbor() {
        Map<String, Cborable> map = new HashMap<>();
        map.put("type", new CborObject.CborString(Type.FILE_UPLOAD.name()));
        map.put("path", new CborObject.CborString(path));
        map.put("startTimeEpochMs", new CborObject.CborLong(startTimeEpochMillis()));
        map.put("owner", owner);
        map.put("writer", writer);
        CborObject.CborList mapKeys = new CborObject.CborList(
                locations.stream()
                        .map(e -> new CborObject.CborByteArray(e.getMapKey()))
                        .collect(Collectors.toList()));
        map.put("mapKeys", mapKeys);

        return CborObject.CborMap.build(map);
    }

    static Transaction fromCbor(CborObject.CborMap map) {
        Type type = Type.valueOf(map.getString("type"));
        boolean isFileUpload = type.equals(Type.FILE_UPLOAD);
        if (!isFileUpload)
            throw new IllegalStateException("Cannot parse transaction: wrong type " + type);

        PublicKeyHash owner = map.getObject("owner", PublicKeyHash::fromCbor);
        SigningPrivateKeyAndPublicHash writer = map.getObject("writer", SigningPrivateKeyAndPublicHash::fromCbor);
        List<byte[]> mapKeys = map.getList("mapKeys", (cborable -> ((CborObject.CborByteArray) cborable).value));

        List<Location> locations = mapKeys.stream()
                .map(mapKey -> new Location(owner, writer.publicKeyHash, mapKey))
                .collect(Collectors.toList());

        return new FileUploadTransaction(
                map.getLong("startTimeEpochMs"),
                map.getString("path"),
                writer,
                locations);
    }
}
