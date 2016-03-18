package peergos.user.fs;

import peergos.crypto.*;
import peergos.crypto.symmetric.*;
import peergos.user.*;
import peergos.util.*;

import java.io.*;
import java.util.*;

public class DirAccess extends FileAccess {

    public DirAccess(SymmetricLink subfolders2files, SymmetricLink subfolders2parent, subfolders, files,
                     SymmetricLink parent2meta, byte[] properties, FileRetriever retriever, SymmetricLocationLink parentLink) {
        super(parent2meta, properties, retriever, parentLink);
        this.subfolders2files = subfolders2files;
        this.subfolders2parent = subfolders2parent;
        this.subfolders = subfolders;
        this.files = files;
    }

    public void serialize(DataOutputStream bout) {
        super.serialize(bout);
        bout.writeArray(this.subfolders2parent.serialize());
        bout.writeArray(this.subfolders2files.serialize());
        bout.writeInt(0);
        bout.writeInt(this.subfolders.length)
        for (int i=0; i < this.subfolders.length; i++)
            bout.writeArray(this.subfolders[i].serialize());
        bout.writeInt(this.files.length)
        for (int i=0; i < this.files.length; i++)
            bout.writeArray(this.files[i].serialize());
    }

    // Location, SymmetricKey, SymmetricKey
    public void addFile(Location location, SymmetricKey ourSubfolders, SymmetricKey targetParent) {
        const filesKey = this.subfolders2files.target(ourSubfolders);
        var nonce = filesKey.createNonce();
        var loc = location.encrypt(filesKey, nonce);
        var link = concat(nonce, filesKey.encrypt(targetParent.serialize(), nonce));
        var buf = new ByteArrayOutputStream();
        buf.writeArray(link);
        buf.writeArray(loc);
        this.files.push(SymmetricLocationLink.create(filesKey, targetParent, location));
    }

    public boolean removeChild(RetrievedFilePointer childRetrievedPointer, ReadableFilePointer readablePointer, UserContext context) {
        if (childRetrievedPointer.fileAccess.isDirectory()) {
            const newsubfolders = [];
            for (var i=0; i < this.subfolders.length; i++) {
                var target = this.subfolders[i].targetLocation(readablePointer.baseKey);
                var keep = true;
                if (arraysEqual(target.mapKey, childRetrievedPointer.filePointer.mapKey))
                    if (arraysEqual(target.writer.getPublicKeys(), childRetrievedPointer.filePointer.writer.getPublicKeys()))
                        if (arraysEqual(target.owner.getPublicKeys(), childRetrievedPointer.filePointer.owner.getPublicKeys()))
                            keep = false;
                if (keep)
                    newsubfolders.push(this.subfolders[i]);
            }
            this.subfolders = newsubfolders;
        } else {
            const newfiles = [];
            const filesKey = subfolders2files.target(readablePointer.baseKey)
            for (var i=0; i < this.files.length; i++) {
                var target = this.files[i].targetLocation(filesKey);
                var keep = true;
                if (arraysEqual(target.mapKey, childRetrievedPointer.filePointer.mapKey))
                    if (arraysEqual(target.writer.getPublicKeys(), childRetrievedPointer.filePointer.writer.getPublicKeys()))
                        if (arraysEqual(target.owner.getPublicKeys(), childRetrievedPointer.filePointer.owner.getPublicKeys()))
                            keep = false;
                if (keep)
                    newfiles.push(this.files[i]);
            }
            this.files = newfiles;
        }
        return context.uploadChunk(this, readablePointer.owner, readablePointer.writer, readablePointer.mapKey);
    }

    // 0=FILE, 1=DIR
    public byte getType() {
        return 1;
    }

    // returns [RetrievedFilePointer]
    public Set<RetrievedFilePointer> getChildren(UserContext context, SymmetricKey baseKey) {
        const prom1 = context.retrieveAllMetadata(this.subfolders, baseKey);
        const prom2 = context.retrieveAllMetadata(this.files, this.subfolders2files.target(baseKey));
        return Promise.all([prom1, prom2]).then(function(mapArr) {
            const res = mapArr[0];
            for (var i=0; i < mapArr[1].length; i++)
                res.push(mapArr[1][i]);
            const retrievedFilePointers = res.map(function(entry) {
               return new RetrievedFilePointer(entry[0],  entry[1]);
            })
            return Promise.resolve(retrievedFilePointers);
        })
    }

    public Set<Location> getChildrenLocations(SymmetricKey baseKey) {
        Set<Location> res = new HashSet<>();
        for (var i=0; i < this.subfolders.length; i++) {
            var subfolderLink = this.subfolders[i];
            res.add(subfolderLink.targetLocation(baseKey));
        }
        SymmetricKey filesKey = this.subfolders2files.target(baseKey);
        for (int i=0; i < this.files.length; i++) {
            var fileLink = this.files[i];
            res.add(fileLink.targetLocation(filesKey));
        }
        return res;
    }

    public SymmetricKey getParentKey(SymmetricKey subfoldersKey) {
        return this.subfolders2parent.target(subfoldersKey);
    }

    public SymmetricKey getFilesKey(SymmetricKey subfoldersKey) {
        return this.subfolders2files.target(subfoldersKey);
    }

    //String, UserContext, User ->
    public boolean mkdir(name, userContext, writer, ourMapKey, baseKey, optionalBaseKey, isSystemFolder) {
        if (!(writer instanceof User))
            throw "Can't modify a directory without write permission (writer must be a User)!";
        const dirReadKey = optionalBaseKey != null ? optionalBaseKey : SymmetricKey.random();
        const dirMapKey = window.nacl.randomBytes(32); // root will be stored under this in the core node
        const ourParentKey = this.getParentKey(baseKey);
        const ourLocation = new Location(userContext.user, writer, ourMapKey);
        const dir = DirAccess.create(dirReadKey, new FileProperties(name, 0, Date.now(), (isSystemFolder == null || !isSystemFolder) ? 0 : 1), ourLocation, ourParentKey);
        const that = this;
        return userContext.uploadChunk(dir, userContext.user, writer, dirMapKey)
                .then(function(success) {
            if (success) {
                that.addSubdir(new Location(userContext.user, writer, dirMapKey), baseKey, dirReadKey);
                // now upload the changed metadata blob for dir
                return userContext.uploadChunk(that, userContext.user, writer, ourMapKey).then(function(res) {
                    return Promise.resolve(new ReadableFilePointer(userContext.user, writer, dirMapKey, dirReadKey));
                });
            }
            return Promise.resolve(false);
        });
    }

    public boolean commit(UserPublicKey owner, UserPublicKey writer, byte[] ourMapKey, UserContext userContext) {
        return userContext.uploadChunk(this, userContext.user, writer, ourMapKey)
    }

    public void addSubdir(Location location, SymmetricKey ourSubfolders, SymmetricKey targetBaseKey) {
        this.subfolders.push(SymmetricLocationLink.create(ourSubfolders, targetBaseKey, location));
    }

    public DirAccess copyTo(SymmetricKey baseKey, SymmetricKey newBaseKey, Location parentLocation,
                            SymmetricKey parentparentKey, UserPublicKey entryWriterKey, byte[] newMapKey, UserContext context) {
        const parentKey = this.getParentKey(baseKey);
        const props = this.getFileProperties(parentKey);
        const da = DirAccess.create(newBaseKey, props, this.retriever, parentLocation, parentparentKey, parentKey);
        const ourNewParentKey = da.getParentKey(newBaseKey);
        const ourNewLocation = new Location(context.user, entryWriterKey, newMapKey);

        return this.getChildren(context, baseKey).then(function(RFPs) {
            // upload new metadata blob for each child and re-add child
            var proms = RFPs.map(function(rfp) {
                var newChildBaseKey = rfp.fileAccess.isDirectory() ? SymmetricKey.random() : rfp.filePointer.baseKey;
                var newChildMapKey = window.nacl.randomBytes(32);
                var newChildLocation = new Location(context.user, entryWriterKey, newChildMapKey);
                return rfp.fileAccess.copyTo(rfp.filePointer.baseKey, newChildBaseKey, ourNewLocation, ourNewParentKey, entryWriterKey, newChildMapKey, context).then(function(newChildFileAccess){
                    if (newChildFileAccess.isDirectory())
                        da.addSubdir(newChildLocation, newBaseKey, newChildBaseKey);
                    else
                        da.addFile(newChildLocation, newBaseKey, newChildBaseKey);
                    return Promise.resolve(true);
                });
            });
            return Promise.all(proms);
        }).then(function(res) {
            return context.uploadChunk(da, context.user, entryWriterKey, newMapKey).then(function(res) {
                return Promise.resolve(da);
            });
        });
    }

    public static DirAccess deserialize(FileAccess base, DataInputStream bin) {
        byte[] s2p = bin.readArray();
        byte[] s2f = bin.readArray();

        int nSharingKeys = bin.readInt();
        var files = [], subfolders = [];
        int nsubfolders = bin.readInt();
        for (int i=0; i < nsubfolders; i++)
            subfolders[i] = new SymmetricLocationLink(bin.readArray());
        int nfiles = bin.readInt();
        for (int i=0; i < nfiles; i++)
            files[i] = new SymmetricLocationLink(bin.readArray());
        return new DirAccess(new SymmetricLink(s2f),
                new SymmetricLink(s2p),
                subfolders, files, base.parent2meta, base.properties, base.retriever, base.parentLink);
    }

    public static DirAccess create(SymmetricKey subfoldersKey, metadata, Location parentLocation, SymmetricKey parentParentKey, SymmetricKey parentKey) {
        SymmetricKey metaKey = SymmetricKey.random();
        if (parentKey == null)
            parentKey = SymmetricKey.random();
        SymmetricKey filesKey = SymmetricKey.random();
        byte[] metaNonce = metaKey.createNonce();
        SymmetricLocationLink parentLink = parentLocation == null ? null : SymmetricLocationLink.create(parentKey, parentParentKey, parentLocation);
        return new DirAccess(SymmetricLink.fromPair(subfoldersKey, filesKey, subfoldersKey.createNonce()),
                SymmetricLink.fromPair(subfoldersKey, parentKey, subfoldersKey.createNonce()),
                Collections.EMPTY_LIST, Collections.EMPTY_LIST,
                SymmetricLink.fromPair(parentKey, metaKey, parentKey.createNonce()),
                ArrayOps.concat(metaNonce, metaKey.encrypt(metadata.serialize(), metaNonce)),
                null,
                parentLink
        );
    }
}
