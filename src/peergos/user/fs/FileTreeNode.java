package peergos.user.fs;

import peergos.crypto.*;
import peergos.crypto.random.*;
import peergos.crypto.symmetric.*;
import peergos.user.*;
import peergos.util.*;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.time.*;
import java.util.*;
import java.util.function.*;
import java.util.stream.*;

public class FileTreeNode {

    final static byte[] BMP = new byte[]{66, 77};
    final static byte[] GIF = new byte[]{71, 73, 70};
    final static byte[] JPEG = new byte[]{(byte)255, (byte)216};
    final static byte[] PNG = new byte[]{(byte)137, 80, 78, 71, 13, 10, 26, 10};
    final static int HEADER_BYTES_TO_IDENTIFY_IMAGE_FILE = 8;
    final static int THUMBNAIL_SIZE = 100;

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
        else
            try {
                SymmetricKey parentKey = this.getParentKey();
                props = pointer.fileAccess.getFileProperties(parentKey);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
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

    public String getPath(UserContext context) {
        Optional<FileTreeNode> parent = retrieveParent(context);
        if (!parent.isPresent() || parent.get().isRoot())
            return "/"+props.name;
        return parent.get().getPath(context) + "/" + props.name;
    }

    public Optional<FileTreeNode> getDescendentByPath(String path, UserContext context) {
        if (path.length() == 0)
            return Optional.of(this);

        if (path.equals("/"))
            if (isDirectory())
                return Optional.of(this);
            else
                return Optional.empty();

        if (path.startsWith("/"))
            path = path.substring(1);
        int slash = path.indexOf("/");
        String prefix = slash > 0 ? path.substring(0, slash) : path;
        String suffix = slash > 0 ? path.substring(slash + 1) : "";
        Set<FileTreeNode> children = getChildren(context);
        for (FileTreeNode child: children)
            if (child.getFileProperties().name.equals(prefix)) {
                return child.getDescendentByPath(suffix, context);
            }
        return Optional.empty();
    }

    public FileTreeNode makeDirty(UserContext context, FileTreeNode parent, Set<String> readersToRemove) throws IOException {
        if (!isWritable())
            throw new IllegalStateException("You cannot mark a file as dirty without write access!");
        if (isDirectory()) {
            // create a new baseKey == subfoldersKey and make all descendants dirty
            SymmetricKey newSubfoldersKey = SymmetricKey.random();
            SymmetricKey newParentKey = SymmetricKey.random();
            FileProperties props = getFileProperties();

            // Create new DirAccess, but don't upload it
            DirAccess newDirAccess = DirAccess.create(newSubfoldersKey, props, parent.pointer.filePointer.getLocation(),
                    parent.getParentKey(), newParentKey);
            // re add children
            DirAccess existing = (DirAccess) pointer.fileAccess;
            for (SymmetricLocationLink link: existing.getSubfolders()) {
                newDirAccess.addSubdir(link.targetLocation(pointer.filePointer.baseKey), newSubfoldersKey, link.target(pointer.filePointer.baseKey));
            }
            SymmetricKey filesKey = existing.getFilesKey(pointer.filePointer.baseKey);
            for (SymmetricLocationLink link: existing.getFiles()) {
                newDirAccess.addFile(link.targetLocation(filesKey), newSubfoldersKey, link.target(filesKey));
            }

            ReadableFilePointer ourNewFilePointer = pointer.filePointer.withBaseKey(newSubfoldersKey);
            readers.removeAll(readersToRemove);
            RetrievedFilePointer ourNewPointer = new RetrievedFilePointer(ourNewFilePointer, newDirAccess);
            FileTreeNode theNewUs = new FileTreeNode(ourNewPointer, ownername, readers, writers, entryWriterKey);

            // clean all subtree keys except file dataKeys (lazily re-key and re-encrypt them)
            Set<FileTreeNode> children = getChildren(context);
            for (FileTreeNode child: children) {
                child.makeDirty(context, theNewUs, readersToRemove);
            }

            // update pointer from parent to us
            ((DirAccess) parent.pointer.fileAccess).updateChildLink(parent.pointer.filePointer, this.pointer, ourNewPointer, context);

            return theNewUs;
        } else {
            // create a new baseKey == parentKey and mark the metaDataKey as dirty
            SymmetricKey parentKey = SymmetricKey.random();
            FileAccess newFileAccess = pointer.fileAccess.markDirty(pointer.filePointer, parentKey, context);

            // changing readers here will only affect the returned FileTreeNode, as the readers is derived from the entry point
            TreeSet<String> newReaders = new TreeSet<>(readers);
            newReaders.removeAll(readersToRemove);
            RetrievedFilePointer newPointer = new RetrievedFilePointer(this.pointer.filePointer.withBaseKey(parentKey), newFileAccess);

            // update link from parent folder to file to have new baseKey
            ((DirAccess) parent.pointer.fileAccess).updateChildLink(parent.pointer.filePointer, pointer, newPointer, context);

            return new FileTreeNode(newPointer, ownername, newReaders, writers, entryWriterKey);
        }
    }

    public boolean hasChildWithName(String name, UserContext context) {
        return getChildren(context).stream().filter(c -> c.props.name.equals(name)).findAny().isPresent();
    }

    public boolean removeChild(FileTreeNode child, UserContext context) throws IOException {
        return ((DirAccess)pointer.fileAccess).removeChild(child.getPointer(), pointer.filePointer, context);
    }

    public boolean addLinkTo(FileTreeNode file, UserContext context) throws IOException {
        if (!this.isDirectory())
            return false;
        if (!this.isWritable())
            return false;
        String name = file.getFileProperties().name;
        if (hasChildWithName(name, context)) {
            System.out.println("Child already exists with name: "+name);
            return false;
        }
        Location loc = file.getLocation();
        if (file.isDirectory()) {
            ((DirAccess)pointer.fileAccess).addSubdir(loc, this.getKey(), file.getKey());
        } else {
            ((DirAccess)pointer.fileAccess).addFile(loc, this.getKey(), file.getKey());
        }
        return ((DirAccess)pointer.fileAccess).commit(pointer.filePointer.owner, (User) entryWriterKey, pointer.filePointer.mapKey, context);
    }

    public String toLink() {
        return pointer.filePointer.toLink();
    }

    public boolean isWritable() {
        return entryWriterKey instanceof User;
    }

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
        return new Location(pointer.filePointer.owner, pointer.filePointer.writer, pointer.filePointer.mapKey);
    }

    public Set<Location> getChildrenLocations() {
        if (!this.isDirectory())
            return Collections.emptySet();
        return ((DirAccess)pointer.fileAccess).getChildrenLocations(pointer.filePointer.baseKey);
    }

    public Optional<FileTreeNode> retrieveParent(UserContext context) {
        if (pointer == null)
            return Optional.empty();
        SymmetricKey parentKey = getParentKey();
        try {
            RetrievedFilePointer parentRFP = pointer.fileAccess.getParent(parentKey, context);
            if (parentRFP == null)
                return Optional.of(createRoot());
            return Optional.of(new FileTreeNode(parentRFP, ownername, Collections.EMPTY_SET, Collections.EMPTY_SET, entryWriterKey));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
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

    public Set<FileTreeNode> getChildren(UserContext context) {
        if (this.props.name.equals("/"))
            return context.getChildren("/");
        if (isReadable()) {
            Set<RetrievedFilePointer> childrenRFPs = retrieveChildren(context);
            Set<FileTreeNode> newChildren = childrenRFPs.stream()
                    .map(x -> new FileTreeNode(x, ownername, readers, writers, entryWriterKey))
                    .collect(Collectors.toSet());
            return newChildren.stream().collect(Collectors.toSet());
        }
        return context.getChildren(getPath(context));
    }

    private Set<RetrievedFilePointer> retrieveChildren(UserContext context) {
        ReadableFilePointer filePointer = pointer.filePointer;
        FileAccess fileAccess = pointer.fileAccess;
        SymmetricKey rootDirKey = filePointer.baseKey;

        if (isReadable()) {
            try {
                return ((DirAccess) fileAccess).getChildren(context, rootDirKey);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
        throw new IllegalStateException("No credentials to retrieve children!");
    }

    public String getOwner() {
        return ownername;
    }

    public boolean isDirectory() {
        boolean isNull = pointer == null;
        return isNull || pointer.fileAccess.isDirectory();
    }

    public boolean uploadFile(String filename, File f, UserContext context, Consumer<Long> monitor, peergos.user.fs.Fragmenter fragmenter) throws IOException {
        return uploadFile(filename, new ResetableFileInputStream(f), f.length(), context,  monitor, fragmenter);
    }

    public boolean uploadFile(String filename, InputStream fileData, long length, UserContext context, Consumer<Long> monitor, peergos.user.fs.Fragmenter fragmenter) throws IOException {
        return uploadFile(filename, fileData, 0, length, Optional.empty(), context, monitor, fragmenter);
    }

    public boolean isDirty() {
        return pointer.fileAccess.isDirty(pointer.filePointer.baseKey);
    }

    public FileTreeNode clean(UserContext context, FileTreeNode parent, peergos.user.fs.Fragmenter fragmenter) throws IOException {
        if (!isDirty())
            return this;
        if (isDirectory()) {
            throw new IllegalStateException("Unimplemented directory cleaning!");
        } else {
            FileProperties props = getFileProperties();
            SymmetricKey baseKey = pointer.filePointer.baseKey;
            // stream download and re-encrypt with new metaKey
            InputStream in = getInputStream(context, l -> {});
            byte[] tmp = new byte[16];
            new Random().nextBytes(tmp);
            String tmpFilename = ArrayOps.bytesToHex(tmp) + ".tmp";

            boolean upload = parent.uploadFile(tmpFilename, in, 0, props.size, Optional.of(baseKey), context, l -> {}, fragmenter);
            Optional<FileTreeNode> tmpChild = parent.getDescendentByPath(tmpFilename, context);
            boolean rename = tmpChild.get().rename(props.name, context, parent, true);
            return parent.getDescendentByPath(props.name, context).get();
        }
    }

    public boolean uploadFile(String filename, InputStream fileData, long startIndex, long endIndex,
                              UserContext context, Consumer<Long> monitor, peergos.user.fs.Fragmenter fragmenter) throws IOException {
        return uploadFile(filename, fileData, startIndex, endIndex, Optional.empty(), context, monitor, fragmenter);
    }

    public boolean uploadFile(String filename, InputStream fileData, long startIndex, long endIndex, Optional<SymmetricKey> baseKey,
                              UserContext context, Consumer<Long> monitor, peergos.user.fs.Fragmenter fragmenter) throws IOException {
        if (!isLegalName(filename))
            return false;
        Optional<FileTreeNode> childOpt = getDescendentByPath(filename, context);
        if (childOpt.isPresent()) {
            return updateExistingChild(childOpt.get(), fileData, startIndex, endIndex, context, monitor, fragmenter);
        }
        if (startIndex > 0) {
            // TODO if startIndex > 0 prepend with a zero section
            throw new IllegalStateException("Unimplemented!");
        }
        SymmetricKey fileKey = baseKey.orElseGet(() -> SymmetricKey.random());
        SymmetricKey fileMetaKey = SymmetricKey.random();
        SymmetricKey rootRKey = pointer.filePointer.baseKey;
        byte[] dirMapKey = pointer.filePointer.mapKey;
        DirAccess dirAccess = (DirAccess) pointer.fileAccess;
        SymmetricKey dirParentKey = dirAccess.getParentKey(rootRKey);
        Location parentLocation = getLocation();

        byte[] thumbData = generateThumbnail(fileData, filename);
        fileData.reset();
        FileProperties fileProps = new FileProperties(filename, endIndex, LocalDateTime.now(), false, Optional.of(thumbData));
        FileUploader chunks = new FileUploader(filename, fileData, startIndex, endIndex, fileKey, fileMetaKey, parentLocation, dirParentKey, monitor, fileProps,
                EncryptedChunk.ERASURE_ORIGINAL, EncryptedChunk.ERASURE_ALLOWED_FAILURES);
        byte[] mapKey = context.randomBytes(32);
        Location nextChunkLocation = new Location(getLocation().owner, getLocation().writer, mapKey);
        Location fileLocation = chunks.upload(context, parentLocation.owner, (User)entryWriterKey, nextChunkLocation);
        dirAccess.addFile(fileLocation, rootRKey, fileKey);
        return context.uploadChunk(dirAccess, parentLocation.owner, (User) entryWriterKey, dirMapKey, Collections.emptyList());
    }

    private boolean updateExistingChild(FileTreeNode existingChild, InputStream fileData, long startIndex, long endIndex, UserContext context,
                                     Consumer<Long> monitor, peergos.user.fs.Fragmenter fragmenter) throws IOException {
        String filename = existingChild.getFileProperties().name;
        System.out.println("Overwriting section ["+Long.toHexString(startIndex)+", "+Long.toHexString(endIndex)+"] of child with name: "+filename);
        FileTreeNode child = existingChild.isDirty() ? existingChild.clean(context, this, fragmenter) : existingChild;
        FileProperties childProps = child.getFileProperties();
        long filesSize = childProps.size;
        FileRetriever retriever = child.getRetriever();
        SymmetricKey baseKey = child.pointer.filePointer.baseKey;
        SymmetricKey dataKey = child.pointer.fileAccess.getMetaKey(baseKey);

        if (startIndex > filesSize) {
            // append with zeroes up until startIndex
            updateExistingChild(existingChild, new InputStream() {
                @Override
                public int read() throws IOException {
                    return 0;
                }
            }, filesSize, startIndex, context, l -> {}, fragmenter);
        }

        for (; startIndex < endIndex; startIndex = startIndex + Chunk.MAX_SIZE - (startIndex % Chunk.MAX_SIZE)) {

            LocatedChunk currentOriginal = retriever.getChunkInputStream(context, dataKey, startIndex, filesSize, child.getLocation(), monitor).get();
            Optional<Location> nextChunkLocationOpt = retriever.getLocationAt(child.getLocation(), startIndex + Chunk.MAX_SIZE, context);
            Supplier<Location> locationSupplier = () -> new Location(getLocation().owner, getLocation().writer, context.randomBytes(32));
            Location nextChunkLocation = nextChunkLocationOpt.orElseGet(locationSupplier);

            System.out.println("********** Writing to chunk at mapkey: "+ArrayOps.bytesToHex(currentOriginal.location.mapKey) + " next: "+nextChunkLocation);
            // modify chunk, re-encrypt and upload
            int internalStart = (int) (startIndex % Chunk.MAX_SIZE);
            int internalEnd = endIndex - (startIndex - internalStart) > Chunk.MAX_SIZE ?
                    Chunk.MAX_SIZE : (int)(endIndex - (startIndex - internalStart));
            byte[] raw = currentOriginal.chunk.data();
            // extend data array if necessary
            if (raw.length < internalEnd)
                raw = Arrays.copyOfRange(raw, 0, internalEnd);
            fileData.read(raw, internalStart, internalEnd - internalStart);

            byte[] nonce = new byte[TweetNaCl.SECRETBOX_NONCE_BYTES];
            context.random.randombytes(nonce, 0, nonce.length);
            Chunk updated = new Chunk(raw, dataKey, currentOriginal.location.mapKey, nonce);
            LocatedChunk located = new LocatedChunk(currentOriginal.location, updated);
            FileProperties newProps = new FileProperties(childProps.name, endIndex > filesSize ? endIndex : filesSize,
                    LocalDateTime.now(), childProps.isHidden, childProps.thumbnail);
            FileUploader.uploadChunk((User)entryWriterKey, newProps, getLocation(), getParentKey(), baseKey, located,
                    EncryptedChunk.ERASURE_ORIGINAL, EncryptedChunk.ERASURE_ALLOWED_FAILURES, nextChunkLocation, context, monitor);

            //update indices to be relative to next chunk
            if (startIndex + internalEnd - internalStart > filesSize) {
                filesSize = startIndex + internalEnd - internalStart;
                if (startIndex + internalEnd - internalStart > Chunk.MAX_SIZE) {
                    // update file size in FileProperties of first chunk
                    Optional<FileTreeNode> updatedChild = getChildren(context).stream().filter(f -> f.getFileProperties().name.equals(filename)).findAny();
                    boolean b = updatedChild.get().setProperties(child.getFileProperties().withSize(endIndex), context, this);
                    if (!b)
                        throw new IllegalStateException("Failed to update file properties for "+child);
                }
            }
        }
        return true;
    }

    static boolean isLegalName(String name) {
        return !name.contains("/");
    }

    public Optional<ReadableFilePointer> mkdir(String newFolderName, UserContext context, boolean isSystemFolder, SafeRandom random) throws IOException {
        return mkdir(newFolderName, context, null, isSystemFolder, random);
    }

    public Optional<ReadableFilePointer> mkdir(String newFolderName, UserContext context, SymmetricKey requestedBaseSymmetricKey,
                                               boolean isSystemFolder, SafeRandom random) throws IOException {
        if (!this.isDirectory())
            return Optional.empty();
        if (!isLegalName(newFolderName))
            return Optional.empty();
        if (hasChildWithName(newFolderName, context)) {
            System.out.println("Child already exists with name: " + newFolderName);
            return Optional.empty();
        }
        ReadableFilePointer dirPointer = pointer.filePointer;
        DirAccess dirAccess = (DirAccess)pointer.fileAccess;
        SymmetricKey rootDirKey = dirPointer.baseKey;
        return Optional.of(dirAccess.mkdir(newFolderName, context, (User) entryWriterKey, dirPointer.mapKey, rootDirKey,
                requestedBaseSymmetricKey, isSystemFolder, random));
    }

    public boolean rename(String newFilename, UserContext context, FileTreeNode parent) throws IOException {
        return rename(newFilename, context, parent, false);
    }

    public boolean rename(String newFilename, UserContext context, FileTreeNode parent, boolean overwrite) throws IOException {
        if (! isLegalName(newFilename))
            return false;
        Optional<FileTreeNode> existing = parent == null ? Optional.empty() : parent.getDescendentByPath(newFilename, context);
        if (existing.isPresent() && ! overwrite)
            return false;

        if (overwrite && existing.isPresent())
            existing.get().remove(context, parent);

        //get current props
        ReadableFilePointer filePointer = pointer.filePointer;
        SymmetricKey baseKey = filePointer.baseKey;
        FileAccess fileAccess = pointer.fileAccess;

        SymmetricKey key = this.isDirectory() ? fileAccess.getParentKey(baseKey) : baseKey;
        FileProperties currentProps = fileAccess.getFileProperties(key);

        FileProperties newProps = new FileProperties(newFilename, currentProps.size, currentProps.modified, currentProps.isHidden, currentProps.thumbnail);

        return fileAccess.rename(writableFilePointer(), newProps, context);
    }

    public boolean setProperties(FileProperties updatedProperties, UserContext context, FileTreeNode parent) throws IOException {
        String newName = updatedProperties.name;
        if (!isLegalName(newName))
            return false;
        if (parent != null && parent.hasChildWithName(newName, context) &&
                !parent.getChildrenLocations().stream()
                        .map(l -> new ByteArrayWrapper(l.mapKey))
                        .collect(Collectors.toSet())
                        .contains(new ByteArrayWrapper(pointer.filePointer.getLocation().mapKey)))
            return false;
        FileAccess fileAccess = pointer.fileAccess;

        return fileAccess.rename(writableFilePointer(), updatedProperties, context);
    }

    private ReadableFilePointer writableFilePointer() {
        ReadableFilePointer filePointer = pointer.filePointer;
        FileAccess fileAccess = pointer.fileAccess;
        SymmetricKey baseKey = filePointer.baseKey;
        return new ReadableFilePointer(filePointer.owner, entryWriterKey, filePointer.mapKey, baseKey);
    }

    public UserPublicKey getEntryWriterKey() {
        return entryWriterKey;
    }

    public boolean copyTo(FileTreeNode target, UserContext context) throws IOException {
        if (! target.isDirectory())
            throw new IllegalStateException("CopyTo target "+ target +" must be a directory");
        if (target.hasChildWithName(getFileProperties().name, context))
            return false;
        //make new FileTreeNode pointing to the same file, but with a different location
        byte[] newMapKey = new byte[32];
        context.random.randombytes(newMapKey, 0, 32);
        SymmetricKey ourBaseKey = this.getKey();
        // a file baseKey is the key for the chunk, which hasn't changed, so this must stay the same
        SymmetricKey newBaseKey = this.isDirectory() ? SymmetricKey.random() : ourBaseKey;
        ReadableFilePointer newRFP = new ReadableFilePointer(context.user, target.getEntryWriterKey(), newMapKey, newBaseKey);
        Location newParentLocation = target.getLocation();
        SymmetricKey newParentParentKey = target.getParentKey();

        FileAccess newAccess = pointer.fileAccess.copyTo(ourBaseKey, newBaseKey, newParentLocation, newParentParentKey, (User)target.getEntryWriterKey(), newMapKey, context);
        // upload new metadatablob
        RetrievedFilePointer newRetrievedFilePointer = new RetrievedFilePointer(newRFP, newAccess);
        FileTreeNode newFileTreeNode = new FileTreeNode(newRetrievedFilePointer, context.username,
                Collections.emptySet(), Collections.emptySet(), target.getEntryWriterKey());
        return target.addLinkTo(newFileTreeNode, context);
    }

    public boolean remove(UserContext context, FileTreeNode parent) throws IOException {
        if (parent != null)
            parent.removeChild(this, context);
        return new RetrievedFilePointer(writableFilePointer(), pointer.fileAccess).remove(context, null);
    }

    public InputStream getInputStream(UserContext context, Consumer<Long> monitor) throws IOException {
        return getInputStream(context, getFileProperties().size, monitor);
    }

    public InputStream getInputStream(UserContext context, long fileSize, Consumer<Long> monitor) throws IOException {
        SymmetricKey baseKey = pointer.filePointer.baseKey;
        SymmetricKey dataKey = pointer.fileAccess.getMetaKey(baseKey);
        return pointer.fileAccess.retriever().getFile(context, dataKey, fileSize, getLocation(), monitor);
    }

    private FileRetriever getRetriever() {
        return pointer.fileAccess.retriever();
    }

    public FileProperties getFileProperties() {
        return props;
    }

    public String toString() {
        return getFileProperties().name;
    }

    public static FileTreeNode createRoot() {
        return new FileTreeNode(null, null, Collections.EMPTY_SET, Collections.EMPTY_SET, null);
    }

    public byte[] generateThumbnail(InputStream imageBlob, String fileName) {
        try {
            if(!isImage(imageBlob)) {
                return new byte[0];
            }
            BufferedImage image = ImageIO.read(imageBlob);
            BufferedImage thumbnailImage = new BufferedImage(THUMBNAIL_SIZE, THUMBNAIL_SIZE, image.getType());
            Graphics2D g = thumbnailImage.createGraphics();
            g.setComposite(AlphaComposite.Src);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(image, 0, 0, THUMBNAIL_SIZE, THUMBNAIL_SIZE, null);
            g.dispose();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(thumbnailImage, "JPG", baos);
            baos.close();
            return baos.toByteArray();
        }
        catch (IOException ioe) {
            ioe.printStackTrace();
        }
        return new byte[0];
    }

    private boolean isImage(InputStream imageBlob)
    {
        try {
            byte[] data = new byte[HEADER_BYTES_TO_IDENTIFY_IMAGE_FILE];
            if(imageBlob.read(data, 0, HEADER_BYTES_TO_IDENTIFY_IMAGE_FILE) < HEADER_BYTES_TO_IDENTIFY_IMAGE_FILE) {
                return false;
            }
            if (!Arrays.equals(Arrays.copyOfRange(data, 0, BMP.length), BMP)
                    && !Arrays.equals(Arrays.copyOfRange(data, 0, GIF.length), GIF)
                    && !Arrays.equals(Arrays.copyOfRange(data, 0, PNG.length), PNG)
                    && !Arrays.equals(Arrays.copyOfRange(data, 0, 2), JPEG))
                return false;
            return true;
        } catch (IOException ex) {
            ex.printStackTrace();
        }	finally {
            try{
                if(imageBlob != null) {
                    imageBlob.reset();
                }
            }catch(Exception e){}
        }
        return false;
    }
}
