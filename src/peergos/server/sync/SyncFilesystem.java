package peergos.server.sync;

import peergos.shared.cbor.CborObject;
import peergos.shared.cbor.Cborable;
import peergos.shared.crypto.symmetric.SymmetricKey;
import peergos.shared.storage.auth.Bat;
import peergos.shared.user.fs.*;
import peergos.shared.util.Triple;
import peergos.shared.Crypto;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.stream.Stream;

public interface SyncFilesystem {

    long totalSpace() throws IOException;

    long freeSpace() throws IOException;

    String getRoot();

    Path resolve(String p);

    boolean exists(Path p);

    void mkdirs(Path p);

    void delete(Path p);

    void bulkDelete(Path dir, Set<String> children);

    void moveTo(Path src, Path target);

    long getLastModified(Path p);

    void setModificationTime(Path p, long t);

    void setHash(Path p, HashTree hashTree, long fileSize);

    void setHashes(List<Triple<String, FileWrapper, HashTree>> toUpdate);

    long size(Path p);

    void truncate(Path p, long size) throws IOException;

    class PartialUploadProps implements Cborable {
        public final SymmetricKey baseKey, dataKey, writeKey;
        public final byte[] streamSecret;
        public final Bat firstChunkBat;
        public final byte[] firstChunkMapKey;

        public PartialUploadProps(SymmetricKey baseKey,
                                  SymmetricKey dataKey,
                                  SymmetricKey writeKey,
                                  byte[] streamSecret,
                                  Bat firstChunkBat,
                                  byte[] firstChunkMapKey) {
            this.baseKey = baseKey;
            this.dataKey = dataKey;
            this.writeKey = writeKey;
            this.streamSecret = streamSecret;
            this.firstChunkBat = firstChunkBat;
            this.firstChunkMapKey = firstChunkMapKey;
        }

        @Override
        public CborObject toCbor() {
            SortedMap<String, Cborable> state = new TreeMap<>();
            state.put("b", baseKey.toCbor());
            state.put("d", dataKey.toCbor());
            state.put("w", writeKey.toCbor());
            state.put("s", new CborObject.CborByteArray(streamSecret));
            state.put("ib", firstChunkBat.toCbor());
            state.put("m", new CborObject.CborByteArray(firstChunkMapKey));
            return CborObject.CborMap.build(state);
        }

        public static PartialUploadProps fromCbor(Cborable cbor) {
            if (! (cbor instanceof CborObject.CborMap))
                throw new IllegalStateException("Invalid cbor for PartialUploadProps! " + cbor);
            CborObject.CborMap m = (CborObject.CborMap) cbor;
            SymmetricKey baseKey = m.get("b", SymmetricKey::fromCbor);
            SymmetricKey dataKey = m.get("d", SymmetricKey::fromCbor);
            SymmetricKey writeKey = m.get("w", SymmetricKey::fromCbor);
            byte[] streamSecret = m.getByteArray("s");
            Bat initialBat = m.get("ib", Bat::fromCbor);
            byte[] initialMapKey = m.getByteArray("m");
            return new PartialUploadProps(baseKey, dataKey, writeKey, streamSecret, initialBat, initialMapKey);
        }

        static PartialUploadProps random(Crypto crypto) {
            SymmetricKey baseKey = SymmetricKey.random();
            SymmetricKey dataKey = SymmetricKey.random();
            SymmetricKey writeKey = SymmetricKey.random();
            byte[] streamSecret = crypto.random.randomBytes(32);
            Bat firstChunkBat = Bat.random(crypto.random);
            byte[] firstChunkMapKey = crypto.random.randomBytes(32);
            return new PartialUploadProps(baseKey, dataKey, writeKey, streamSecret, firstChunkBat, firstChunkMapKey);
        }
    }

    void setBytes(Path p,
                  long fileOffset,
                  AsyncReader data,
                  long size,
                  Optional<HashTree> hash,
                  Optional<LocalDateTime> modificationTime,
                  Optional<Thumbnail> thumbnail,
                  PartialUploadProps props,
                  Consumer<String> progress) throws IOException;

    AsyncReader getBytes(Path p, long fileOffset) throws IOException;

    void uploadSubtree(Stream<FileWrapper.FolderUploadProperties> directories);

    Optional<Thumbnail> getThumbnail(Path p);

    HashTree hashFile(Path p, Optional<FileWrapper> meta, String relativePath, SyncState syncedState);

    void applyToSubtree(Consumer<FileProps> file, Consumer<FileProps> dir) throws IOException;

    class FileProps {
        public final String relPath;
        public final long modifiedTime;
        public final long size;
        public final Optional<FileWrapper> meta;

        public FileProps(String relPath, long modifiedTime, long size, Optional<FileWrapper> meta) {
            this.relPath = relPath;
            this.modifiedTime = modifiedTime;
            this.size = size;
            this.meta = meta;
        }
    }
}
