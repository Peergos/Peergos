package peergos.fuse;

import jnr.ffi.Pointer;
import jnr.ffi.Struct;
import jnr.ffi.types.*;
import peergos.user.UserContext;
import peergos.user.fs.FileProperties;
import peergos.user.fs.FileTreeNode;
import peergos.user.fs.ReadableFilePointer;
import peergos.util.ArrayOps;
import peergos.util.Serialize;

import ru.serce.jnrfuse.ErrorCodes;
import ru.serce.jnrfuse.FuseFillDir;
import ru.serce.jnrfuse.FuseStubFS;
import ru.serce.jnrfuse.struct.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.*;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Nice FUSE API doc @
 * https://www.cs.hmc.edu/~geoff/classes/hmc.cs135.201001/homework/fuse/fuse_doc.html
 * also
 * https://github.com/libfuse/libfuse/blob/master/include/fuse.h
 */
public class PeergosFS extends FuseStubFS {

    private static class PeergosStat {
        private final FileTreeNode treeNode;
        private final FileProperties properties;

        public PeergosStat(FileTreeNode treeNode, FileProperties properties) {
            this.treeNode = treeNode;
            this.properties = properties;
        }
    }


    private final UserContext userContext;

    public PeergosFS(UserContext userContext) {
        this.userContext = userContext;
    }



    @Override
    public int getattr(String s, FileStat fileStat) {
        int aDefault = -ErrorCodes.ENOENT();
        return applyIfPresent(s, (peergosStat) -> {
            try {
                FileTreeNode fileTreeNode = peergosStat.treeNode;
                FileProperties fileProperties = peergosStat.properties;

                int mode = fileTreeNode.isDirectory() ?
                        FileStat.S_IFDIR | 0755 : FileStat.S_IFREG | 0644;

                fileStat.st_mode.set(mode);
                fileStat.st_size.set(fileProperties.size);
                return 0;
            } catch (Throwable t) {
                t.printStackTrace();
                return 1;
            }
        }, aDefault);
    }

    @Override
    public int readlink(String s, Pointer pointer, @size_t long l) {
        throw new IllegalStateException("Unimplemented");
    }

    @Override
    public int mknod(String s, @mode_t long l, @dev_t long l1) {
        throw new IllegalStateException("Unimplemented");
    }

    private Optional<ReadableFilePointer> mkdir(String name, FileTreeNode node)  {
        boolean isSystemFolder = false;
        try {
            return node.mkdir(name, userContext, isSystemFolder);
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return Optional.empty();
        }
    }
    @Override
    public int mkdir(String s, @mode_t long l) {
        Optional<PeergosStat> current = getByPath(s);
        if (current.isPresent())
            return 1;
        Path path = Paths.get(s);
        String parentPath = path.getParent().toString();

        Optional<PeergosStat> parentOpt = getByPath(parentPath);

        String name = path.getFileName().toString();

        if (! parentOpt.isPresent())
            return 1;

        PeergosStat parent = parentOpt.get();
        return mkdir(name, parent.treeNode).isPresent() ? 0 : 1;
    }

    @Override
    public int unlink(String s) {
        return unimp();
    }

    @Override
    public int rmdir(String s) {
        return applyIfPresent(s, (stat) -> rmdir(stat));
    }

    @Override
    public int symlink(String s, String s1) {
        return unimp();
    }

    private int rename(PeergosStat stat, String name) {
        try {
            Optional<FileTreeNode> treeNode = stat.treeNode.retrieveParent(userContext);
            if(! treeNode.isPresent())
                return 1;

            stat.treeNode.rename(name, userContext,
                    treeNode.get());
            return 0;
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return 1;
        }
    }
    @Override
    public int rename(String s, String s1) {
        return applyIfPresent(s, (stat) -> rename(stat, s1));
    }

    @Override
    public int link(String s, String s1) {
        return unimp();
    }

    @Override
    public int chmod(String s, @mode_t long l) {
        return unimp();
    }

    @Override
    public int chown(String s, @uid_t long l, @gid_t long l1) {
        return unimp();
    }

    @Override
    public int truncate(String s, @off_t long l) {
        //TODO
        return 0;
    }

    @Override
    public int open(String s, FuseFileInfo fuseFileInfo) {
        debug("OPEN %s", s);
        return 0;
    }

    @Override
    public int read(String s, Pointer pointer, @size_t long size, @off_t long offset, FuseFileInfo fuseFileInfo) {
        debug("READ %s, size %d  offset %d ", s, size, offset);
        return applyIfPresent(s, (stat) -> read(stat, pointer, size, offset));
    }

    @Override
    public int write(String s, Pointer pointer, @size_t long size, @off_t long offset, FuseFileInfo fuseFileInfo) {
        debug("WRITE %s, size %d  offset %d ", s, size, offset);
        Path path = Paths.get(s);
        String parentPath = path.getParent().toString();
        String name = path.getFileName().toString();
        return applyIfPresent(parentPath, (parent) -> write(parent, name, pointer, size, offset), -ErrorCodes.ENOENT());
    }

    @Override
    public int statfs(String s, Statvfs statvfs) {
        return unimp();
    }

    @Override
    public int flush(String s, FuseFileInfo fuseFileInfo) {
        return 0;
    }

//    @Override
//    public int release(String s, FuseFileInfo fuseFileInfo) {
//        return 0;
//    }

//    @Override
//    public int fsync(String s, int i, FuseFileInfo fuseFileInfo) {
//        return unimp();
//    }

//    @Override
//    public int setxattr(String s, String s1, Pointer pointer, @size_t long l, int i) {
//        return unimp();
//    }

//    @Override
//    public int getxattr(String s, String s1, Pointer pointer, @size_t long l) {
//        return 0;
//    }

//    @Override
//    public int listxattr(String s, Pointer pointer, @size_t long l) {
//        return unimp();
//    }

    @Override
    public int removexattr(String s, String s1) {
        return unimp();
    }

    @Override
    public int opendir(String s, FuseFileInfo fuseFileInfo) {
        return  0;
    }

    @Override
    public int readdir(String s, Pointer pointer, FuseFillDir fuseFillDir, @off_t long l, FuseFileInfo fuseFileInfo) {
        return applyIfPresent(s, (stat) ->readdir(stat,  fuseFillDir, pointer));
    }

    @Override
    public int releasedir(String s, FuseFileInfo fuseFileInfo) {
        return 0;
    }

    @Override
    public int fsyncdir(String s, FuseFileInfo fuseFileInfo) {
        return 0;
    }

    @Override
    public Pointer init(Pointer pointer) {
        return pointer;
    }

    @Override
    public void destroy(Pointer pointer) {

    }

    @Override
    public int access(String s, int mask) {
        debug("ACCESS %s, mask %d", s, mask);
        return 0;
    }

    @Override
    public int create(String s, @mode_t long l, FuseFileInfo fuseFileInfo) {

        Path path = Paths.get(s);
        String parentPath = path.getParent().toString();
        String name = path.getFileName().toString();
        byte[] emptyData = new byte[0];

        return applyIfPresent(parentPath,
                (stat) -> write(stat,  name, emptyData, 0, 0));
    }

    @Override
    public int ftruncate(String s, @off_t long l, FuseFileInfo fuseFileInfo) {
        return unimp();
    }

    @Override
    public int fgetattr(String s, FileStat fileStat, FuseFileInfo fuseFileInfo) {
        return getattr(s, fileStat);
    }

    @Override
    public int lock(String s, FuseFileInfo fuseFileInfo, int i, Flock flock) {
        // TODO: 01/04/16  
        return 0;
    }

    @Override
    public int utimens(String s, Timespec[] timespecs) {
        int aDefault = -ErrorCodes.ENOENT();

        Optional<PeergosStat> parentOpt = getParentByPath(s);
        if (! parentOpt.isPresent())
            return aDefault;

        return applyIfPresent(s, (stat) -> {
            Timespec modified = timespecs[0], access = timespecs[1];
            long epochSeconds = modified.tv_sec.get();
            Instant instant = Instant.ofEpochSecond(epochSeconds);
            LocalDateTime lastModified = LocalDateTime.ofInstant(instant, ZoneId.systemDefault());

//            debug("UTIMENS %s, with %s, %d, %s", s, lastModified.toString(), epochSeconds, modified.toString());

            FileProperties updated = stat.properties.withModified(lastModified);
            try {
                boolean isUpdated = stat.treeNode.setProperties(updated, userContext, parentOpt.get().treeNode);
                return isUpdated ? 0 : 1;
            } catch (IOException ex) {
                ex.printStackTrace();
                return 1;
            }
        }, aDefault);

    }

    @Override
    public int bmap(String s, @size_t long l, long l1) {
        return unimp();
    }

    @Override
    public int ioctl(String s, int i, Pointer pointer, FuseFileInfo fuseFileInfo, @u_int32_t long l, Pointer pointer1) {
        return unimp();
    }

    @Override
    public int poll(String s, FuseFileInfo fuseFileInfo, FusePollhandle fusePollhandle, Pointer pointer) {
        return unimp();
    }

//    @Override
//    public int write_buf(String s, FuseBufvec fuseBufvec, @off_t long l, FuseFileInfo fuseFileInfo) {
//
//        return write();
//    }

//    @Override
//    public int read_buf(String s, Pointer pointer, @size_t long l, @off_t long l1, FuseFileInfo fuseFileInfo) {
//        return 0;
//    }

    @Override
    public int flock(String s, FuseFileInfo fuseFileInfo, int i) {
        return unimp();
    }

    @Override
    public int fallocate(String s, int i, @off_t long l, @off_t long l1, FuseFileInfo fuseFileInfo) {
        return unimp();
    }

    private int unimp() {
        IllegalStateException ex = new IllegalStateException("Unimlemented!");
        ex.printStackTrace();
        throw ex;
    }

    private Optional<PeergosStat> getByPath(String path) {
        try {
            FileTreeNode treeRoot = userContext.getTreeRoot();
            Optional<FileTreeNode> opt = treeRoot.getDescendentByPath(path, userContext);
            if (! opt.isPresent())
                return Optional.empty();
            FileTreeNode treeNode = opt.get();
            FileProperties fileProperties = treeNode.getFileProperties();

            return Optional.of(new PeergosStat(treeNode, fileProperties));
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return Optional.empty();
        }
    }

    private Optional<PeergosStat> getParentByPath(String  path) {
        String parentPath = Paths.get(path).getParent().toString();
        return getByPath(parentPath);
    }

    private int applyIf(String path, boolean isPresent, Function<PeergosStat,  Integer> func, int _default) {
        Optional<PeergosStat> byPath = getByPath(path);
        if (byPath.isPresent() && isPresent)
            return func.apply(byPath.get());
        return _default;
    }

    private int applyIfPresent(String path, Function<PeergosStat,  Integer> func) {
        int aDefault = 1;
        return applyIfPresent(path, func, aDefault);
    }

    private int applyIfPresent(String path, Function<PeergosStat,  Integer> func, int _default) {
        boolean isPresent = true;
        return applyIf(path, isPresent, func, _default);
    }


    private int rmdir(PeergosStat stat) {
        FileTreeNode treeNode = stat.treeNode;
        try {
            Optional<FileTreeNode> opt = treeNode.retrieveParent(userContext);
            treeNode.remove(userContext,
                    opt.get());
            return 0;
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return 1;
        }
    }

    private int readdir(PeergosStat stat, FuseFillDir fuseFillDir, Pointer pointer) {
        Set<FileTreeNode> children = stat.treeNode.getChildren(userContext);
        children.stream()
                .map(e ->  e.getFileProperties().name)
                .forEach(e ->  fuseFillDir.apply(pointer, e,  null, 0));
        return 0;
    }

    public int read(PeergosStat stat, Pointer pointer, long requestedSize, long offset) {
        long actualSize = stat.properties.size;
        if (offset > actualSize) {
            return 0;
        }
        long size = Math.min(actualSize, requestedSize);
        try {
            InputStream is = stat.treeNode.getInputStream(userContext, actualSize, (l) -> {});

            is.skip(offset);

            for (long i = 0; i < size; i++)
                pointer.putByte(i, (byte) is.read());

        } catch (Exception  ioe) {
            ioe.printStackTrace();
            return 1;
        }

        return (int) size;
    }

    private byte[] getData(Pointer pointer, int size) {
        if (Integer.MAX_VALUE < size) {
            throw new IllegalStateException("Cannot write more than " + Integer.MAX_VALUE + " bytes");
        }

        byte[] toWrite = new byte[size];
        pointer.get(0, toWrite, 0, size);
        return toWrite;
    }

    public int write(PeergosStat parent, String name, byte[] toWrite, long size, long offset) {

        debug("WRITE data %s, size %d, offset %d, parent %s, name %s", ArrayOps.bytesToHex(toWrite), size, offset, parent.properties.name, name);

        try {
            long updatedLength = size + offset;
            if (Integer.MAX_VALUE < updatedLength) {
                throw new IllegalStateException("Cannot write more than " + Integer.MAX_VALUE + " bytes");
            }

            int iOffset = (int) offset;
            int iSize= (int) size;

            Optional<FileTreeNode> targetOpt = parent.treeNode.getChildren(userContext).stream()
                    .filter(e -> e.getFileProperties().name.equals(name))
                    .findFirst();


            byte[] uploadData = null;
            if (targetOpt.isPresent()) {
                //get current data  and overwrite
                FileTreeNode treeNode = targetOpt.get();
                InputStream is = treeNode.getInputStream(userContext, treeNode.getFileProperties().size, (l) -> {
                });
                try {
                    byte[] data = Serialize.readFully(is);
                    data = Serialize.ensureSize(data, (int) updatedLength);
                    System.arraycopy(toWrite, 0, data, iOffset, iSize);
                    uploadData = data;
                } catch (IOException ioe) {
                    ioe.printStackTrace();
                    return 1;
                }
            } else {
                //nothing there yet
                uploadData = new byte[(int) updatedLength];
                System.arraycopy(toWrite, 0, uploadData, iOffset, toWrite.length);
            }

            //re-upload data
            try {
                parent.treeNode.uploadFile(name, new ByteArrayInputStream(uploadData), updatedLength, userContext, (l) -> {});
                return iSize;
            } catch (IOException e) {
                e.printStackTrace();
                return 1;
            }

        } catch (Throwable t) {
            t.printStackTrace();
            return 1;

        }

    }

    public int write(PeergosStat parent, String name, Pointer pointer, long size, long offset) {
        byte[] data = getData(pointer, (int) size);
        return write(parent, name, data, size, offset);
    }

    /**
     * JNR doesn't play nicely with debugger at all => debugging like it's 1990
     */
    private void debug(String template, Object... obj) {
        String msg = String.format(template, obj);
        System.out.println(msg);
    }
}
