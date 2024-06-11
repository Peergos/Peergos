package peergos.shared.user.fs.transaction;

import jsinterop.annotations.JsMethod;
import peergos.shared.NetworkAccess;
import peergos.shared.cbor.CborObject;
import peergos.shared.cbor.Cborable;
import peergos.shared.crypto.SigningPrivateKeyAndPublicHash;
import peergos.shared.crypto.hash.*;
import peergos.shared.crypto.symmetric.*;
import peergos.shared.storage.*;
import peergos.shared.storage.auth.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.*;

public class FileUploadTransaction implements Transaction {
    private final long startTimeEpochMillis;
    private final String path, name;
    private final PublicKeyHash owner;
    private final SigningPrivateKeyAndPublicHash writer;
    private final Location firstChunk;
    public final FileProperties props;
    public final Optional<Bat> firstBat;
    public final SymmetricKey baseKey, dataKey, writeKey;
    private final long size;
    private final byte[] streamSecret;

    public FileUploadTransaction(long startTimeEpochMillis,
                                 String path,
                                 String name,
                                 FileProperties props,
                                 SigningPrivateKeyAndPublicHash writer,
                                 Location firstChunk,
                                 Optional<Bat> firstBat,
                                 long size,
                                 SymmetricKey baseKey,
                                 SymmetricKey dataKey,
                                 SymmetricKey writeKey,
                                 byte[] streamSecret) {
        this.startTimeEpochMillis = startTimeEpochMillis;
        this.path = path;
        this.name = name;
        this.props = props;
        this.writer = writer;
        this.firstChunk = firstChunk;
        this.firstBat = firstBat;
        this.size = size;
        this.baseKey = baseKey;
        this.dataKey = dataKey;
        this.writeKey = writeKey;
        this.streamSecret = streamSecret;
        this.owner = firstChunk.owner;
    }

    public boolean isLegacy() {
        return props == null;
    }

    public long size() {
        return size;
    }

    public byte[] streamSecret() {
        return streamSecret;
    }

    public Location getFirstLocation() {
        return firstChunk;
    }

    public PublicKeyHash writer() {
        return writer.publicKeyHash;
    }

    public byte[] firstMapKey() {
        return firstChunk.getMapKey();
    }

    private CompletableFuture<Snapshot> clear(Snapshot version, Committer committer, NetworkAccess networkAccess, Location location) {
        return IpfsTransaction.call(owner,
                tid -> version.withWriter(owner, writer.publicKeyHash, networkAccess)
                        .thenCompose(v -> v.contains(writer.publicKeyHash) ?
                                networkAccess.deleteChunkIfPresent(v, committer, location.owner, writer, location.getMapKey(), tid) :
                                Futures.of(v)), networkAccess.dhtClient);
    }

    public CompletableFuture<Snapshot> clear(Snapshot version, Committer committer, NetworkAccess network, Hasher h) {
        return Futures.reduceAll(LongStream.range(0, (size + Chunk.MAX_SIZE - 1)/Chunk.MAX_SIZE).boxed(),
                        new Pair<>(version, firstChunk),
                        (p, i) -> clear(p.left, committer, network, p.right)
                                .thenCompose(s -> FileProperties.calculateNextMapKey(streamSecret, p.right.getMapKey(), Optional.empty(), h)
                                        .thenApply(mapKey -> new Pair<>(s, firstChunk.withMapKey(mapKey.left)))),
                        (a, b) -> b)
                .thenApply(p -> p.left);
    }

    @JsMethod
    public String getPath() {
        return path;
    }

    @Override
    public String name() {
        return name;
    }

    @JsMethod
    public String targetFilename() {
        Path path = PathUtil.get(this.path);
        return path.getFileName().toString();
    }

    @Override
    public long startTimeEpochMillis() {
        return startTimeEpochMillis;
    }

    public LocalDateTime startTime() {
        return LocalDateTime.ofEpochSecond(startTimeEpochMillis / 1000, (int)(startTimeEpochMillis % 1000)* 1_000_000, ZoneOffset.UTC);
    }

    public WritableAbsoluteCapability writeCap() {
        return new WritableAbsoluteCapability(owner, writer.publicKeyHash, firstMapKey(), firstBat, baseKey, writeKey);
    }

    @Override
    public CborObject toCbor() {
        Map<String, Cborable> map = new HashMap<>();
        map.put("type", new CborObject.CborString(Type.FILE_UPLOAD.name()));
        map.put("path", new CborObject.CborString(path));
        map.put("startTimeEpochMs", new CborObject.CborLong(startTimeEpochMillis()));
        map.put("owner", owner);
        map.put("writer", writer);
        map.put("baseKey", baseKey);
        map.put("dataKey", dataKey);
        map.put("writeKey", writeKey);
        map.put("props", props);
        firstBat.ifPresent(b -> map.put("firstBat", b));
        map.put("mapKey", new CborObject.CborByteArray(firstChunk.getMapKey()));
        map.put("streamSecret", new CborObject.CborByteArray(streamSecret));
        map.put("size", new CborObject.CborLong(size));

        return CborObject.CborMap.build(map);
    }

    static Transaction fromCbor(CborObject.CborMap map, String filename) {
        Type type = Type.valueOf(map.getString("type"));
        boolean isFileUpload = type.equals(Type.FILE_UPLOAD);
        if (!isFileUpload)
            throw new IllegalStateException("Cannot parse transaction: wrong type " + type);

        PublicKeyHash owner = map.getObject("owner", PublicKeyHash::fromCbor);
        SigningPrivateKeyAndPublicHash writer = map.getObject("writer", SigningPrivateKeyAndPublicHash::fromCbor);

        if (! map.containsKey("streamSecret"))
            throw new IllegalStateException("Invalid upload transaction");

        long startTimeEpochMs = map.getLong("startTimeEpochMs");
        String path = map.getString("path");
        long size = map.getLong("size");
        byte[] streamSecrets = map.getByteArray("streamSecret");
        if (! map.containsKey("props")) // legacy transactions have enough information to delete, but not to continue
            return new FileUploadTransaction(
                    startTimeEpochMs,
                    path,
                    filename,
                    null,
                    writer,
                    new Location(owner, writer.publicKeyHash, map.getByteArray("mapKey")),
                    null,
                    size,
                    null,
                    null,
                    null,
                    streamSecrets);

        return new FileUploadTransaction(
                startTimeEpochMs,
                path,
                filename,
                map.get("props", FileProperties::fromCbor),
                writer,
                new Location(owner, writer.publicKeyHash, map.getByteArray("mapKey")),
                map.getOptional("firstBat", Bat::fromCbor),
                size,
                map.getObject("baseKey", SymmetricKey::fromCbor),
                map.getObject("dataKey", SymmetricKey::fromCbor),
                map.getObject("writeKey", SymmetricKey::fromCbor),
                streamSecrets);
    }
}
