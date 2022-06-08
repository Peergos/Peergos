package peergos.server.fuse;
import java.util.logging.*;

import peergos.server.util.Logging;

import jnr.ffi.Pointer;
import jnr.ffi.types.*;
import peergos.shared.storage.auth.*;
import peergos.shared.user.UserContext;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import ru.serce.jnrfuse.ErrorCodes;
import ru.serce.jnrfuse.FuseFillDir;
import ru.serce.jnrfuse.FuseStubFS;
import ru.serce.jnrfuse.struct.*;

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
public class PeergosFS extends FuseStubFS implements AutoCloseable {
	private static final Logger LOG = Logging.LOG();

    protected static class PeergosStat {
        public final FileWrapper treeNode;
        public final FileProperties properties;

        public PeergosStat(FileWrapper treeNode, FileProperties properties) {
            this.treeNode = treeNode;
            this.properties = properties;
        }
    }


    private final UserContext context;
    protected volatile boolean isClosed;

    public PeergosFS(UserContext context) {
        this.context = context;
    }

    @Override
    public void close() throws Exception {
        ensureNotClosed();
        this.isClosed = true;
    }

    private void ensureNotClosed() {
        if  (isClosed)
            throw new IllegalStateException(this +" is closed");
    }

    protected int annotateAttributes(String fullPath, PeergosStat peergosStat, FileStat fileStat) {
        try {
            FileWrapper fileWrapper = peergosStat.treeNode;
            FileProperties fileProperties = peergosStat.properties;

            int mode = fileWrapper.isDirectory() ?
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
            LOG.log(Level.WARNING, t.getMessage(), t);
            return -ErrorCodes.ENOENT();
        }
    }

    @Override
    public int getattr(String s, FileStat fileStat) {
        ensureNotClosed();
        int aDefault = -ErrorCodes.ENOENT();
        return applyIfPresent(s, (peergosStat) -> annotateAttributes(s,
                peergosStat, fileStat), aDefault);
    }

    @Override
    public int readlink(String s, Pointer pointer, @size_t long l) {
        throw new IllegalStateException("Unimplemented");
    }

    @Override
    public int mknod(String s, @mode_t long l, @dev_t long l1) {
        throw new IllegalStateException("Unimplemented");
    }

    private Optional<FileWrapper> mkdir(String name, FileWrapper node)  {
        boolean isSystemFolder = false;
        try {
            return Optional.of(node.mkdir(name, context.network, isSystemFolder, context.getMirrorBat().join().map(BatWithId::id), context.crypto).get());
        } catch (Exception ioe) {
            LOG.log(Level.WARNING, ioe.getMessage(), ioe);
            return Optional.empty();
        }
    }
    @Override
    public int mkdir(String s, @mode_t long l) {
        ensureNotClosed();
        Optional<PeergosStat> current = getByPath(s);
        if (current.isPresent())
            return -ErrorCodes.ENOENT();
        Path path = PathUtil.get(s);
        String parentPath = path.getParent().toString();

        Optional<PeergosStat> parentOpt = getByPath(parentPath);

        String name = path.getFileName().toString();

        if (! parentOpt.isPresent())
            return -ErrorCodes.ENOENT();

        PeergosStat parent = parentOpt.get();
        return mkdir(name, parent.treeNode).isPresent() ? 0 : -ErrorCodes.ENOENT();
    }

    @Override
    public int unlink(String s) {
        ensureNotClosed();
        try {
            Path requested = PathUtil.get(s);
            Optional<FileWrapper> file = context.getByPath(s).get();
            if (!file.isPresent())
                return -ErrorCodes.ENOENT();

            Optional<FileWrapper> parent = context.getByPath(requested.getParent()).get();;
            if (!parent.isPresent())
                return -ErrorCodes.ENOENT();

            FileWrapper updatedParent = file.get().remove(parent.get(), requested, context).get();
            return 0;
        } catch (Exception ioe) {
            LOG.log(Level.WARNING, ioe.getMessage(), ioe);
            return -ErrorCodes.ENOENT();
        }
    }

    @Override
    public int rmdir(String s) {
        ensureNotClosed();
        Path dir = PathUtil.get(s);
        return applyIfPresent(s, (stat) -> applyIfPresent(dir.getParent().toString(), parentStat -> rmdir(stat, dir, parentStat)));
    }

    @Override
    public int symlink(String s, String s1) {
        return unimp();
    }

    private int rename(PeergosStat source, PeergosStat sourceParent, String sourcePath, String targetPath) {
        ensureNotClosed();
        try {
            Path requested = PathUtil.get(targetPath);
            String targetFilename = requested.getFileName().toString();
            Optional<FileWrapper> newParent = context.getByPath(requested.getParent().toString()).get();
            if (!newParent.isPresent())
                return -ErrorCodes.ENOENT();

            FileWrapper parent = sourceParent.treeNode;
            FileWrapper updatedParent = source.treeNode.rename(targetFilename, parent, PathUtil.get(sourcePath), context).get();
            // TODO clean up on error conditions
            if (! parent.equals(newParent.get())) {
                Path renamedInPlacePath = PathUtil.get(sourcePath).getParent().resolve(requested.getFileName().toString());
                Optional<FileWrapper> renamedOriginal = context.getByPath(renamedInPlacePath).get();
                if (! renamedOriginal.isPresent())
                    return -ErrorCodes.ENOENT();
                renamedOriginal.get().copyTo(newParent.get(), context).get();
                FileWrapper updatedParent2 = renamedOriginal.get().getUpdated(context.network).join()
                        .remove(parent.getUpdated(context.network).join(), renamedInPlacePath, context).get();
            }
            return 0;
        } catch (Exception ioe) {
            LOG.log(Level.WARNING, ioe.getMessage(), ioe);
            return -ErrorCodes.ENOENT();
        }
    }
    @Override
    public int rename(String s, String s1) {
        ensureNotClosed();
        Path source = PathUtil.get(s);
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
        ensureNotClosed();
        //TODO
        return 0;
    }

    @Override
    public int open(String s, FuseFileInfo fuseFileInfo) {
        ensureNotClosed();
        debug("OPEN %s", s);
        return 0;
    }

    @Override
    public int read(String s, Pointer pointer, @size_t long size, @off_t long offset, FuseFileInfo fuseFileInfo) {
        ensureNotClosed();
        debug("READ_OWN_FILE %s, size %d  offset %d ", s, size, offset);
        return applyIfPresent(s, (stat) -> read(stat, pointer, size, offset));
    }

    @Override
    public int write(String s, Pointer pointer, @size_t long size, @off_t long offset, FuseFileInfo fuseFileInfo) {
        ensureNotClosed();
        debug("WRITE_OWN_FILE %s, size %d  offset %d ", s, size, offset);
        Path path = PathUtil.get(s);
        String parentPath = path.getParent().toString();
        String name = path.getFileName().toString();
        return applyIfPresent(parentPath, (parent) -> write(parent, name, pointer, size, offset), -ErrorCodes.ENOENT());
    }

    @Override
    public int statfs(String s, Statvfs statvfs) {
        ensureNotClosed();
        long driveSize = context.getQuota().join();
        long blocksize = 128*1024;
        long used = context.getSpaceUsage().join();
        long free = driveSize - used;
        statvfs.f_bsize.set(blocksize);
        statvfs.f_frsize.set(blocksize);
        statvfs.f_blocks.set(driveSize / blocksize);
        statvfs.f_bfree.set(free / blocksize);
        statvfs.f_bavail.set(free / blocksize);
        statvfs.f_files.set(1024*1024);
        statvfs.f_ffree.set(1024*1024);
        statvfs.f_fsid.set('p' + 'e' * 256 + 'e' * 256*256 + 'r' * 256*256*256);
        statvfs.f_namemax.set(FileProperties.MAX_FILE_NAME_SIZE);
        statvfs.f_flag.set(4096);
        return 0;
    }

    @Override
    public int flush(String s, FuseFileInfo fuseFileInfo) {
        ensureNotClosed();
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
        ensureNotClosed();
        return 0;
    }

    @Override
    public int readdir(String s, Pointer pointer, FuseFillDir fuseFillDir, @off_t long l, FuseFileInfo fuseFileInfo) {
        ensureNotClosed();
        return applyIfPresent(s, (stat) ->readdir(stat, fuseFillDir, pointer));
    }

    @Override
    public int releasedir(String s, FuseFileInfo fuseFileInfo) {
        ensureNotClosed();
        return 0;
    }

    @Override
    public int fsyncdir(String s, FuseFileInfo fuseFileInfo) {
        ensureNotClosed();
        return 0;
    }

    @Override
    public Pointer init(Pointer pointer) {
        ensureNotClosed();
        return pointer;
    }

    @Override
    public void destroy(Pointer pointer) {
        ensureNotClosed();
    }

    @Override
    public int access(String s, int mask) {
        ensureNotClosed();
        debug("ACCESS %s, mask %d", s, mask);
        return 0;
    }

    @Override
    public int create(String s, @mode_t long l, FuseFileInfo fuseFileInfo) {
        ensureNotClosed();
        Path path = PathUtil.get(s);
        String parentPath = path.getParent().toString();
        String name = path.getFileName().toString();
        byte[] emptyData = new byte[0];

        return applyIfPresent(parentPath,
                (stat) -> write(stat,  name, emptyData, 0, 0));
    }

    @Override
    public int ftruncate(String s, @off_t long l, FuseFileInfo fuseFileInfo) {
        ensureNotClosed();
        Path path = PathUtil.get(s);
        String parentPath = path.getParent().toString();
        return applyIfBothPresent(parentPath, s, (parent, file) -> truncate(parent, file, l));
    }

    @Override
    public int fgetattr(String s, FileStat fileStat, FuseFileInfo fuseFileInfo) {
        ensureNotClosed();
        return getattr(s, fileStat);
    }

    @Override
    public int lock(String s, FuseFileInfo fuseFileInfo, int i, Flock flock) {
        LOG.info("LOCK: "+s);
        ensureNotClosed();
        return 0;
    }

//    @Override
    public int utimens(String s, Timespec[] timespecs) {
        ensureNotClosed();
        int aDefault = -ErrorCodes.ENOENT();

        Optional<PeergosStat> parentOpt = getParentByPath(s);
        if (! parentOpt.isPresent())
            return aDefault;

        return applyIfPresent(s, (stat) -> {

            Timespec access = timespecs[0], modified = timespecs[1];
            long epochSeconds = modified.tv_sec.longValue();
            long nanos = modified.tv_nsec.longValue();
            Instant instant = Instant.ofEpochSecond(epochSeconds).plusNanos(nanos);
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
                boolean isUpdated = stat.treeNode.setProperties(updated, context.crypto.hasher, context.network, Optional.of(parentOpt.get().treeNode)).get();
                return isUpdated ? 0 : -ErrorCodes.ENOENT();
            } catch (Exception ex) {
                LOG.log(Level.WARNING, ex.getMessage(), ex);
                return -ErrorCodes.ENOENT();
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
        LOG.log(Level.WARNING, ex.getMessage(), ex);
        throw ex;
    }

    protected Optional<PeergosStat> getByPath(String path) {
        try {
            Optional<FileWrapper> opt = context.getByPath(path).get();
            ;
            if (!opt.isPresent())
                return Optional.empty();
            FileWrapper treeNode = opt.get();
            FileProperties fileProperties = treeNode.getFileProperties();

            return Optional.of(new PeergosStat(treeNode, fileProperties));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private Optional<PeergosStat> getParentByPath(String  path) {
        String parentPath = PathUtil.get(path).getParent().toString();
        return getByPath(parentPath);
    }

    protected int applyIf(String path, boolean isPresent, Function<PeergosStat,  Integer> func, int _default) {
        Optional<PeergosStat> byPath = getByPath(path);
        if (byPath.isPresent() && isPresent)
            return func.apply(byPath.get());
        return _default;
    }

    protected int applyIfPresent(String path, Function<PeergosStat,  Integer> func) {
        int aDefault = -ErrorCodes.ENOENT();
        return applyIfPresent(path, func, aDefault);
    }

    protected int applyIfPresent(String path, Function<PeergosStat,  Integer> func, int _default) {
        boolean isPresent = true;
        return applyIf(path, isPresent, func, _default);
    }

    private int applyIfBothPresent(String parentPath, String filePath, BiFunction<PeergosStat, PeergosStat,  Integer> func) {
        int aDefault = -ErrorCodes.ENOENT();
        return applyIfPresent(parentPath, parentStat -> applyIfPresent(filePath, fileStat -> func.apply(parentStat, fileStat)), aDefault);
    }

    private int rmdir(PeergosStat stat, Path dir, PeergosStat parentStat) {
        FileWrapper treeNode = stat.treeNode;
        try {
            FileWrapper updatedParent = treeNode.remove(parentStat.treeNode, dir, context).get();
            return 0;
        } catch (Exception ioe) {
            LOG.log(Level.WARNING, ioe.getMessage(), ioe);
            return -ErrorCodes.ENOENT();
        }
    }

    private int readdir(PeergosStat stat, FuseFillDir fuseFillDir, Pointer pointer) {
        try {
            Set<FileWrapper> children = stat.treeNode.getChildren(context.crypto.hasher, context.network).get();
            children.stream()
                    .map(e -> e.getFileProperties().name)
                    .forEach(e -> fuseFillDir.apply(pointer, e, null, 0));
            return 0;
        } catch (Exception e) {
            LOG.log(Level.WARNING, e.getMessage(), e);
            return -ErrorCodes.ENOENT();
        }
    }

    protected Optional<byte[]> read(PeergosStat stat, long requestedSize, long offset) {
        long actualSize = stat.properties.size;

        if (offset > actualSize) {
            Optional.empty();
        }

        long size = Math.min(actualSize - offset, requestedSize);
        byte[] data =  new byte[(int) size];

        if (data.length == 0)
            return Optional.of(data);

        try (AsyncReader asyncReader = stat.treeNode.getInputStream(context.network, context.crypto, actualSize, (l) -> {}).get()){
            AsyncReader seeked = asyncReader.seekJS((int) (offset >> 32), (int) offset).get();

            // N.B. Fuse seems to assume that a file must be an integral number of disk sectors,
            // so need to tolerate EOFs up end of last sector (4KiB)
            if (offset + size > actualSize + 4096)
                return Optional.empty();

            int sizeToRead = offset + size >= actualSize ? (int) (actualSize - offset) : (int) size;
            int read = seeked.readIntoArray(data, 0, sizeToRead).get();

            return Optional.of(data);
        } catch (Exception  ioe) {
            LOG.log(Level.WARNING, ioe.getMessage(), ioe);
            return Optional.empty();
        }
    }

    public int read(PeergosStat stat, Pointer pointer, long requestedSize, long offset) {
        Optional<byte[]> dataOpt = read(stat, requestedSize, offset);

        if  (! dataOpt.isPresent())
            return -ErrorCodes.ENOENT();

        byte[] data = dataOpt.get();
        for (int i = 0; i < data.length; i++) {
            pointer.putByte(i, data[i]);
        }
        return data.length;
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
            if (size > file.properties.size) {
                long currentPos = file.properties.size;
                byte[] fullChunk = new byte[Chunk.MAX_SIZE];
                FileWrapper parentNode = parent.treeNode;
                while (currentPos < size) {
                    long nextBoundary = Math.min(size, currentPos + Chunk.MAX_SIZE - (currentPos % Chunk.MAX_SIZE));
                    int sizeInChunk = (int) (nextBoundary - currentPos);
                    byte[] data = sizeInChunk == Chunk.MAX_SIZE ? fullChunk : new byte[sizeInChunk];
                    parentNode = parentNode.uploadFileSection(file.properties.name, AsyncReader.build(data),
                            file.properties.isHidden, currentPos, currentPos + sizeInChunk, Optional.empty(),
                            true, context.network, context.crypto, x -> {},
                            file.treeNode.getLocation().getMapKey(), file.properties.streamSecret, file.treeNode.getPointer().capability.bat,
                            context.getMirrorBat().join().map(BatWithId::id)).get();
                    currentPos += sizeInChunk;
                }
            } else
                file.treeNode.truncate(size, context.network, context.crypto).get();
            return 0;
        } catch (Throwable t) {
            LOG.log(Level.WARNING, t.getMessage(), t);
            return -ErrorCodes.ENOENT();
        }
    }

    public int write(PeergosStat parent, String name, byte[] toWrite, long size, long offset) {

        try {
            long updatedLength = size + offset;
            if (Integer.MAX_VALUE < updatedLength) {
                throw new IllegalStateException("Cannot write more than " + Integer.MAX_VALUE + " bytes");
            }

            FileWrapper b = parent.treeNode.uploadFileSection(name, new AsyncReader.ArrayBacked(toWrite), false, offset,
                    offset + size, Optional.empty(), true, context.network,
                    context.crypto, l -> {},
                    context.crypto.random.randomBytes(32), Optional.empty(), Optional.of(Bat.random(context.crypto.random)),
                    context.getMirrorBat().join().map(BatWithId::id)).get();
            return (int) size;
        } catch (Throwable t) {
            LOG.log(Level.WARNING, t.getMessage(), t);
            return -ErrorCodes.ENOENT();
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
        LOG.info(msg);
    }
}
