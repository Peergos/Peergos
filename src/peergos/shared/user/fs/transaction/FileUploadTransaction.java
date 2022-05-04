package peergos.shared.user.fs.transaction;

import jsinterop.annotations.JsMethod;
import peergos.shared.NetworkAccess;
import peergos.shared.cbor.CborObject;
import peergos.shared.cbor.Cborable;
import peergos.shared.crypto.SigningPrivateKeyAndPublicHash;
import peergos.shared.crypto.hash.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.*;

public class FileUploadTransaction implements Transaction {
    private final long startTimeEpochMillis;
    private final String path, name;
    private final PublicKeyHash owner;
    private final SigningPrivateKeyAndPublicHash writer;
    private final Location firstChunk;
    private final long size;
    private final byte[] streamSecret;

    public FileUploadTransaction(long startTimeEpochMillis,
                                 String path,
                                 String name,
                                 SigningPrivateKeyAndPublicHash writer,
                                 Location firstChunk,
                                 long size,
                                 byte[] streamSecret) {
        this.startTimeEpochMillis = startTimeEpochMillis;
        this.path = path;
        this.name = name;
        this.writer = writer;
        this.firstChunk = firstChunk;
        this.size = size;
        this.streamSecret = streamSecret;
        this.owner = firstChunk.owner;
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

    private CompletableFuture<Snapshot> clear(Snapshot version, Committer committer, NetworkAccess networkAccess, Location location) {
        return networkAccess.deleteChunkIfPresent(version, committer, location.owner, writer, location.getMapKey());
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
        return "" + path.hashCode();
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

    @Override
    public CborObject toCbor() {
        Map<String, Cborable> map = new HashMap<>();
        map.put("type", new CborObject.CborString(Type.FILE_UPLOAD.name()));
        map.put("path", new CborObject.CborString(path));
        map.put("startTimeEpochMs", new CborObject.CborLong(startTimeEpochMillis()));
        map.put("owner", owner);
        map.put("writer", writer);
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

        return new FileUploadTransaction(
                map.getLong("startTimeEpochMs"),
                map.getString("path"),
                filename,
                writer,
                new Location(owner, writer.publicKeyHash, map.getByteArray("mapKey")),
                map.getLong("size"),
                map.getByteArray("streamSecret"));
    }
}
