package peergos.user.fs;

import peergos.crypto.*;
import peergos.crypto.random.*;
import peergos.crypto.symmetric.*;
import peergos.user.*;
import peergos.util.*;

import java.io.*;
import java.time.*;
import java.util.*;
import java.util.stream.*;

public class DirAccess extends FileAccess {

    public static final int MAX_CHILD_LINKS_PER_BLOB = 10;
    public final SymmetricLink subfolders2files, subfolders2parent;
    private List<SymmetricLocationLink> subfolders, files;
    private final Optional<SymmetricLocationLink> moreFolderContents;

    public DirAccess(SymmetricLink subfolders2files, SymmetricLink subfolders2parent, List<SymmetricLocationLink> subfolders,
                     List<SymmetricLocationLink> files, SymmetricLink parent2meta, byte[] properties,
                     FileRetriever retriever, SymmetricLocationLink parentLink, Optional<SymmetricLocationLink> moreFolderContents) {
        super(parent2meta, properties, retriever, parentLink);
        this.subfolders2files = subfolders2files;
        this.subfolders2parent = subfolders2parent;
        this.subfolders = subfolders;
        this.files = files;
        this.moreFolderContents = moreFolderContents;
    }

    public void serialize(DataSink bout) throws IOException {
        super.serialize(bout);
        bout.writeArray(subfolders2parent.serialize());
        bout.writeArray(subfolders2files.serialize());
        bout.writeInt(0);
        bout.writeInt(this.subfolders.size());
        subfolders.forEach(x -> bout.writeArray(x.serialize()));
        bout.writeInt(this.files.size());
        files.forEach(x -> bout.writeArray(x.serialize()));
        bout.writeBoolean(moreFolderContents.isPresent());
        if (moreFolderContents.isPresent())
            bout.writeArray(moreFolderContents.get().serialize());
    }

    public List<SymmetricLocationLink> getSubfolders() {
        return Collections.unmodifiableList(subfolders);
    }

    public List<SymmetricLocationLink> getFiles() {
        return Collections.unmodifiableList(files);
    }

    public boolean isDirty(SymmetricKey baseKey) {
        throw new IllegalStateException("Unimplemented!");
    }

    public boolean rename(ReadableFilePointer writableFilePointer, FileProperties newProps, UserContext context) throws IOException {
        if (!writableFilePointer.isWritable())
            throw new IllegalStateException("Need a writable pointer!");
        SymmetricKey metaKey;
        SymmetricKey parentKey = subfolders2parent.target(writableFilePointer.baseKey);
        metaKey = this.getMetaKey(parentKey);
        byte[] metaNonce = metaKey.createNonce();
        DirAccess dira = new DirAccess(this.subfolders2files, this.subfolders2parent,
                this.subfolders, this.files, this.parent2meta,
                ArrayOps.concat(metaNonce, metaKey.encrypt(newProps.serialize(), metaNonce)),
                null,
                parentLink,
                moreFolderContents
        );
        return context.uploadChunk(dira, writableFilePointer.owner, (User)writableFilePointer.writer, writableFilePointer.mapKey, Collections.EMPTY_LIST);
    }

    public void addFile(Location location, SymmetricKey ourSubfolders, SymmetricKey targetParent, ReadableFilePointer ourPointer,
                        UserContext context) throws IOException {
        if (subfolders.size() + files.size() >= MAX_CHILD_LINKS_PER_BLOB) {
            List<Map.Entry<ReadableFilePointer, DirAccess>> nextMetablob = getNextMetablob(ourSubfolders, context);
            if (nextMetablob.size() >= 1) {
                Map.Entry<ReadableFilePointer, DirAccess> pair = nextMetablob.get(0);
                ReadableFilePointer nextPointer = pair.getKey();
                DirAccess nextBlob = pair.getValue();
                nextBlob.addFile(location, ourSubfolders, targetParent, nextPointer, context);
            } else {
                // create and upload new metadata blob
                SymmetricKey nextSubfoldersKey = SymmetricKey.random();
                SymmetricKey ourParentKey = subfolders2parent.target(ourSubfolders);
                DirAccess next = DirAccess.create(nextSubfoldersKey, FileProperties.EMPTY,
                        parentLink.targetLocation(ourParentKey), parentLink.target(ourParentKey), ourParentKey);
                byte[] nextMapKey = context.randomBytes(32);
                ReadableFilePointer nextPointer = new ReadableFilePointer(ourPointer.owner, ourPointer.writer, nextMapKey, nextSubfoldersKey);
                next.addFile(location, nextSubfoldersKey, targetParent, nextPointer, context);
                next.commit(ourPointer.owner, (User)ourPointer.writer, nextMapKey, context);
                // re-upload us with the link to the next DirAccess
                DirAccess newUs = new DirAccess(subfolders2files, subfolders2parent, subfolders, files, parent2meta, properties, retriever,
                        parentLink, Optional.of(SymmetricLocationLink.create(ourSubfolders, nextSubfoldersKey, nextPointer.getLocation())));
                newUs.commit(ourPointer.owner, (User)ourPointer.writer, ourPointer.mapKey, context);
            }
        } else {
            SymmetricKey filesKey = this.subfolders2files.target(ourSubfolders);
            this.files.add(SymmetricLocationLink.create(filesKey, targetParent, location));
        }
    }

    // returns new version of this directory
    public DirAccess addSubdir(Location location, SymmetricKey ourSubfolders, SymmetricKey targetBaseKey, ReadableFilePointer ourPointer,
                          UserContext context) throws IOException {
        if (subfolders.size() + files.size() >= MAX_CHILD_LINKS_PER_BLOB) {
            List<Map.Entry<ReadableFilePointer, DirAccess>> nextMetablob = getNextMetablob(ourSubfolders, context);
            if (nextMetablob.size() >= 1) {
                Map.Entry<ReadableFilePointer, DirAccess> pair = nextMetablob.get(0);
                ReadableFilePointer nextPointer = pair.getKey();
                DirAccess nextBlob = pair.getValue();
                nextBlob.addSubdir(location, ourSubfolders, targetBaseKey, nextPointer, context);
                nextBlob.commit(nextPointer.owner, (User)ourPointer.writer, nextPointer.mapKey, context);
            } else {
                // create and upload new metadata blob
                SymmetricKey nextSubfoldersKey = SymmetricKey.random();
                SymmetricKey ourParentKey = subfolders2parent.target(ourSubfolders);
                DirAccess next = DirAccess.create(nextSubfoldersKey, FileProperties.EMPTY,
                        parentLink != null ? parentLink.targetLocation(ourParentKey) : null,
                        parentLink != null ? parentLink.target(ourParentKey) : null, ourParentKey);
                byte[] nextMapKey = context.randomBytes(32);
                ReadableFilePointer nextPointer = new ReadableFilePointer(ourPointer.owner, ourPointer.writer, nextMapKey, nextSubfoldersKey);
                next.addSubdir(location, nextSubfoldersKey, targetBaseKey, nextPointer, context);
                next.commit(ourPointer.owner, (User)ourPointer.writer, nextMapKey, context);
                // re-upload us with the link to the next DirAccess
                DirAccess newUs = new DirAccess(subfolders2files, subfolders2parent, subfolders, files, parent2meta, properties, retriever,
                        parentLink, Optional.of(SymmetricLocationLink.create(ourSubfolders, nextSubfoldersKey, nextPointer.getLocation())));
                return newUs;
            }
        } else {
            this.subfolders.add(SymmetricLocationLink.create(ourSubfolders, targetBaseKey, location));
        }
        return this;
    }

    private List<Map.Entry<ReadableFilePointer, DirAccess>> getNextMetablob(SymmetricKey subfoldersKey, UserContext context) throws IOException {
        if (!moreFolderContents.isPresent())
            return Collections.emptyList();
        Map<ReadableFilePointer, FileAccess> retrieved = context.retrieveAllMetadata(Arrays.asList(moreFolderContents.get()), subfoldersKey);
        return retrieved.entrySet().stream()
                .map(e -> new AbstractMap.SimpleEntry<>(e.getKey(), (DirAccess) e.getValue()))
                .collect(Collectors.toList());
    }

    public void updateChildLink(ReadableFilePointer ourPointer, RetrievedFilePointer original, RetrievedFilePointer modified, UserContext context) throws IOException {
        removeChild(original, ourPointer, context);
        Location loc = modified.filePointer.getLocation();
        DirAccess toUpdate = this;
        if (modified.fileAccess.isDirectory())
            toUpdate = addSubdir(loc, ourPointer.baseKey, modified.filePointer.baseKey, ourPointer, context);
        else
            addFile(loc, ourPointer.baseKey, modified.filePointer.baseKey, ourPointer, context);
        context.uploadChunk(toUpdate, ourPointer.owner, (User) ourPointer.writer, ourPointer.mapKey, Collections.EMPTY_LIST);
    }

    public boolean removeChild(RetrievedFilePointer childRetrievedPointer, ReadableFilePointer ourPointer, UserContext context) throws IOException {
        if (childRetrievedPointer.fileAccess.isDirectory()) {
            this.subfolders = subfolders.stream().filter(e -> {
                try {
                    Location target = e.targetLocation(ourPointer.baseKey);
                    boolean keep = true;
                    if (Arrays.equals(target.mapKey, childRetrievedPointer.filePointer.mapKey))
                        if (Arrays.equals(target.writer.getPublicKeys(), childRetrievedPointer.filePointer.writer.getPublicKeys()))
                            if (Arrays.equals(target.owner.getPublicKeys(), childRetrievedPointer.filePointer.owner.getPublicKeys()))
                                keep = false;
                    return keep;
                } catch (TweetNaCl.InvalidCipherTextException ex) {
                    ex.printStackTrace();
                    return false;
                } catch (Exception f) {
                    return false;
                }
            }).collect(Collectors.toList());
        } else {
            files = files.stream().filter(e -> {
            SymmetricKey filesKey = subfolders2files.target(ourPointer.baseKey);
                try {
                    Location target = e.targetLocation(filesKey);
                    boolean keep = true;
                    if (Arrays.equals(target.mapKey, childRetrievedPointer.filePointer.mapKey))
                        if (Arrays.equals(target.writer.getPublicKeys(), childRetrievedPointer.filePointer.writer.getPublicKeys()))
                            if (Arrays.equals(target.owner.getPublicKeys(), childRetrievedPointer.filePointer.owner.getPublicKeys()))
                                keep = false;
                    return keep;
                } catch (TweetNaCl.InvalidCipherTextException ex) {
                    ex.printStackTrace();
                    return false;
                } catch (Exception f) {
                    return false;
                }
            }).collect(Collectors.toList());
        }
        return context.uploadChunk(this, ourPointer.owner, (User) ourPointer.writer, ourPointer.mapKey, Collections.EMPTY_LIST);
    }

    // 0=FILE, 1=DIR
    public byte getType() {
        return 1;
    }

    // returns [RetrievedFilePointer]
    public Set<RetrievedFilePointer> getChildren(UserContext context, SymmetricKey baseKey) throws IOException {
        Map<ReadableFilePointer, FileAccess> subdirs = context.retrieveAllMetadata(this.subfolders, baseKey);
        Map<ReadableFilePointer, FileAccess> files = context.retrieveAllMetadata(this.files, this.subfolders2files.target(baseKey));
        Map<ReadableFilePointer, FileAccess> moreChildrenSource = moreFolderContents.isPresent() ?
                context.retrieveAllMetadata(Arrays.asList(moreFolderContents.get()), baseKey) : Collections.emptyMap();
        Stream<RetrievedFilePointer> moreChildren = moreChildrenSource.entrySet().stream().flatMap(e -> {
            try {
                return ((DirAccess) e.getValue()).getChildren(context, e.getKey().baseKey).stream();
            } catch (IOException ex) {
                return Stream.empty();
            }
        });
        return Stream.concat(
                Stream.concat(
                        subdirs.entrySet().stream(),
                        files.entrySet().stream())
                        .map(e -> new RetrievedFilePointer(e.getKey(), e.getValue())),
                moreChildren)
                .collect(Collectors.toSet());
    }

    public Set<Location> getChildrenLocations(SymmetricKey baseKey) {
        SymmetricKey filesKey = this.subfolders2files.target(baseKey);
        return Stream.concat(subfolders.stream().map(d -> d.targetLocation(baseKey)),
                files.stream().map(f -> f.targetLocation(filesKey)))
                .collect(Collectors.toSet());
    }

    public SymmetricKey getParentKey(SymmetricKey subfoldersKey) {
        return this.subfolders2parent.target(subfoldersKey);
    }

    public SymmetricKey getFilesKey(SymmetricKey subfoldersKey) {
        return this.subfolders2files.target(subfoldersKey);
    }

    // returns pointer to new child directory
    public ReadableFilePointer mkdir(String name, UserContext userContext, User writer, byte[] ourMapKey,
                                     SymmetricKey baseKey, SymmetricKey optionalBaseKey, boolean isSystemFolder, SafeRandom random) throws IOException {
        SymmetricKey dirReadKey = optionalBaseKey != null ? optionalBaseKey : SymmetricKey.random();
        byte[] dirMapKey = new byte[32]; // root will be stored under this in the btree
        random.randombytes(dirMapKey, 0, 32);
        SymmetricKey ourParentKey = this.getParentKey(baseKey);
        Location ourLocation = new Location(userContext.user, writer, ourMapKey);
        DirAccess dir = DirAccess.create(dirReadKey, new FileProperties(name, 0, LocalDateTime.now(), isSystemFolder, Optional.empty()), ourLocation, ourParentKey, null);
        boolean success = userContext.uploadChunk(dir, userContext.user, writer, dirMapKey, Collections.EMPTY_LIST);
        if (success) {
            ReadableFilePointer ourPointer = new ReadableFilePointer(userContext.user, writer, ourMapKey, baseKey);
            DirAccess modified = addSubdir(new Location(userContext.user, writer, dirMapKey), baseKey, dirReadKey, ourPointer, userContext);
            // now upload the changed metadata blob for dir
            modified.commit(userContext.user, writer, ourMapKey, userContext);
            return new ReadableFilePointer(userContext.user, writer, dirMapKey, dirReadKey);
        }
        throw new IllegalStateException("Couldn't upload directory metadata!");
    }

    public boolean commit(UserPublicKey owner, User writer, byte[] ourMapKey, UserContext userContext) throws IOException {
        return userContext.uploadChunk(this, userContext.user, writer, ourMapKey, Collections.EMPTY_LIST);
    }

    public DirAccess copyTo(SymmetricKey baseKey, SymmetricKey newBaseKey, Location parentLocation,
                            SymmetricKey parentparentKey, User entryWriterKey, byte[] newMapKey, UserContext context) throws IOException {
        SymmetricKey parentKey = getParentKey(baseKey);
        FileProperties props = getFileProperties(parentKey);
        DirAccess da = DirAccess.create(newBaseKey, props, parentLocation, parentparentKey, parentKey);
        SymmetricKey ourNewParentKey = da.getParentKey(newBaseKey);
        Location ourNewLocation = new Location(context.user, entryWriterKey, newMapKey);

        Set<RetrievedFilePointer> RFPs = this.getChildren(context, baseKey);
        // upload new metadata blob for each child and re-add child
        for (RetrievedFilePointer rfp: RFPs) {
            SymmetricKey newChildBaseKey = rfp.fileAccess.isDirectory() ? SymmetricKey.random() : rfp.filePointer.baseKey;
            byte[] newChildMapKey = new byte[32];
            context.random.randombytes(newChildMapKey, 0, 32);
            Location newChildLocation = new Location(context.user, entryWriterKey, newChildMapKey);
            FileAccess newChildFileAccess = rfp.fileAccess.copyTo(rfp.filePointer.baseKey, newChildBaseKey,
                    ourNewLocation, ourNewParentKey, entryWriterKey, newChildMapKey, context);
            ReadableFilePointer ourNewPointer = new ReadableFilePointer(ourNewLocation.owner, entryWriterKey, newMapKey, newBaseKey);
            if (newChildFileAccess.isDirectory())
                da = da.addSubdir(newChildLocation, newBaseKey, newChildBaseKey, ourNewPointer, context);
            else
                da.addFile(newChildLocation, newBaseKey, newChildBaseKey, ourNewPointer, context);
        }
        context.uploadChunk(da, context.user, entryWriterKey, newMapKey, Collections.EMPTY_LIST);
        return da;
    }

    public static DirAccess deserialize(FileAccess base, DataSource bin) throws IOException {
        byte[] s2p = bin.readArray();
        byte[] s2f = bin.readArray();

        int nSharingKeys = bin.readInt();
        List<SymmetricLocationLink> files = new ArrayList<>(), subfolders = new ArrayList<>();
        int nsubfolders = bin.readInt();
        for (int i=0; i < nsubfolders; i++)
            subfolders.add(SymmetricLocationLink.deserialize(bin.readArray()));
        int nfiles = bin.readInt();
        for (int i=0; i < nfiles; i++)
            files.add(SymmetricLocationLink.deserialize(bin.readArray()));
        boolean hasMoreChildren = bin.readBoolean();
        Optional<SymmetricLocationLink> moreChildren = hasMoreChildren ? Optional.of(SymmetricLocationLink.deserialize(bin.readArray())) : Optional.empty();
        return new DirAccess(new SymmetricLink(s2f), new SymmetricLink(s2p),
                subfolders, files, base.parent2meta, base.properties, base.retriever, base.parentLink, moreChildren);
    }

    public static DirAccess create(SymmetricKey subfoldersKey, FileProperties metadata, Location parentLocation, SymmetricKey parentParentKey, SymmetricKey parentKey) {
        SymmetricKey metaKey = SymmetricKey.random();
        if (parentKey == null)
            parentKey = SymmetricKey.random();
        SymmetricKey filesKey = SymmetricKey.random();
        byte[] metaNonce = metaKey.createNonce();
        SymmetricLocationLink parentLink = parentLocation == null ? null : SymmetricLocationLink.create(parentKey, parentParentKey, parentLocation);
        return new DirAccess(SymmetricLink.fromPair(subfoldersKey, filesKey),
                SymmetricLink.fromPair(subfoldersKey, parentKey),
                new ArrayList<>(), new ArrayList<>(),
                SymmetricLink.fromPair(parentKey, metaKey),
                ArrayOps.concat(metaNonce, metaKey.encrypt(metadata.serialize(), metaNonce)),
                null,
                parentLink,
                Optional.empty()
        );
    }
}
