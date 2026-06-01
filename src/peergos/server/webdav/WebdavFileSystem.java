/*
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package peergos.server.webdav;

import org.peergos.util.Pair;
import peergos.server.UserService;
import peergos.server.storage.DeletableContentAddressedStorage;
import peergos.server.util.Logging;
import peergos.server.webdav.modeshape.webdav.ITransaction;
import peergos.server.webdav.modeshape.webdav.IWebdavStore;
import peergos.server.webdav.modeshape.webdav.StoredObject;
import peergos.server.webdav.modeshape.webdav.exceptions.WebdavException;
import peergos.server.Builder;
import peergos.server.Main;
import peergos.shared.Crypto;
import peergos.shared.NetworkAccess;
import peergos.shared.crypto.hash.Hasher;
import peergos.shared.crypto.hash.PublicKeyHash;
import peergos.shared.mutable.MutablePointers;
import peergos.shared.storage.ContentAddressedStorage;
import peergos.shared.user.CommittedWriterData;
import peergos.shared.user.UserContext;
import peergos.shared.user.fs.AsyncReader;
import peergos.shared.user.fs.FileWrapper;
import peergos.shared.user.fs.HashTree;
import peergos.shared.user.fs.HashTreeBuilder;
import peergos.shared.util.PathUtil;
import peergos.shared.util.Futures;

import java.io.*;
import java.net.URL;
import java.nio.file.Path;
import java.security.Principal;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Integration with Peergos File system
 * This class was based on org.modeshape.webdav.LocalFileSystemStore
 * which in turn was based on reference Implementation of WebdavStore
 * Originally by:
 * @author joa
 * @author re
 * @author hchiorea@redhat.com
 */
public class WebdavFileSystem implements IWebdavStore {

    private static final Logger LOG = Logging.LOG();
    private static final String PEERGOS_NS = "https://peergos.org/ns/dav";

    private final UserContext context;
    private volatile long cachedUsed = 0;
    private volatile long cachedQuota = 1024L * 1024 * 1024;
    private volatile long quotaCacheTime = 0;
    private static final long QUOTA_CACHE_TTL_MS = 60_000;

    public WebdavFileSystem(String username, String password, String peergosUrl) {
        Crypto crypto = Main.initCrypto();
        try {
            Optional<String> userAgent = Optional.of("Peergos-" + UserService.CURRENT_VERSION + "-webdav");
            NetworkAccess network = Builder.buildJavaNetworkAccess(new URL(peergosUrl),
                    peergosUrl.startsWith("https"), userAgent, Optional.empty()).join();
            context = UserContext.signIn(username, password, Main::getMfaResponseCLI, network, crypto).join();
        } catch (Exception ex) {
            LOG.log(Level.WARNING, ex, () -> "Unable to connect to Peergos account");
            throw new IllegalStateException("Unable to connect to Peergos account: ", ex);
        }
    }

    /**
     * Returns a snapshot of all writers visible in the current user's file tree as
     * { writerPublicKeyHash.toString() -> hex-encoded signed pointer bytes }.
     * Used by the macOS File Provider extension as a sync anchor to detect changes.
     */
    public Map<String, String> getWriterSnapshot() {
        PublicKeyHash owner = context.signer.publicKeyHash;
        ContentAddressedStorage dht = context.network.dhtClient;
        MutablePointers mutable = context.network.mutable;
        Hasher hasher = context.crypto.hasher;
        CommittedWriterData.Retriever retriever =
                (h, s) -> ContentAddressedStorage.getWriterData(owner, h, s, dht);
        Map<PublicKeyHash, byte[]> raw = getUserSnapshotRecursive(
                owner, owner, Collections.emptyMap(), mutable, dht, hasher, retriever).join();
        HexFormat hex = HexFormat.of();
        Map<String, String> result = new LinkedHashMap<>();
        raw.forEach((writer, bytes) -> result.put(writer.toString(), hex.formatHex(bytes)));
        return result;
    }

    private static CompletableFuture<Map<PublicKeyHash, byte[]>> getUserSnapshotRecursive(
            PublicKeyHash owner,
            PublicKeyHash writer,
            Map<PublicKeyHash, byte[]> alreadyDone,
            MutablePointers mutable,
            ContentAddressedStorage dht,
            Hasher hasher,
            CommittedWriterData.Retriever retriever) {
        return DeletableContentAddressedStorage.getDirectOwnedKeys(owner, writer, mutable, retriever, dht, hasher)
                .thenCompose(directOwned -> {
                    Set<PublicKeyHash> newKeys = directOwned.stream()
                            .filter(h -> !alreadyDone.containsKey(h))
                            .collect(Collectors.toSet());
                    Map<PublicKeyHash, byte[]> done = new HashMap<>(alreadyDone);
                    return mutable.getPointer(owner, writer).thenCompose(val -> {
                        if (val.isPresent())
                            done.put(writer, val.get());
                        BiFunction<Map<PublicKeyHash, byte[]>, PublicKeyHash,
                                CompletableFuture<Map<PublicKeyHash, byte[]>>> composer =
                                (a, w) -> getUserSnapshotRecursive(owner, w, a, mutable, dht, hasher, retriever)
                                        .thenApply(ws -> Stream.concat(
                                                ws.entrySet().stream().filter(e -> !a.containsKey(e.getKey())),
                                                a.entrySet().stream())
                                                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
                        return Futures.reduceAll(newKeys, done, composer,
                                (a, b) -> Stream.concat(
                                        a.entrySet().stream().filter(e -> !b.containsKey(e.getKey())),
                                        b.entrySet().stream())
                                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
                    });
                });
    }

    @Override
    public void destroy() {
    }

    @Override
    public ITransaction begin(Principal principal ) throws WebdavException {
        LOG.fine("PeergosFileSystem.begin()");
        return null;
    }

    @Override
    public void checkAuthentication( ITransaction transaction ) throws SecurityException {
        LOG.fine("PeergosFileSystem.checkAuthentication()");
    }

    @Override
    public void commit( ITransaction transaction ) throws WebdavException {
        LOG.fine("PeergosFileSystem.commit()");
    }

    @Override
    public void rollback( ITransaction transaction ) throws WebdavException {
        LOG.fine("PeergosFileSystem.rollback()");
    }

    private Optional<FileWrapper> getByPath(Path path) {
        return context.getByPath(path.toString().replace('\\', '/')).join();
    }

    @Override
    public void createFolder( ITransaction transaction,
                              String uri ) throws WebdavException {
        LOG.fine("PeergosFileSystem.createFolder(" + uri + ")");
        Path path = new File(uri).toPath();
        Optional<FileWrapper> parentFolder = getByPath(path.getParent());
        if (parentFolder.isEmpty() || parentFolder.get().getFileProperties().isHidden) {
            throw new WebdavException("cannot find parent of folder: " + uri);
        }
        try {
            parentFolder.get().mkdir(path.getFileName().toString(), context.network, false, context.mirrorBatId(), context.crypto).join();
        } catch (Exception ex) {
            LOG.log(Level.WARNING, ex, () -> "cannot create folder");
            throw new WebdavException("cannot create folder: " + uri);
        }
    }

    @Override
    public void createResource( ITransaction transaction,
                                String uri ) throws WebdavException {
        LOG.fine("PeergosFileSystem.createResource(" + uri + ")");
        Path path = new File(uri).toPath();
        if (path.getFileName().toString().startsWith("._") || path.getFileName().toString().equals(".DS_Store")) { // MacOS rubbish!
            return;
        }
        Optional<FileWrapper> parentFolder = getByPath(path.getParent());
        if (parentFolder.isEmpty() || parentFolder.get().getFileProperties().isHidden) {
            throw new WebdavException("cannot find parent of file: " + uri);
        }
        byte[] contents = new byte[0];
        try {
            HashTreeBuilder hash = new HashTreeBuilder(0);
            hash.setChunk(0, contents, context.crypto.hasher).join();
            HashTree h = hash.complete(context.crypto.hasher).join();
            parentFolder.get().uploadFileWithHash(path.getFileName().toString(), new AsyncReader.ArrayBacked(contents),
                    contents.length, Optional.of(h), Optional.empty(), Optional.empty(), context.network, context.crypto, l -> {}).join();
        } catch (Exception e) {
            LOG.warning("PeergosFileSystem.createResource(" + uri + ") failed");
            throw new WebdavException(e);
        }
    }

    @Override
    public long setResourceContent( ITransaction transaction,
                                    String uri,
                                    Pair<AsyncReader, Long> readerPair,
                                    String contentType,
                                    String characterEncoding ) throws WebdavException {
        return setResourceContent(transaction, uri, readerPair, 0, -1, contentType, characterEncoding);
    }

    @Override
    public long setResourceContent( ITransaction transaction,
                                    String uri,
                                    Pair<AsyncReader, Long> readerPair,
                                    long rangeStart,
                                    long rangeEnd,
                                    String contentType,
                                    String characterEncoding ) throws WebdavException {

        LOG.fine("PeergosFileSystem.setResourceContent(" + uri + ")");
        Path path = new File(uri).toPath();
        if (path.getFileName().toString().startsWith("._") || path.getFileName().toString().equals(".DS_Store")) { // MacOS rubbish!
            return 0;
        }
        Optional<FileWrapper> parentFolder = getByPath(path.getParent());
        if (parentFolder.isEmpty() || parentFolder.get().getFileProperties().isHidden) {
            throw new WebdavException("cannot find parent of file: " + uri);
        }
        try {
            boolean isRange = rangeEnd != -1;
            if (isRange) {
                // Client has already done chunk comparison; just write the specified range.
                Optional<FileWrapper> existingFile = getByPath(path);
                if (existingFile.isPresent() && !existingFile.get().isDirectory()
                        && !existingFile.get().getFileProperties().isHidden) {
                    FileWrapper fw = existingFile.get();
                    context.network.synchronizer.applyComplexUpdate(fw.owner(), fw.signingPair(),
                            (s, committer) -> fw.clean(s, committer, context.network, context.crypto)
                                    .thenCompose(cleaned -> cleaned.left.overwriteSection(
                                            cleaned.right, committer,
                                            readerPair.left, rangeStart, rangeEnd,
                                            Optional.empty(), context.network, context.crypto, l -> {}))).join();
                }
            } else {
                Optional<FileWrapper> existing = getByPath(path);
                if (existing.isPresent() && !existing.get().isDirectory()
                        && !existing.get().getFileProperties().isHidden
                        && existing.get().getSize() > 0) {
                    existing.get().overwriteChangedChunks(readerPair.left, readerPair.right,
                            context.network, context.crypto, l -> {}).join();
                } else {
                    parentFolder.get().uploadOrReplaceFile(path.getFileName().toString(),
                            readerPair.left, readerPair.right, context.network, context.crypto, () -> false, l -> {}).join();
                }
            }
            return readerPair.right;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "PeergosFileSystem.setResourceContent(" + uri + ") failed", e);
            throw new WebdavException(e);
        }
    }

    @Override
    public void moveResource( ITransaction transaction,
                              String sourcePath,
                              String destPath) throws WebdavException {

        LOG.fine("PeergosFileSystem.moveResource(" + sourcePath + ")");
        Path path = PathUtil.get(sourcePath);
        if (path.getFileName().toString().startsWith("._") || path.getFileName().toString().equals(".DS_Store")) { // MacOS rubbish!
            return;
        }
        Optional<FileWrapper> sourceFile = getByPath(path);
        if (sourceFile.isEmpty() || sourceFile.get().getFileProperties().isHidden) {
            throw new WebdavException("cannot find source file: " + sourcePath);
        }
        Optional<FileWrapper> parent = getByPath(path.getParent());
        if (parent.isEmpty() || parent.get().getFileProperties().isHidden) {
            throw new WebdavException("cannot find source parent: " + sourcePath);
        }
        Path targetPath = PathUtil.get(destPath);
        Path targetParentPath = targetPath.getParent();
        Optional<FileWrapper> targetParent = getByPath(targetParentPath);
        if (targetParent.isEmpty() || targetParent.get().getFileProperties().isHidden) {
            throw new WebdavException("cannot find target dir: " + destPath);
        }
        try {
            if (targetParentPath.toString().equals(path.getParent().toString())) { // rename
                sourceFile.get().rename(targetPath.getFileName().toString(), targetParent.get(), path, context).join();
            } else {
                sourceFile.get().moveTo(targetParent.get(), parent.get(), PathUtil.get(sourcePath), context, () -> Futures.of(true)).join();
            }
        } catch (Exception e) {
            LOG.warning("PeergosFileSystem.setResourceContent(" + sourcePath + ") failed");
            throw new WebdavException(e);
        }
    }

    @Override
    public String[] getChildrenNames( ITransaction transaction,
                                      String uri ) throws WebdavException {
        LOG.fine("PeergosFileSystem.getChildrenNames(" + uri + ")");
        Path path = new File(uri).toPath();
        Optional<FileWrapper> folder = getByPath(path);
        if (folder.isEmpty() || !folder.get().isDirectory() || Optional.ofNullable(folder.get().getFileProperties()).map(p -> p.isHidden).orElse(false)) {
            return new String[0];
        }
        Set<FileWrapper> children = folder.get().getChildren(context.crypto.hasher, context.network).join();
        List<String> filenames = children.stream()
                .filter(f -> !f.getFileProperties().isHidden)
                .map(f -> f.getName()).collect(Collectors.toList());
        String[] childrenNames = new String[filenames.size()];
        return filenames.toArray(childrenNames);
    }

    @Override
    public void removeObject( ITransaction transaction,
                              String uri ) throws WebdavException {
        Path path = new File(uri).toPath();
        Optional<FileWrapper> fw = getByPath(path);
        if (fw.isEmpty() || fw.get().getFileProperties().isHidden) {
            throw new WebdavException("cannot find: " + uri);
        }
        Optional<FileWrapper> parentFolder = getByPath(path.getParent());
        if (parentFolder.isEmpty()) {
            throw new WebdavException("cannot find parent folder of: " + uri);
        }
        try {
            fw.get().remove(parentFolder.get(), path, context).join();
        } catch (Exception ex) {
            throw new WebdavException("cannot delete object: " + uri);
        }
    }

    @Override
    public Pair<AsyncReader, Long> getResourceContent(ITransaction transaction,
                                                      String uri ) throws WebdavException {
        LOG.fine("PeergosFileSystem.getResourceContent(" + uri + ")");
        Path path = new File(uri).toPath();
        Optional<FileWrapper> fw = getByPath(path);
        if (fw.isEmpty() || fw.get().isDirectory() || fw.get().getFileProperties().isHidden) {
            throw new WebdavException("cannot find file: " + uri);
        }
        try {
            return new Pair<>(fw.get().getInputStream(context.network, context.crypto,
                    fw.get().getSize(), l-> {}).join(), fw.get().getSize());
        } catch (Exception e) {
            LOG.warning("PeergosFileSystem.getResourceContent(" + uri + ") failed");
            throw new WebdavException(e);
        }
    }

    @Override
    public long getResourceLength( ITransaction transaction,
                                   String uri) throws WebdavException {
        Path path = new File(uri).toPath();
        Optional<FileWrapper> fw = getByPath(path);
        if (fw.isEmpty() || fw.get().isDirectory() || fw.get().getFileProperties().isHidden) {
            throw new WebdavException("cannot find file: " + uri);
        }
        return fw.get().getFileProperties().size;
    }

    @Override
    public StoredObject getStoredObject(ITransaction transaction,
                                        String uri ) {
        StoredObject so = null;
        Path path = new File(uri).toPath();
        Optional<FileWrapper> fwOpt = getByPath(path);
        if (fwOpt.isPresent() && fwOpt.get().isRoot()) {
            so = new StoredObject();
            so.setFolder(true);
            so.setLastModified(new Date(0));
            so.setCreationDate(new Date(0));
            so.setResourceLength(0);
        } else if (fwOpt.isPresent() && !fwOpt.get().getFileProperties().isHidden) {
            so = new StoredObject();
            FileWrapper fw = fwOpt.get();
            so.setFolder(fw.isDirectory());
            so.setLastModified(new Date(fw.getFileProperties().modified.toEpochSecond(ZoneOffset.UTC) * 1000));
            so.setCreationDate(new Date(fw.getFileProperties().created.toEpochSecond(ZoneOffset.UTC) * 1000));
            so.setResourceLength(fw.getFileProperties().size);
        }
        return so;
    }

    @Override
    public Map<String, String> setCustomProperties( ITransaction transaction,
                                                    String resourceUri,
                                                    Map<String, Object> propertiesToSet,
                                                    List<String> propertiesToRemove ) {
        LOG.fine("PeergosFileSystem.setCustomProperties(" + resourceUri + ")");
        return null;
    }

    @Override
    public Map<String, Object> getCustomProperties( ITransaction transaction,
                                                    String resourceUri ) {
        Path path = new File(resourceUri).toPath();
        Optional<FileWrapper> fwOpt = getByPath(path);
        if (fwOpt.isEmpty() || fwOpt.get().isDirectory() || fwOpt.get().getFileProperties().isHidden)
            return Collections.emptyMap();
        FileWrapper fw = fwOpt.get();
        Map<String, Object> props = new LinkedHashMap<>();
        fw.getFileProperties().treeHash.ifPresent(h -> {
            props.put(PEERGOS_NS + ":treehash", h.toString());
            h.level1.ifPresent(chunks -> props.put(PEERGOS_NS + ":treehash-chunks",
                    HexFormat.of().formatHex(chunks.chunkHashes)));
        });
        props.put(PEERGOS_NS + ":writerKey", fw.writer().toString());
        return props;
    }

    @Override
    public Map<String, String> getCustomNamespaces( ITransaction transaction,
                                                    String resourceUri ) {
        return Collections.singletonMap(PEERGOS_NS, "P");
    }

    @Override
    public long[] getQuotaInfo( ITransaction transaction ) {
        long now = System.currentTimeMillis();
        if (now - quotaCacheTime > QUOTA_CACHE_TTL_MS) {
            try {
                cachedUsed = context.getSpaceUsage(false).join();
                cachedQuota = context.getQuota().join();
                quotaCacheTime = now;
            } catch (Exception e) {
                LOG.fine("Failed to refresh quota info: " + e.getMessage());
            }
        }
        return new long[]{cachedUsed, Math.max(0, cachedQuota - cachedUsed)};
    }
}
