package peergos.shared.user.fs;

import jsinterop.annotations.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.random.*;
import peergos.shared.crypto.symmetric.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.io.*;
import java.time.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.*;
import java.util.stream.*;

public class FileTreeNode {

    final static int[] BMP = new int[]{66, 77};
    final static int[] GIF = new int[]{71, 73, 70};
    final static int[] JPEG = new int[]{255, 216};
    final static int[] PNG = new int[]{137, 80, 78, 71, 13, 10, 26, 10};
    final static int HEADER_BYTES_TO_IDENTIFY_IMAGE_FILE = 8;
    final static int THUMBNAIL_SIZE = 100;
    final NativeJSThumbnail thumbnail;
    
    RetrievedFilePointer pointer;
    private FileProperties props;
    String ownername;
    Set<String> readers;
    Set<String> writers;
    UserPublicKey entryWriterKey;

    public FileTreeNode(RetrievedFilePointer pointer, String ownername, Set<String> readers, Set<String> writers, UserPublicKey entryWriterKey) {
        this.pointer = pointer == null ? null : pointer.withWriter(entryWriterKey);
        this.ownername = ownername;
        this.readers = readers;
        this.writers = writers;
        this.entryWriterKey = entryWriterKey;
        if (pointer == null)
            props = new FileProperties("/", 0, LocalDateTime.MIN, false, Optional.empty());
        else {
            SymmetricKey parentKey = this.getParentKey();
            props = pointer.fileAccess.getFileProperties(parentKey);
        }
        thumbnail = new NativeJSThumbnail();
    }

    public boolean equals(Object other) {
        if (other == null)
            return false;
        if (!(other instanceof FileTreeNode))
            return false;
        return pointer.equals(((FileTreeNode)other).getPointer());
    }

    public RetrievedFilePointer getPointer() {
        return pointer;
    }

    public boolean isRoot() {
        return props.name.equals("/");
    }

    public CompletableFuture<String> getPath(UserContext context) {
        return retrieveParent(context).thenCompose(parent -> {
            if (!parent.isPresent() || parent.get().isRoot())
                return CompletableFuture.completedFuture("/" + props.name);
            return parent.get().getPath(context).thenApply(parentPath -> parentPath + "/" + props.name);
        });
    }

    public CompletableFuture<Optional<FileTreeNode>> getDescendentByPath(String path, UserContext context) {
        if (path.length() == 0)
            return CompletableFuture.completedFuture(Optional.of(this));

        if (path.equals("/"))
            if (isDirectory())
                return CompletableFuture.completedFuture(Optional.of(this));
            else
                return CompletableFuture.completedFuture(Optional.empty());

        if (path.startsWith("/"))
            path = path.substring(1);
        int slash = path.indexOf("/");
        String prefix = slash > 0 ? path.substring(0, slash) : path;
        String suffix = slash > 0 ? path.substring(slash + 1) : "";
        return getChildren(context).thenCompose(children -> {
            for (FileTreeNode child : children)
                if (child.getFileProperties().name.equals(prefix)) {
                    return child.getDescendentByPath(suffix, context);
                }
            return CompletableFuture.completedFuture(Optional.empty());
        });
    }

    /** Marks a file/directory and all its descendants as dirty. Directories are immediately cleaned,
     * but files have all their keys except the actual data key cleaned. That is cleaned lazily, the next time it is modified
     *
     * @param context
     * @param parent
     * @param readersToRemove
     * @return
     * @throws IOException
     */
    public CompletableFuture<FileTreeNode> makeDirty(UserContext context, FileTreeNode parent, Set<String> readersToRemove) {
        if (!isWritable())
            throw new IllegalStateException("You cannot mark a file as dirty without write access!");
        if (isDirectory()) {
            // create a new baseKey == subfoldersKey and make all descendants dirty
            SymmetricKey newSubfoldersKey = SymmetricKey.random();
            ReadableFilePointer ourNewPointer = pointer.filePointer.withBaseKey(newSubfoldersKey);
            SymmetricKey newParentKey = SymmetricKey.random();
            FileProperties props = getFileProperties();

            // Create new DirAccess, but don't upload it
            DirAccess newDirAccess = DirAccess.create(newSubfoldersKey, props, parent.pointer.filePointer.getLocation(),
                    parent.getParentKey(), newParentKey);
            // re add children
            DirAccess existing = (DirAccess) pointer.fileAccess;
            List<ReadableFilePointer> subdirs = existing.getSubfolders().stream().map(link -> new ReadableFilePointer(link.targetLocation(pointer.filePointer.baseKey),
                    link.target(pointer.filePointer.baseKey))).collect(Collectors.toList());
            return newDirAccess.addSubdirsAndCommit(subdirs, newSubfoldersKey, ourNewPointer, context).thenCompose(updatedDirAccess -> {

                SymmetricKey filesKey = existing.getFilesKey(pointer.filePointer.baseKey);
                List<ReadableFilePointer> files = existing.getFiles().stream()
                        .map(link -> new ReadableFilePointer(link.targetLocation(filesKey), link.target(filesKey)))
                        .collect(Collectors.toList());
                return updatedDirAccess.addFilesAndCommit(files, newSubfoldersKey, ourNewPointer, context)
                        .thenCompose(fullyUpdatedDirAccess -> {

                            readers.removeAll(readersToRemove);
                            RetrievedFilePointer ourNewRetrievedPointer = new RetrievedFilePointer(ourNewPointer, fullyUpdatedDirAccess);
                            FileTreeNode theNewUs = new FileTreeNode(ourNewRetrievedPointer, ownername, readers, writers, entryWriterKey);

                            // clean all subtree keys except file dataKeys (lazily re-key and re-encrypt them)
                            return getChildren(context).thenCompose(children -> {
                                for (FileTreeNode child : children) {
                                    child.makeDirty(context, theNewUs, readersToRemove);
                                }

                                // update pointer from parent to us
                                return ((DirAccess) parent.pointer.fileAccess)
                                        .updateChildLink(parent.pointer.filePointer, this.pointer, ourNewRetrievedPointer, context)
                                        .thenApply(x -> theNewUs);
                            });
                        });
            });
        } else {
            // create a new baseKey == parentKey and mark the metaDataKey as dirty
            SymmetricKey parentKey = SymmetricKey.random();
            return pointer.fileAccess.markDirty(pointer.filePointer, parentKey, context).thenCompose(newFileAccess -> {

                // changing readers here will only affect the returned FileTreeNode, as the readers is derived from the entry point
                TreeSet<String> newReaders = new TreeSet<>(readers);
                newReaders.removeAll(readersToRemove);
                RetrievedFilePointer newPointer = new RetrievedFilePointer(this.pointer.filePointer.withBaseKey(parentKey), newFileAccess);

                // update link from parent folder to file to have new baseKey
                return ((DirAccess) parent.pointer.fileAccess)
                        .updateChildLink(parent.pointer.filePointer, pointer, newPointer, context)
                        .thenApply(x -> new FileTreeNode(newPointer, ownername, newReaders, writers, entryWriterKey));
            });
        }
    }

    public CompletableFuture<Boolean> hasChildWithName(String name, UserContext context) {
        return getChildren(context)
                .thenApply(children -> children.stream().filter(c -> c.props.name.equals(name)).findAny().isPresent());
    }

    public CompletableFuture<Boolean> removeChild(FileTreeNode child, UserContext context) {
        return ((DirAccess)pointer.fileAccess).removeChild(child.getPointer(), pointer.filePointer, context);
    }

    public CompletableFuture<FileTreeNode> addLinkTo(FileTreeNode file, UserContext context) {
        CompletableFuture<FileTreeNode> error = new CompletableFuture<>();
        if (!this.isDirectory() || !this.isWritable()) {
            error.completeExceptionally(new IllegalArgumentException("Can only add link toa writable directory!"));
            return error;
        }
        String name = file.getFileProperties().name;
        return hasChildWithName(name, context).thenCompose(hasChild -> {
            if (hasChild) {
                error.completeExceptionally(new IllegalStateException("Child already exists with name: " + name));
                return error;
            }
            Location loc = file.getLocation();
            DirAccess toUpdate = (DirAccess) pointer.fileAccess;
            return (file.isDirectory() ?
                    toUpdate.addSubdirAndCommit(file.pointer.filePointer, this.getKey(), pointer.filePointer, context) :
                    toUpdate.addFileAndCommit(file.pointer.filePointer, this.getKey(), pointer.filePointer, context))
                    .thenApply(dirAccess -> new FileTreeNode(this.pointer, ownername, readers, writers, entryWriterKey));
        });
    }

    @JsMethod
    public String toLink() {
        return pointer.filePointer.toLink();
    }

    @JsMethod
    public boolean isWritable() {
        return entryWriterKey instanceof SigningKeyPair;
    }

    @JsMethod
    public boolean isReadable() {
        try {
            pointer.fileAccess.getMetaKey(pointer.filePointer.baseKey);
            return false;
        } catch (Exception e) {}
        return true;
    }

    public SymmetricKey getKey() {
        return pointer.filePointer.baseKey;
    }

    public Location getLocation() {
        return pointer.filePointer.getLocation();
    }

    public Set<Location> getChildrenLocations() {
        if (!this.isDirectory())
            return Collections.emptySet();
        return ((DirAccess)pointer.fileAccess).getChildrenLocations(pointer.filePointer.baseKey);
    }

    public CompletableFuture<Optional<FileTreeNode>> retrieveParent(UserContext context) {
        if (pointer == null)
            return CompletableFuture.completedFuture(Optional.empty());
        SymmetricKey parentKey = getParentKey();
        CompletableFuture<RetrievedFilePointer> parent = pointer.fileAccess.getParent(parentKey, context);
        return parent.thenApply(parentRFP -> {
            if (parentRFP == null)
                return Optional.of(createRoot());
            return Optional.of(new FileTreeNode(parentRFP, ownername, Collections.emptySet(), Collections.emptySet(), entryWriterKey));
        });
    }

    public SymmetricKey getParentKey() {
        SymmetricKey parentKey = pointer.filePointer.baseKey;
        if (this.isDirectory())
            try {
                parentKey = pointer.fileAccess.getParentKey(parentKey);
            } catch (Exception e) {
                // if we don't have read access to this folder, then we must just have the parent key already
            }
        return parentKey;
    }

    @JsMethod
    public CompletableFuture<Set<FileTreeNode>> getChildren(UserContext context) {
        if (this.props.name.equals("/"))
            return context.getChildren("/");
        if (isReadable()) {
            return retrieveChildren(context).thenApply(childrenRFPs -> {
                Set<FileTreeNode> newChildren = childrenRFPs.stream()
                        .map(x -> new FileTreeNode(x, ownername, readers, writers, entryWriterKey))
                        .collect(Collectors.toSet());
                return newChildren.stream().collect(Collectors.toSet());
            });
        }
        return getPath(context).thenCompose(path -> context.getChildren(path));
    }

    private CompletableFuture<Set<RetrievedFilePointer>> retrieveChildren(UserContext context) {
        ReadableFilePointer filePointer = pointer.filePointer;
        FileAccess fileAccess = pointer.fileAccess;
        SymmetricKey rootDirKey = filePointer.baseKey;

        if (isReadable())
            return ((DirAccess) fileAccess).getChildren(context, rootDirKey);
        throw new IllegalStateException("No credentials to retrieve children!");
    }

    @JsMethod
    public String getOwner() {
        return ownername;
    }

    @JsMethod
    public boolean isDirectory() {
        boolean isNull = pointer == null;
        return isNull || pointer.fileAccess.isDirectory();
    }

    public boolean isDirty() {
        return pointer.fileAccess.isDirty(pointer.filePointer.baseKey);
    }

    public CompletableFuture<FileTreeNode> clean(UserContext context, FileTreeNode parent, peergos.shared.user.fs.Fragmenter fragmenter) {
        if (!isDirty())
            return CompletableFuture.completedFuture(this);
        if (isDirectory()) {
            throw new IllegalStateException("Unimplemented directory cleaning!");
        } else {
            FileProperties props = getFileProperties();
            SymmetricKey baseKey = pointer.filePointer.baseKey;
            // stream download and re-encrypt with new metaKey
            return getInputStream(context, l -> {}).thenCompose(in -> {
                byte[] tmp = new byte[16];
                new Random().nextBytes(tmp);
                String tmpFilename = ArrayOps.bytesToHex(tmp) + ".tmp";

                CompletableFuture<Boolean> reuploaded = parent.uploadFile(tmpFilename, in, 0, props.size,
                        Optional.of(baseKey), context, l -> {
                        }, fragmenter);
                return reuploaded.thenCompose(upload -> parent.getDescendentByPath(tmpFilename, context))
                        .thenCompose(tmpChild -> tmpChild.get().rename(props.name, context, parent, true))
                        .thenCompose(rename -> parent.getDescendentByPath(props.name, context))
                        .thenApply(fileOpt -> fileOpt.get());
            });
        }
    }

    @JsMethod
    public CompletableFuture<Boolean> uploadFile(String filename, AsyncReader fileData, int lengthHi, int lengthLow, UserContext context, ProgressConsumer<Long> monitor) {
        return uploadFile(filename, fileData, lengthLow + ((lengthHi & 0xFFFFFFFFL) << 32), context, monitor, context.fragmenter());
    }

    public CompletableFuture<Boolean> uploadFile(String filename, AsyncReader fileData, long length, UserContext context,
                                                 ProgressConsumer<Long> monitor, peergos.shared.user.fs.Fragmenter fragmenter) {
        return uploadFile(filename, fileData, 0, length, Optional.empty(), context, monitor, fragmenter);
    }

    public CompletableFuture<Boolean> uploadFile(String filename, AsyncReader fileData, long startIndex, long endIndex,
                                                 UserContext context, ProgressConsumer<Long> monitor, peergos.shared.user.fs.Fragmenter fragmenter) {
        return uploadFile(filename, fileData, startIndex, endIndex, Optional.empty(), context, monitor, fragmenter);
    }

    public CompletableFuture<Boolean> uploadFile(String filename, AsyncReader fileData, long startIndex, long endIndex, Optional<SymmetricKey> baseKey,
                                                 UserContext context, ProgressConsumer<Long> monitor, peergos.shared.user.fs.Fragmenter fragmenter) {
        if (!isLegalName(filename))
            return CompletableFuture.completedFuture(false);
        return getDescendentByPath(filename, context).thenCompose(childOpt -> {
            if (childOpt.isPresent()) {
                return updateExistingChild(childOpt.get(), fileData, startIndex, endIndex, context, monitor, fragmenter);
            }
            if (startIndex > 0) {
                // TODO if startIndex > 0 prepend with a zero section
                throw new IllegalStateException("Unimplemented!");
            }
            SymmetricKey fileKey = baseKey.orElseGet(SymmetricKey::random);
            SymmetricKey fileMetaKey = SymmetricKey.random();
            SymmetricKey rootRKey = pointer.filePointer.baseKey;
            byte[] dirMapKey = pointer.filePointer.getLocation().getMapKey();
            DirAccess dirAccess = (DirAccess) pointer.fileAccess;
            SymmetricKey dirParentKey = dirAccess.getParentKey(rootRKey);
            Location parentLocation = getLocation();
            CompletableFuture<Boolean> result = new CompletableFuture<>();
            int thumbnailSrcImageSize = startIndex == 0 && endIndex < Integer.MAX_VALUE ? (int)endIndex : 0;
            generateThumbnail(context, fileData, thumbnailSrcImageSize, filename).thenAccept(thumbData -> {
                fileData.reset().thenAccept(resetResult -> {
                FileProperties fileProps = new FileProperties(filename, endIndex, LocalDateTime.now(), false, Optional.of(thumbData));
                FileUploader chunks = new FileUploader(filename, fileData, startIndex, endIndex, fileKey, fileMetaKey, parentLocation, dirParentKey, monitor, fileProps,
                                                       EncryptedChunk.ERASURE_ORIGINAL, EncryptedChunk.ERASURE_ALLOWED_FAILURES);
                byte[] mapKey = context.randomBytes(32);
                Location nextChunkLocation = new Location(getLocation().owner, getLocation().writer, mapKey);
                chunks.upload(context, parentLocation.owner, (SigningKeyPair) entryWriterKey, nextChunkLocation).thenAccept(fileLocation -> {
                    ReadableFilePointer filePointer = new ReadableFilePointer(fileLocation, fileKey);
                    dirAccess.addFileAndCommit(filePointer, rootRKey, pointer.filePointer, context);
                    context.uploadChunk(dirAccess, new Location(parentLocation.owner, entryWriterKey, dirMapKey)
                                        , Collections.emptyList()).thenAccept(uploadResult -> {
                        result.complete(uploadResult);
                    });
                });
                });
            });
            return result;
        });
    }

    private CompletableFuture<Boolean> updateExistingChild(FileTreeNode existingChild, AsyncReader fileData,
                                                           long inputStartIndex, long endIndex, UserContext context,
                                                           ProgressConsumer<Long> monitor, peergos.shared.user.fs.Fragmenter fragmenter) {

        String filename = existingChild.getFileProperties().name;
        System.out.println("Overwriting section [" + Long.toHexString(inputStartIndex) + ", " + Long.toHexString(endIndex) + "] of child with name: " + filename);

        if (existingChild.isDirty()) {
            CompletableFuture<FileTreeNode> clean = existingChild.clean(context, this, fragmenter);
            return clean.thenCompose(x -> CompletableFuture.completedFuture(true));
        }

        Supplier<Location> locationSupplier = () -> new Location(getLocation().owner, getLocation().writer, context.randomBytes(32));

        return CompletableFuture.completedFuture(existingChild)
                .thenCompose(child -> {
                    FileProperties childProps = child.getFileProperties();
                    final AtomicLong filesSize = new AtomicLong(childProps.size);
                    FileRetriever retriever = child.getRetriever();
                    SymmetricKey baseKey = child.pointer.filePointer.baseKey;
                    SymmetricKey dataKey = child.pointer.fileAccess.getMetaKey(baseKey);

                    List<Long> startIndexes = new ArrayList<>();

                    for (long startIndex = inputStartIndex; startIndex < endIndex; startIndex = startIndex + Chunk.MAX_SIZE - (startIndex % Chunk.MAX_SIZE))
                        startIndexes.add(startIndex);


                    boolean identity = true;

                    BiFunction<Boolean, Long, CompletableFuture<Boolean>> composer = (id, startIndex) -> {
                        return retriever.getChunkInputStream(context, dataKey, startIndex, filesSize.get(), child.getLocation(), monitor)
                                .thenCompose(currentLocation -> {
                                            CompletableFuture<Optional<Location>> locationAt = retriever.getLocationAt(child.getLocation(), startIndex + Chunk.MAX_SIZE, context);
                                            return locationAt.thenCompose(location ->
                                                    CompletableFuture.completedFuture(new Pair<>(currentLocation, location)));
                                        }
                                ).thenCompose(pair -> {

                                    if (!pair.left.isPresent()) {
                                        CompletableFuture<Boolean> result = new CompletableFuture<>();
                                        result.completeExceptionally(new IllegalStateException("Current chunk not present"));
                                        return result;
                                    }

                                    LocatedChunk currentOriginal = pair.left.get();
                                    Optional<Location> nextChunkLocationOpt = pair.right;
                                    Location nextChunkLocation = nextChunkLocationOpt.orElseGet(locationSupplier);
                                    System.out.println("********** Writing to chunk at mapkey: " + ArrayOps.bytesToHex(currentOriginal.location.getMapKey()) + " next: " + nextChunkLocation);

                                    // modify chunk, re-encrypt and upload
                                    int internalStart = (int) (startIndex % Chunk.MAX_SIZE);
                                    int internalEnd = endIndex - (startIndex - internalStart) > Chunk.MAX_SIZE ?
                                            Chunk.MAX_SIZE : (int) (endIndex - (startIndex - internalStart));
                                    byte[] rawData = currentOriginal.chunk.data();
                                    // extend data array if necessary
                                    if (rawData.length < internalEnd)
                                        rawData = Arrays.copyOfRange(rawData, 0, internalEnd);
                                    byte[] raw = rawData;

                                    return fileData.readIntoArray(raw, internalStart, internalEnd - internalStart).thenCompose(read -> {

                                        byte[] nonce = context.randomBytes(TweetNaCl.SECRETBOX_NONCE_BYTES);

                                        Chunk updated = new Chunk(raw, dataKey, currentOriginal.location.getMapKey(), nonce);
                                        LocatedChunk located = new LocatedChunk(currentOriginal.location, updated);
                                        long currentSize = filesSize.get();
                                        FileProperties newProps = new FileProperties(childProps.name, endIndex > currentSize ? endIndex : currentSize,
                                                LocalDateTime.now(), childProps.isHidden, childProps.thumbnail);

                                        CompletableFuture<Boolean> chunkUploaded = FileUploader.uploadChunk((SigningKeyPair) entryWriterKey, newProps, getLocation(), getParentKey(), baseKey, located,
                                                EncryptedChunk.ERASURE_ORIGINAL, EncryptedChunk.ERASURE_ALLOWED_FAILURES, nextChunkLocation, context, monitor);

                                        return chunkUploaded.thenCompose(isUploaded -> {
                                            if (!isUploaded)
                                                return CompletableFuture.completedFuture(false);


                                            //update indices to be relative to next chunk
                                            long updatedLength = startIndex + internalEnd - internalStart;
                                            if (updatedLength > filesSize.get()) {
                                                filesSize.set(updatedLength);

                                                if (updatedLength > Chunk.MAX_SIZE) {
                                                    // update file size in FileProperties of first chunk
                                                    CompletableFuture<Boolean> updatedSize = getChildren(context).thenCompose(children -> {
                                                        Optional<FileTreeNode> updatedChild = children.stream()
                                                                .filter(f -> f.getFileProperties().name.equals(filename))
                                                                .findAny();
                                                        return updatedChild.get().setProperties(child.getFileProperties().withSize(endIndex), context, this);
                                                    });
                                                }
                                            }
                                            return CompletableFuture.completedFuture(true);
                                        });
                                    });
                                });
                    };

                    BiFunction<Boolean, Boolean, Boolean> combiner = (left, right) -> left && right;
                    return Futures.reduceAll(startIndexes, identity, composer, combiner);
                });
    }

    static boolean isLegalName(String name) {
        return !name.contains("/");
    }

    @JsMethod
    public CompletableFuture<ReadableFilePointer> mkdir(String newFolderName, UserContext context, boolean isSystemFolder) throws IOException {
        return mkdir(newFolderName, context, isSystemFolder, context.crypto.random);
    }

    public CompletableFuture<ReadableFilePointer> mkdir(String newFolderName, UserContext context, boolean isSystemFolder, SafeRandom random) throws IOException {
        return mkdir(newFolderName, context, null, isSystemFolder, random);
    }

    public CompletableFuture<ReadableFilePointer>
    mkdir(String newFolderName, UserContext context, SymmetricKey requestedBaseSymmetricKey,
                                                        boolean isSystemFolder, SafeRandom random) {
        CompletableFuture<ReadableFilePointer> result = new CompletableFuture<>();
        if (!this.isDirectory()) {
            result.completeExceptionally(new IllegalStateException("Cannot mkdir in a file!"));
            return result;
        }
        if (!isLegalName(newFolderName)) {
            result.completeExceptionally(new IllegalStateException("Illegal directory name: " + newFolderName));
            return result;
        }
        return hasChildWithName(newFolderName, context).thenCompose(hasChild -> {
            if (hasChild) {
                result.completeExceptionally(new IllegalStateException("Child already exists with name: " + newFolderName));
                return result;
            }
            ReadableFilePointer dirPointer = pointer.filePointer;
            DirAccess dirAccess = (DirAccess) pointer.fileAccess;
            SymmetricKey rootDirKey = dirPointer.baseKey;
            return dirAccess.mkdir(newFolderName, context, (SigningKeyPair) entryWriterKey, dirPointer.getLocation().getMapKey(), rootDirKey,
                    requestedBaseSymmetricKey, isSystemFolder, random);
        });
    }

    @JsMethod
    public CompletableFuture<Boolean> rename(String newFilename, UserContext context, FileTreeNode parent) {
        return rename(newFilename, context, parent, false);
    }

    public CompletableFuture<Boolean> rename(String newFilename, UserContext context, FileTreeNode parent, boolean overwrite) {
        if (! isLegalName(newFilename))
            return CompletableFuture.completedFuture(false);
        CompletableFuture<Optional<FileTreeNode>> childExists = parent == null ?
                CompletableFuture.completedFuture(Optional.empty()) :
                parent.getDescendentByPath(newFilename, context);
        return childExists
                .thenCompose(existing -> {
                    if (existing.isPresent() && !overwrite)
                        return CompletableFuture.completedFuture(false);

                    return ((overwrite && existing.isPresent()) ?
                            existing.get().remove(context, parent) :
                            CompletableFuture.completedFuture(true)).thenCompose(res -> {

                        //get current props
                        ReadableFilePointer filePointer = pointer.filePointer;
                        SymmetricKey baseKey = filePointer.baseKey;
                        FileAccess fileAccess = pointer.fileAccess;

                        SymmetricKey key = this.isDirectory() ? fileAccess.getParentKey(baseKey) : baseKey;
                        FileProperties currentProps = fileAccess.getFileProperties(key);

                        FileProperties newProps = new FileProperties(newFilename, currentProps.size, currentProps.modified, currentProps.isHidden, currentProps.thumbnail);

                        return fileAccess.rename(writableFilePointer(), newProps, context);
                    });
                });
    }

    public CompletableFuture<Boolean> setProperties(FileProperties updatedProperties, UserContext context, FileTreeNode parent) {
        String newName = updatedProperties.name;
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        if (!isLegalName(newName)) {
            result.completeExceptionally(new IllegalArgumentException("Illegal file name: " + newName));
            return result;
        }
        return (parent == null ? CompletableFuture.completedFuture(false) : parent.hasChildWithName(newName, context)).thenCompose(hasChild -> {
            if (hasChild && parent!= null && !parent.getChildrenLocations().stream()
                    .map(l -> new ByteArrayWrapper(l.getMapKey()))
                    .collect(Collectors.toSet())
                    .contains(new ByteArrayWrapper(pointer.filePointer.getLocation().getMapKey()))) {
                result.completeExceptionally(new IllegalStateException("Cannot rename to same name as an existing file"));
                return result;
            }
            FileAccess fileAccess = pointer.fileAccess;

            return fileAccess.rename(writableFilePointer(), updatedProperties, context);
        });
    }

    private ReadableFilePointer writableFilePointer() {
        ReadableFilePointer filePointer = pointer.filePointer;
        SymmetricKey baseKey = filePointer.baseKey;
        return new ReadableFilePointer(filePointer.location.withWriter(entryWriterKey), baseKey);
    }

    public UserPublicKey getEntryWriterKey() {
        return entryWriterKey;
    }

    @JsMethod
    public CompletableFuture<FileTreeNode> copyTo(FileTreeNode target, UserContext context) {
        CompletableFuture<FileTreeNode> result = new CompletableFuture<>();
        if (! target.isDirectory()) {
            result.completeExceptionally(new IllegalStateException("CopyTo target " + target + " must be a directory"));
            return result;
        }
        return target.hasChildWithName(getFileProperties().name, context).thenCompose(childExists -> {
            if (childExists) {
                result.completeExceptionally(new IllegalStateException("CopyTo target " + target + " already has child with name " + getFileProperties().name));
                return result;
            }
            //make new FileTreeNode pointing to the same file, but with a different location
            byte[] newMapKey = new byte[32];
            context.crypto.random.randombytes(newMapKey, 0, 32);
            SymmetricKey ourBaseKey = this.getKey();
            // a file baseKey is the key for the chunk, which hasn't changed, so this must stay the same
            SymmetricKey newBaseKey = this.isDirectory() ? SymmetricKey.random() : ourBaseKey;
            ReadableFilePointer newRFP = new ReadableFilePointer(context.signer, target.getEntryWriterKey(), newMapKey, newBaseKey);
            Location newParentLocation = target.getLocation();
            SymmetricKey newParentParentKey = target.getParentKey();

            return pointer.fileAccess.copyTo(ourBaseKey, newBaseKey, newParentLocation, newParentParentKey, (SigningKeyPair) target.getEntryWriterKey(), newMapKey, context)
                    .thenCompose(newAccess -> {
                        // upload new metadatablob
                        RetrievedFilePointer newRetrievedFilePointer = new RetrievedFilePointer(newRFP, newAccess);
                        FileTreeNode newFileTreeNode = new FileTreeNode(newRetrievedFilePointer, context.username,
                                Collections.emptySet(), Collections.emptySet(), target.getEntryWriterKey());
                        return target.addLinkTo(newFileTreeNode, context);
                    });
        });
    }

    @JsMethod
    public CompletableFuture<Boolean> remove(UserContext context, FileTreeNode parent) {
        Supplier<CompletableFuture<Boolean>> supplier = () -> new RetrievedFilePointer(writableFilePointer(), pointer.fileAccess).remove(context, null);

        if (parent != null) {
            return parent.removeChild(this, context)
                    .thenCompose(x -> supplier.get());
        }
        return supplier.get();
    }

    public CompletableFuture<? extends AsyncReader> getInputStream(UserContext context, ProgressConsumer<Long> monitor) {
        return getInputStream(context, getFileProperties().size, monitor);
    }

    @JsMethod
    public CompletableFuture<? extends AsyncReader> getInputStream(UserContext context, int fileSizeHi, int fileSizeLow,
                                                                   ProgressConsumer<Long> monitor) {
        return getInputStream(context, fileSizeLow + ((fileSizeHi & 0xFFFFFFFFL) << 32), monitor);
    }

    public CompletableFuture<? extends AsyncReader> getInputStream(UserContext context, long fileSize, ProgressConsumer<Long> monitor) {
        SymmetricKey baseKey = pointer.filePointer.baseKey;
        SymmetricKey dataKey = pointer.fileAccess.getMetaKey(baseKey);
        return pointer.fileAccess.retriever().getFile(context, dataKey, fileSize, getLocation(), monitor);
    }

    private FileRetriever getRetriever() {
        return pointer.fileAccess.retriever();
    }

    @JsMethod
    public String getBase64Thumbnail() {
        Optional<byte[]> thumbnail = props.thumbnail;
        if(thumbnail.isPresent()){
            String base64Data = Base64.getEncoder().encodeToString(thumbnail.get());
            return "data:image/png;base64," + base64Data;
        }else{
            return "";
        }
    }

    @JsMethod
    public FileProperties getFileProperties() {
        return props;
    }

    public String getName() {
        return getFileProperties().name;
    }

    public long getSize() {
        return getFileProperties().size;
    }

    public String toString() {
        return getFileProperties().name;
    }

    public static FileTreeNode createRoot() {
        return new FileTreeNode(null, null, Collections.EMPTY_SET, Collections.EMPTY_SET, null);
    }

    private CompletableFuture<byte[]> generateThumbnail(UserContext context, AsyncReader fileData, int fileSize, String filename)
    {
        CompletableFuture<byte[]> fut = new CompletableFuture<>();
        if(context.isJavascript() && fileSize > 0) {
            isImage(fileData).thenAccept(isThumbnail -> {
                if(isThumbnail) {
                    thumbnail.generateThumbnail(fileData, fileSize, filename).thenAccept(base64Str -> {
                        byte[] bytesOfData = Base64.getDecoder().decode(base64Str);
                        fut.complete(bytesOfData);
                    });
                } else{
                    fut.complete(new byte[0]);    			 
                }
            });
        } else {
            fut.complete(new byte[0]);
        }
        return fut;
    }

    private CompletableFuture<Boolean> isImage(AsyncReader imageBlob)
    {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        byte[] data = new byte[HEADER_BYTES_TO_IDENTIFY_IMAGE_FILE];
        imageBlob.readIntoArray(data, 0, HEADER_BYTES_TO_IDENTIFY_IMAGE_FILE).thenAccept(numBytesRead -> {
        	imageBlob.reset().thenAccept(resetResult -> {
	            if(numBytesRead < HEADER_BYTES_TO_IDENTIFY_IMAGE_FILE) {
	            	result.complete(false);
	            }else {
                    byte[] tempBytes = Arrays.copyOfRange(data, 0, 2);
	            	if (!compareArrayContents(Arrays.copyOfRange(data, 0, BMP.length), BMP)
	                    && !compareArrayContents(Arrays.copyOfRange(data, 0, GIF.length), GIF)
	                    && !compareArrayContents(Arrays.copyOfRange(data, 0, PNG.length), PNG)
	                    && !compareArrayContents(Arrays.copyOfRange(data, 0, 2), JPEG)) {
	            		result.complete(false);
	            	}else {
            			result.complete(true);
	            	}
	            }
        	});
        });
    	return result;
    }

    private boolean compareArrayContents(byte[] a, int[] a2) {
        if (a==null || a2==null){
            return false;
        }
        int length = a.length;
        if (a2.length != length){
            return false;
        }
        
        for (int i=0; i<length; i++) {
            if (a[i] != a2[i]) {
                return false;
            }
        }
        return true;
    }
    private static InputStream NULL_STREAM = new InputStream() {
        @Override
        public int read() throws IOException {
            return 0;
        }
    };
}
