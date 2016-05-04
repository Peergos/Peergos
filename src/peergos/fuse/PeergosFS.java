package peergos.fuse;

import jnr.ffi.Pointer;
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
import java.util.*;
import java.util.function.*;

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

                Instant instant = fileProperties.modified.toInstant(ZonedDateTime.now().getOffset());
                long epochSecond = instant.getEpochSecond();
                long nanoSeconds = instant.getNano();


                fileStat.st_mtim.tv_sec.set(epochSecond);
                fileStat.st_mtim.tv_nsec.set(nanoSeconds);

                fileStat.st_atim.tv_nsec.set(epochSecond);
                fileStat.st_atim.tv_nsec.set(nanoSeconds);
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
        try {
            Path requested = Paths.get(s);
            Optional<FileTreeNode> file = userContext.getByPath(s);
            if (!file.isPresent())
                return 1;

            Optional<FileTreeNode> parent = userContext.getByPath(requested.getParent().toString());
            if (!parent.isPresent())
                return 1;

            boolean removed = file.get().remove(userContext, parent.get());
            return removed ? 0 : 1;
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return 1;
        }
    }

    @Override
    public int rmdir(String s) {
        Path dir = Paths.get(s);
        return applyIfPresent(s, (stat) -> applyIfPresent(dir.getParent().toString(), parentStat -> rmdir(stat, parentStat)));
    }

    @Override
    public int symlink(String s, String s1) {
        return unimp();
    }

    private int rename(PeergosStat source, PeergosStat sourceParent, String sourcePath, String name) {
        try {
            Path requested = Paths.get(name);
            Optional<FileTreeNode> newParent = userContext.getByPath(requested.getParent().toString());
            if (!newParent.isPresent())
                return 1;

            FileTreeNode parent = sourceParent.treeNode;
            source.treeNode.rename(requested.getFileName().toString(), userContext, parent);
            // TODO clean up on error conditions
            if (!parent.equals(newParent.get())) {
                Path renamedInPlacePath = Paths.get(sourcePath).getParent().resolve(requested.getFileName().toString());
                Optional<FileTreeNode> renamedOriginal = userContext.getByPath(renamedInPlacePath.toString());
                if (!renamedOriginal.isPresent())
                    return 1;
                boolean copyResult = renamedOriginal.get().copyTo(newParent.get(), userContext);
                boolean removed = source.treeNode.remove(userContext, parent);
                if (!copyResult || !removed)
                    return 1;
            }
            return 0;
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return 1;
        }
    }
    @Override
    public int rename(String s, String s1) {
        Path source = Paths.get(s);
        return applyIfPresent(s, (stat) -> applyIfPresent(source.getParent().toString(), parentStat -> rename(stat, parentStat, s, s1)));
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
        statvfs.f_bsize.set(128*1024L);
//        return 0;
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
        Path path = Paths.get(s);
        String parentPath = path.getParent().toString();
        return applyIfBothPresent(parentPath, s, (parent, file) -> truncate(parent, file, l));
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

//    @Override
    public int utimens(String s, Timespec[] timespecs) {
        int aDefault = -ErrorCodes.ENOENT();

        Optional<PeergosStat> parentOpt = getParentByPath(s);
        if (! parentOpt.isPresent())
            return aDefault;

        return applyIfPresent(s, (stat) -> {

            Timespec access = timespecs[0], modified = timespecs[1];
            long epochSeconds = modified.tv_sec.longValue();
            Instant instant = Instant.ofEpochSecond(epochSeconds);
            LocalDateTime lastModified = LocalDateTime.ofInstant(instant, ZoneId.systemDefault());

            FileProperties updated = stat.properties.withModified(lastModified);

            /*
            debug("utimens %s, with %s, %d, %s, updated %s", s,
                    lastModified.toString(),
                    epochSeconds,
                    modified.toString(),
                    updated.toString());
                    */

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
        Optional<FileTreeNode> opt = userContext.getByPath(path);
        if (! opt.isPresent())
            return Optional.empty();
        FileTreeNode treeNode = opt.get();
        FileProperties fileProperties = treeNode.getFileProperties();

        return Optional.of(new PeergosStat(treeNode, fileProperties));
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

    private int applyIfBothPresent(String parentPath, String filePath, BiFunction<PeergosStat, PeergosStat,  Integer> func) {
        int aDefault = 1;
        return applyIfPresent(parentPath, parentStat -> applyIfPresent(filePath, fileStat -> func.apply(parentStat, fileStat)), aDefault);
    }

    private int rmdir(PeergosStat stat, PeergosStat parentStat) {
        FileTreeNode treeNode = stat.treeNode;
        try {
            treeNode.remove(userContext, parentStat.treeNode);
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

            for (long i = 0; i < size; i++) {
                // N.B. Fuse seems to assume that a file must be an integral number of disk sectors,
                // so need to tolerate EOFs up end of last sector (4KiB)
                if (i + offset >= actualSize && i + offset < actualSize + 4096)
                    continue;
                int read = is.read();
                if (read < 0)
                    return 1;
                pointer.putByte(i, (byte) read);
            }

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

    public int truncate(PeergosStat parent, PeergosStat file, long size) {

        debug("TRUNCATE file %s, size %d", file.properties.name, size);

        try {
            if (size > Integer.MAX_VALUE)
                throw new IllegalStateException("Trying to truncate/extend to > 4GiB! "+ size);

            byte[] original = Serialize.readFully(file.treeNode.getInputStream(userContext, file.properties.size, l -> {}));
            // TODO do this smarter by only writing the chunk containing the new endpoint, and deleting all following chunks
            // or extending with 0s
            byte[] truncated = Arrays.copyOfRange(original, 0, (int)size);
            file.treeNode.remove(userContext, parent.treeNode);
            boolean b = parent.treeNode.uploadFile(file.properties.name, new ByteArrayInputStream(truncated), truncated.length, userContext, l -> {});
            return b ? (int) size : 1;
        } catch (Throwable t) {
            t.printStackTrace();
            return 1;
        }
    }

    public int write(PeergosStat parent, String name, byte[] toWrite, long size, long offset) {

        try {
            long updatedLength = size + offset;
            if (Integer.MAX_VALUE < updatedLength) {
                throw new IllegalStateException("Cannot write more than " + Integer.MAX_VALUE + " bytes");
            }

            boolean b = parent.treeNode.uploadFile(name, new ByteArrayInputStream(toWrite), offset, offset + size, userContext, l -> {});
            return b ? (int) size : 1;
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
