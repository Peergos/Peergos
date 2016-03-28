package peergos.user.fs;

import peergos.crypto.*;
import peergos.crypto.symmetric.*;
import peergos.user.*;
import peergos.util.*;

import java.io.*;
import java.time.*;
import java.util.*;
import java.util.stream.*;

public class DirAccess extends FileAccess {

    private final SymmetricLink subfolders2files, subfolders2parent;
    private List<SymmetricLocationLink> subfolders, files;

    public DirAccess(SymmetricLink subfolders2files, SymmetricLink subfolders2parent, List<SymmetricLocationLink> subfolders,
                     List<SymmetricLocationLink> files, SymmetricLink parent2meta, byte[] properties,
                     FileRetriever retriever, SymmetricLocationLink parentLink) {
        super(parent2meta, properties, retriever, parentLink);
        this.subfolders2files = subfolders2files;
        this.subfolders2parent = subfolders2parent;
        this.subfolders = subfolders;
        this.files = files;
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
                parentLink
        );
        return context.uploadChunk(dira, writableFilePointer.owner, (User)writableFilePointer.writer, writableFilePointer.mapKey, Collections.EMPTY_LIST);
    }

    public void addFile(Location location, SymmetricKey ourSubfolders, SymmetricKey targetParent) {
        SymmetricKey filesKey = this.subfolders2files.target(ourSubfolders);
        this.files.add(SymmetricLocationLink.create(filesKey, targetParent, location));
    }

    public boolean removeChild(RetrievedFilePointer childRetrievedPointer, ReadableFilePointer readablePointer, UserContext context) throws IOException {
        if (childRetrievedPointer.fileAccess.isDirectory()) {
            this.subfolders = subfolders.stream().filter(e -> {
                try {
                    Location target = e.targetLocation(readablePointer.baseKey);
                    boolean keep = true;
                    if (Arrays.equals(target.mapKey, childRetrievedPointer.filePointer.mapKey))
                        if (Arrays.equals(target.writer.getPublicKeys(), childRetrievedPointer.filePointer.writer.getPublicKeys()))
                            if (Arrays.equals(target.owner.getPublicKeys(), childRetrievedPointer.filePointer.owner.getPublicKeys()))
                                keep = false;
                    return keep;
                } catch (Exception f) {
                    return false;
                }
            }).collect(Collectors.toList());
        } else {
            files = files.stream().filter(e -> {
            SymmetricKey filesKey = subfolders2files.target(readablePointer.baseKey);
                try {
                    Location target = e.targetLocation(filesKey);
                    boolean keep = true;
                    if (Arrays.equals(target.mapKey, childRetrievedPointer.filePointer.mapKey))
                        if (Arrays.equals(target.writer.getPublicKeys(), childRetrievedPointer.filePointer.writer.getPublicKeys()))
                            if (Arrays.equals(target.owner.getPublicKeys(), childRetrievedPointer.filePointer.owner.getPublicKeys()))
                                keep = false;
                    return keep;
                } catch (Exception f) {
                    return false;
                }
            }).collect(Collectors.toList());
        }
        return context.uploadChunk(this, readablePointer.owner, (User) readablePointer.writer, readablePointer.mapKey, Collections.EMPTY_LIST);
    }

    // 0=FILE, 1=DIR
    public byte getType() {
        return 1;
    }

    // returns [RetrievedFilePointer]
    public Set<RetrievedFilePointer> getChildren(UserContext context, SymmetricKey baseKey) throws IOException {
        Map<ReadableFilePointer, FileAccess> subdirs = context.retrieveAllMetadata(this.subfolders, baseKey);
        Map<ReadableFilePointer, FileAccess> files = context.retrieveAllMetadata(this.files, this.subfolders2files.target(baseKey));
        return Stream.concat(subdirs.entrySet().stream(), files.entrySet().stream())
                .map(e -> new RetrievedFilePointer(e.getKey(), e.getValue()))
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

    //String, UserContext, User ->
    public ReadableFilePointer mkdir(String name, UserContext userContext, User writer, byte[] ourMapKey,
                                     SymmetricKey baseKey, SymmetricKey optionalBaseKey, boolean isSystemFolder) throws IOException {
        SymmetricKey dirReadKey = optionalBaseKey != null ? optionalBaseKey : SymmetricKey.random();
        byte[] dirMapKey = TweetNaCl.securedRandom(32); // root will be stored under this in the core node
        SymmetricKey ourParentKey = this.getParentKey(baseKey);
        Location ourLocation = new Location(userContext.user, writer, ourMapKey);
        DirAccess dir = DirAccess.create(dirReadKey, new FileProperties(name, 0, LocalDateTime.now(), isSystemFolder, Optional.empty()), ourLocation, ourParentKey, null);
        boolean success = userContext.uploadChunk(dir, userContext.user, writer, dirMapKey, Collections.EMPTY_LIST);
        if (success) {
            addSubdir(new Location(userContext.user, writer, dirMapKey), baseKey, dirReadKey);
            // now upload the changed metadata blob for dir
            userContext.uploadChunk(this, userContext.user, writer, ourMapKey, Collections.EMPTY_LIST);
            return new ReadableFilePointer(userContext.user, writer, dirMapKey, dirReadKey);
        }
        throw new IllegalStateException("Couldn't upload directory metadata!");
    }

    public boolean commit(UserPublicKey owner, User writer, byte[] ourMapKey, UserContext userContext) throws IOException {
        return userContext.uploadChunk(this, userContext.user, writer, ourMapKey, Collections.EMPTY_LIST);
    }

    public void addSubdir(Location location, SymmetricKey ourSubfolders, SymmetricKey targetBaseKey) {
        this.subfolders.add(SymmetricLocationLink.create(ourSubfolders, targetBaseKey, location));
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
            byte[] newChildMapKey = TweetNaCl.securedRandom(32);
            Location newChildLocation = new Location(context.user, entryWriterKey, newChildMapKey);
            FileAccess newChildFileAccess = rfp.fileAccess.copyTo(rfp.filePointer.baseKey, newChildBaseKey,
                    ourNewLocation, ourNewParentKey, entryWriterKey, newChildMapKey, context);
            if (newChildFileAccess.isDirectory())
                da.addSubdir(newChildLocation, newBaseKey, newChildBaseKey);
            else
                da.addFile(newChildLocation, newBaseKey, newChildBaseKey);
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
        return new DirAccess(new SymmetricLink(s2f), new SymmetricLink(s2p),
                subfolders, files, base.parent2meta, base.properties, base.retriever, base.parentLink);
    }

    public static DirAccess create(SymmetricKey subfoldersKey, FileProperties metadata, Location parentLocation, SymmetricKey parentParentKey, SymmetricKey parentKey) {
        SymmetricKey metaKey = SymmetricKey.random();
        if (parentKey == null)
            parentKey = SymmetricKey.random();
        SymmetricKey filesKey = SymmetricKey.random();
        byte[] metaNonce = metaKey.createNonce();
        SymmetricLocationLink parentLink = parentLocation == null ? null : SymmetricLocationLink.create(parentKey, parentParentKey, parentLocation);
        return new DirAccess(SymmetricLink.fromPair(subfoldersKey, filesKey, subfoldersKey.createNonce()),
                SymmetricLink.fromPair(subfoldersKey, parentKey, subfoldersKey.createNonce()),
                new ArrayList<>(), new ArrayList<>(),
                SymmetricLink.fromPair(parentKey, metaKey, parentKey.createNonce()),
                ArrayOps.concat(metaNonce, metaKey.encrypt(metadata.serialize(), metaNonce)),
                null,
                parentLink
        );
    }
}
