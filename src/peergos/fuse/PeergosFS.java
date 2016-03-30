package peergos.fuse;

import jnr.ffi.Pointer;
import jnr.ffi.types.*;
import peergos.user.UserContext;
import peergos.user.fs.FileProperties;
import peergos.user.fs.FileTreeNode;
import peergos.user.fs.ReadableFilePointer;
import ru.serce.jnrfuse.AbstractFuseFS;

import ru.serce.jnrfuse.FuseFillDir;
import ru.serce.jnrfuse.struct.*;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

public class PeergosFS extends AbstractFuseFS {

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
        });
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
        return unimp();
    }

    @Override
    public int open(String s, FuseFileInfo fuseFileInfo) {
        return 0;
    }

    @Override
    public int read(String s, Pointer pointer, @size_t long l, @off_t long l1, FuseFileInfo fuseFileInfo) {
        return unimp();
    }

    @Override
    public int write(String s, Pointer pointer, @size_t long l, @off_t long l1, FuseFileInfo fuseFileInfo) {
        return unimp();
    }

    @Override
    public int statfs(String s, Statvfs statvfs) {
        return unimp();
    }

    @Override
    public int flush(String s, FuseFileInfo fuseFileInfo) {
        return unimp();
    }

    @Override
    public int release(String s, FuseFileInfo fuseFileInfo) {
        return unimp();
    }

    @Override
    public int fsync(String s, int i, FuseFileInfo fuseFileInfo) {
        return unimp();
    }

    @Override
    public int setxattr(String s, String s1, Pointer pointer, @size_t long l, int i) {
        return unimp();
    }

    @Override
    public int getxattr(String s, String s1, Pointer pointer, @size_t long l) {
        return unimp();
    }

    @Override
    public int listxattr(String s, Pointer pointer, @size_t long l) {
        return unimp();
    }

    @Override
    public int removexattr(String s, String s1) {
        return unimp();
    }

    @Override
    public int opendir(String s, FuseFileInfo fuseFileInfo) {
        return  0;
//        return unimp();
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
        return unimp();
    }

    @Override
    public Pointer init(Pointer pointer) {
        return null;
    }

    @Override
    public void destroy(Pointer pointer) {

    }

    @Override
    public int access(String s, int i) {
        return unimp();
    }

    @Override
    public int create(String s, @mode_t long l, FuseFileInfo fuseFileInfo) {
        return unimp();
    }

    @Override
    public int ftruncate(String s, @off_t long l, FuseFileInfo fuseFileInfo) {
        return unimp();
    }

    @Override
    public int fgetattr(String s, FileStat fileStat, FuseFileInfo fuseFileInfo) {
        return unimp();
    }

    @Override
    public int lock(String s, FuseFileInfo fuseFileInfo, int i, Flock flock) {
        return unimp();
    }

    @Override
    public int utimens(String s, Timespec[] timespecs) {
        return unimp();
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

    @Override
    public int write_buf(String s, FuseBufvec fuseBufvec, @off_t long l, FuseFileInfo fuseFileInfo) {
        return unimp();
    }

    @Override
    public int read_buf(String s, Pointer pointer, @size_t long l, @off_t long l1, FuseFileInfo fuseFileInfo) {
        return unimp();
    }

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

    private int applyIf(String path, boolean isPresent, Function<PeergosStat,  Integer> func) {
        Optional<PeergosStat> byPath = getByPath(path);
        if (byPath.isPresent() && isPresent)
            return func.apply(byPath.get());
        return 1;
    }

    private int applyIfPresent(String path, Function<PeergosStat,  Integer> func) {
        boolean isPresent = true;
        return applyIf(path, isPresent, func);
    }

    private int applyIfAbsent(String path, Function<PeergosStat,  Integer> func) {
        boolean isPresent = false;
        return applyIf(path, isPresent, func);
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
                .map(e ->  getPropertiesUnchecked(e).name)
                .forEach(e ->  fuseFillDir.apply(pointer, e,  null, 0));
        return 0;
    }

    private static FileProperties getPropertiesUnchecked(FileTreeNode  node) {
        try {
            return node.getFileProperties();
        }  catch (IOException ioe) {
            throw new IllegalStateException(ioe);
        }
    }
}
