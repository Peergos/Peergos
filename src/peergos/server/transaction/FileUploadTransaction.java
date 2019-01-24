package peergos.server.transaction;

import peergos.shared.NetworkAccess;
import peergos.shared.cbor.CborObject;
import peergos.shared.cbor.Cborable;
import peergos.shared.crypto.SigningPrivateKeyAndPublicHash;
import peergos.shared.crypto.hash.PublicKeyHash;
import peergos.shared.storage.TransactionId;
import peergos.shared.user.fs.Location;
import peergos.shared.user.fs.cryptree.CryptreeNode;
import peergos.shared.util.Futures;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;

public class FileUploadTransaction implements Transaction {
    private final long startTimeEpochMillis, timeoutMs;
    private final String path;
    //  common to whole file
    PublicKeyHash owner;
    SigningPrivateKeyAndPublicHash writer;
    private List<Location> locations;

    public FileUploadTransaction(long startTimeEpochMillis,
                                 long timeoutMs, String path,
                                 PublicKeyHash owner,
                                 SigningPrivateKeyAndPublicHash writer,
                                 List<Location> locations) {
        this.startTimeEpochMillis = startTimeEpochMillis;
        this.timeoutMs = timeoutMs;
        this.path = path;
        this.owner = owner;
        this.writer = writer;
        this.locations = locations;
        ensureValid();

    }

    private boolean isValid(Location location) {
        return location.owner.equals(owner) && location.writer.equals(writer.publicKeyHash);
    }

    private void ensureValid() {
        Optional<Location> invalid = locations.stream()
                .filter(e -> !isValid(e))
                .findFirst();
        if (invalid.isPresent())
            throw new IllegalStateException("Invalid location " + invalid.get());
    }

    private CompletableFuture<Boolean> clear(NetworkAccess networkAccess, Location location) {
        return networkAccess.getMetadata(location)
                .thenCompose(mOpt -> {
                    if (!mOpt.isPresent()) {
                        return CompletableFuture.completedFuture(true);
                    }
                    CryptreeNode metadata = mOpt.get();
                    TransactionId ipfsTxionId = TransactionId.build(name());
                    return networkAccess.deleteChunk(metadata, location.owner, location.getMapKey(), writer, ipfsTxionId)
                            .thenApply(e -> true);
                });
    }

    public CompletableFuture<Boolean> clear(NetworkAccess networkAccess) {
        List<CompletableFuture<Boolean>> futures = locations.stream().map(loc -> clear(networkAccess, loc))
                .collect(Collectors.toList());
        return Futures.combineAll(futures)
                .thenApply(e -> true);
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
    public long timeoutMs() {
        return timeoutMs;
    }

    @Override
    public CborObject toCbor() {
        Map<String, Cborable> map = new HashMap<>();
        map.put("type", new CborObject.CborString(Type.FILE_UPLOAD.name()));
        map.put("path", new CborObject.CborString(path));
        map.put("timeoutMs", new CborObject.CborLong(timeoutMs()));
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


    static Transaction deserialize(CborObject.CborMap map) {
        Type type = Type.valueOf(map.get("type").toString());
        boolean isFileUpload = type.equals(Type.FILE_UPLOAD);
        if (!isFileUpload)
            throw new IllegalStateException("Cannot deserialize transaction: wrong type " + type);

        PublicKeyHash owner = map.getObject("owner", PublicKeyHash::fromCbor);
        SigningPrivateKeyAndPublicHash writer = map.getObject("writer", SigningPrivateKeyAndPublicHash::fromCbor);
        List<byte[]> mapKeys = map.getList("mapKeys", (cborable -> ((CborObject.CborByteArray) cborable).value));

        List<Location> locations = mapKeys.stream()
                .map(mapKey -> new Location(owner, writer.publicKeyHash, mapKey))
                .collect(Collectors.toList());

        return new FileUploadTransaction(
                map.getLong("startTimeEpochMs"),
                map.getLong("timeoutMs"),
                map.getString("path"),
                owner,
                writer,
                locations);
    }
}
